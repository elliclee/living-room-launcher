#!/usr/bin/env python3
"""客厅桌面私有局域网更新源：init、publish APK、serve。"""
from pathlib import Path
import argparse
import hashlib
import hmac
import http.server
import ipaddress
import json
import os
import re
import secrets
import shutil
import tempfile

ROOT = Path(__file__).resolve().parent
STATE = ROOT / '.build/update-server-state.json'
STORE = ROOT / '.build/update-packages'
MAX_APK = 64 * 1024 * 1024
import build
PACKAGE = build.project_identity()['package']


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as out:
            json.dump(value, out, ensure_ascii=False, indent=2)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def config():
    return json.loads(STATE.read_text())


def initialize(host, port):
    if STATE.exists():
        current = config()
        if current['host'] != host or current['port'] != port:
            raise ValueError('已有固定更新配置；请保留原地址与端口')
        return
    address = ipaddress.ip_address(host)
    if address.version != 4 or not any(address in network for network in map(ipaddress.ip_network, ['10.0.0.0/8','172.16.0.0/12','192.168.0.0/16'])) or not 1 <= port <= 65535:
        raise ValueError('需要电脑的 RFC1918 局域网 IPv4 地址及有效端口')
    atomic_json(STATE, {'host': host, 'port': port, 'token': secrets.token_urlsafe(32)})


def inspect_apk(path):
    import build
    if not path.is_file() or not 0 < path.stat().st_size <= MAX_APK:
        raise ValueError('APK 不存在或超过 64 MiB')
    signer = build.run([build.sdktool('apksigner'), 'verify', '--print-certs', path])
    expected_identity = build.signing_identity()
    pattern = r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)'
    identities = re.findall(pattern, signer)
    if not identities or identities != expected_identity:
        raise ValueError('拒绝不同签名的 APK')
    badging = build.run([build.sdktool('aapt2'), 'dump', 'badging', path])
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not match or match[1] != PACKAGE:
        raise ValueError('拒绝不同包名的 APK')
    tree = build.run([build.sdktool('aapt2'), 'dump', 'xmltree', path, '--file', 'AndroidManifest.xml'])
    if tree.count('(Raw: "android.intent.category.HOME")') != 1 or f"leanback-launchable-activity: name='{PACKAGE}.HomeActivity'" not in badging:
        raise ValueError('更新包必须保留原 HOME 入口')
    with path.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    return {'format_version': 1, 'package': PACKAGE, 'version_code': int(match[2]),
            'version': match[3], 'bytes': path.stat().st_size,
            'sha256': digest}


def publish(path, notes):
    STORE.mkdir(parents=True, exist_ok=True)
    # Inspect a private snapshot, so a concurrently rebuilt source cannot change after validation.
    fd, temporary = tempfile.mkstemp(dir=STORE, suffix='.apk')
    os.close(fd)
    snapshot = Path(temporary)
    try:
        if path.stat().st_size > MAX_APK:
            raise ValueError('APK 超过 64 MiB')
        shutil.copyfile(path, snapshot)
        manifest = inspect_apk(snapshot)
        if len(notes) > 1000:
            raise ValueError('更新说明过长')
        manifest['notes'] = notes
        target = STORE / (manifest['sha256'] + '.apk')
        if target.exists():
            if target.read_bytes() != snapshot.read_bytes():
                raise ValueError('已发布内容与摘要不符')
        else:
            os.replace(snapshot, target)
        atomic_json(STORE / 'manifest.json', manifest)
        return manifest
    finally:
        snapshot.unlink(missing_ok=True)


def make_server(settings, bind=None):
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # No credentials or phone/TV request paths in logs.

        def do_GET(self):
            expected = 'Bearer ' + settings['token']
            if not hmac.compare_digest(self.headers.get('Authorization', '').encode('utf-8'), expected.encode('utf-8')):
                self.send_error(401)
                return
            if self.path == '/manifest.json':
                path = STORE / 'manifest.json'
                content_type = 'application/json; charset=utf-8'
            elif re.fullmatch(r'/packages/[0-9a-f]{64}\.apk', self.path):
                path = STORE / self.path.rsplit('/', 1)[1]
                content_type = 'application/vnd.android.package-archive'
            else:
                self.send_error(404)
                return
            try:
                with path.open('rb') as source:
                    self.send_response(200)
                    self.send_header('Content-Type', content_type)
                    self.send_header('Content-Length', str(os.fstat(source.fileno()).st_size))
                    self.send_header('Cache-Control', 'no-store')
                    self.end_headers()
                    shutil.copyfileobj(source, self.wfile, length=65536)
            except FileNotFoundError:
                self.send_error(404)
            except (BrokenPipeError, ConnectionResetError, TimeoutError):
                pass

        def setup(self):
            super().setup()
            self.connection.settimeout(15)

    return http.server.HTTPServer((bind or settings['host'], settings['port']), Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    init = commands.add_parser('init')
    init.add_argument('--host', required=True)
    init.add_argument('--port', type=int, default=57816)
    pub = commands.add_parser('publish')
    pub.add_argument('apk', type=Path)
    pub.add_argument('--notes', default='')
    commands.add_parser('serve')
    args = parser.parse_args()
    if args.command == 'init':
        initialize(args.host, args.port)
        print('固定更新配置已准备；私有令牌不输出。')
    elif args.command == 'publish':
        manifest = publish(args.apk.resolve(), args.notes)
        print(json.dumps(manifest, ensure_ascii=False))
    else:
        settings = config()
        server = make_server(settings)
        print(f"更新服务已启动：{settings['host']}:{settings['port']}；按 Ctrl+C 停止。", flush=True)
        try:
            server.serve_forever()
        finally:
            server.server_close()


if __name__ == '__main__':
    main()
