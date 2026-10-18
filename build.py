#!/usr/bin/env python3
"""Build the standalone launcher with an existing Android SDK and JDK; no dependency downloads."""
from pathlib import Path
import base64
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / '.build'
DIST = ROOT / 'dist'
NS = '{http://schemas.android.com/apk/res/android}'
SDK = Path(os.environ.get('LIVINGROOM_SDK') or os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or
           (Path.home() / 'Library/Android/sdk' if sys.platform == 'darwin' else
            Path(os.environ.get('LOCALAPPDATA', Path.home())) / 'Android/Sdk' if os.name == 'nt' else Path.home() / 'Android/Sdk'))

def java_home():
    chosen = os.environ.get('LIVINGROOM_JDK') or os.environ.get('JAVA_HOME')
    if chosen:
        return Path(chosen)
    if sys.platform == 'darwin' and Path('/usr/libexec/java_home').exists():
        result = subprocess.run(['/usr/libexec/java_home', '-v', '17'], capture_output=True, text=True)
        if result.returncode == 0:
            return Path(result.stdout.strip())
    javac = shutil.which('javac')
    return Path(javac).resolve().parent.parent if javac else Path('/jdk-not-configured')

JDK = java_home()
TOOLS = SDK / 'build-tools/35.0.0'
PLATFORM = SDK / 'platforms/android-35/android.jar'
ENV = dict(os.environ, JAVA_HOME=str(JDK))
ENV['PATH'] = str(JDK / 'bin') + os.pathsep + ENV.get('PATH', '')
PERMISSIONS = {'android.permission.QUERY_ALL_PACKAGES', 'android.permission.INTERNET', 'android.permission.REQUEST_INSTALL_PACKAGES'}

def sdktool(name):
    suffix = ('.bat' if name in {'d8', 'apksigner'} else '.exe') if os.name == 'nt' else ''
    return TOOLS / (name + suffix)

def javatool(name):
    return JDK / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))

def run(args):
    result = subprocess.run([str(a) for a in args], cwd=ROOT, env=ENV,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if result.returncode:
        raise RuntimeError(f'{Path(str(args[0])).name} failed ({result.returncode}): {result.stdout}')
    return result.stdout

def project_identity():
    manifest = ET.parse(ROOT / 'AndroidManifest.xml').getroot()
    return {'package': manifest.attrib['package'], 'version_code': int(manifest.attrib[NS + 'versionCode']),
            'version': manifest.attrib[NS + 'versionName']}

def signing_identity():
    # This baseline belongs to this checkout, not to whichever APK is being published.
    value = json.loads((BUILD / 'signing-identity.json').read_text())['certificate_sha256']
    if not re.fullmatch(r'[0-9a-f]{64}', value):
        raise ValueError('Invalid local signing identity')
    return [value]

def prepare_signing():
    key, password = BUILD / 'signing.p12', BUILD / 'signing-password'
    if key.exists() != password.exists():
        raise RuntimeError('Signing files are incomplete; restore your own signing backup before building')
    if not key.exists():
        with password.open('x') as out:
            os.chmod(password, 0o600)
            out.write(secrets.token_urlsafe(32))
    ENV['LIVINGROOM_STOREPASS'] = password.read_text()
    if not key.exists():
        run([javatool('keytool'), '-genkeypair', '-keystore', key, '-storetype', 'PKCS12',
             '-storepass:env', 'LIVINGROOM_STOREPASS', '-alias', 'living-room', '-keyalg', 'RSA', '-keysize', '2048',
             '-validity', '3650', '-dname', 'CN=Living Room Launcher Local Build'])
        os.chmod(key, 0o600)
    pem = run([javatool('keytool'), '-exportcert', '-rfc', '-keystore', key, '-alias', 'living-room',
               '-storepass:env', 'LIVINGROOM_STOREPASS'])
    cert = re.search(r'-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----', pem, re.S)
    if not cert:
        raise RuntimeError('Cannot export local signing certificate')
    digest = hashlib.sha256(base64.b64decode(cert[1])).hexdigest()
    identity = BUILD / 'signing-identity.json'
    if identity.exists():
        if signing_identity() != [digest]:
            raise RuntimeError('Signing key changed; restore the original key for this checkout')
    else:
        identity.write_text(json.dumps({'certificate_sha256': digest}))
    return key

def main():
    for path in [PLATFORM, javatool('javac'), javatool('keytool')] + [sdktool(x) for x in ['aapt2', 'd8', 'zipalign', 'apksigner']]:
        if not path.is_file():
            raise RuntimeError(f'Missing local tool: {path}. Configure LIVINGROOM_SDK and LIVINGROOM_JDK (JDK17, SDK35/build-tools35.0.0).')
    identity = project_identity()
    if not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z._-]{0,79}', identity['version']) or identity['version_code'] <= 0:
        raise RuntimeError('Invalid manifest version')
    manifest = ET.parse(ROOT / 'AndroidManifest.xml').getroot()
    if {node.attrib[NS+'name'] for node in manifest.findall('uses-permission')} != PERMISSIONS:
        raise RuntimeError('Unexpected launcher permissions')
    BUILD.mkdir(exist_ok=True)
    state_path = BUILD / 'update-server-state.json'
    if not state_path.exists():
        raise RuntimeError('Run python3 update_server.py init --host YOUR_COMPUTER_LAN_IP before building')
    state = json.loads(state_path.read_text())
    generated = BUILD / 'generated/io/github/elliclee/livingroom/UpdateConfig.java'
    generated.parent.mkdir(parents=True, exist_ok=True)
    generated.write_text('package io.github.elliclee.livingroom; final class UpdateConfig {\n'
                         + 'static final String HOST = ' + json.dumps(state['host']) + ';\n'
                         + 'static final int PORT = ' + str(int(state['port'])) + ';\n'
                         + 'static final String TOKEN = ' + json.dumps(state['token']) + ';\n}\n')
    os.chmod(generated, 0o600)
    classes, dex = BUILD / 'classes', BUILD / 'dex'
    # Replacing a successful compile removes obsolete anonymous classes after source refactors.
    staging = Path(tempfile.mkdtemp(prefix='classes-', dir=BUILD))
    try:
        run([javatool('javac'), '-encoding', 'UTF-8', '--release', '8', '-classpath', PLATFORM,
             '-d', staging, *sorted((ROOT / 'src').rglob('*.java')), generated])
        if classes.exists():
            shutil.rmtree(classes)
        staging.rename(classes)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    dex.mkdir(exist_ok=True)
    run([sdktool('d8'), '--min-api', '24', '--lib', PLATFORM, '--output', dex, *sorted(classes.rglob('*.class'))])
    resources = BUILD / 'resources.zip'
    resources.unlink(missing_ok=True)
    run([sdktool('aapt2'), 'compile', '--dir', ROOT / 'res', '-o', resources])
    key = prepare_signing()
    unsigned, aligned = BUILD / 'unsigned.apk', BUILD / 'aligned.apk'
    run([sdktool('aapt2'), 'link', '-o', unsigned, '-I', PLATFORM, '--manifest', ROOT / 'AndroidManifest.xml', '-R', resources])
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as package:
        package.write(dex / 'classes.dex', 'classes.dex')
        package.write(ROOT / 'assets/wallpapers/coast.png', 'assets/wallpapers/coast.png')
    run([sdktool('zipalign'), '-f', '4', unsigned, aligned])
    DIST.mkdir(exist_ok=True)
    output = DIST / ('LivingRoom-' + identity['version'] + '.apk')
    run([sdktool('apksigner'), 'sign', '--ks', key, '--ks-key-alias', 'living-room', '--ks-pass', 'env:LIVINGROOM_STOREPASS', '--out', output, aligned])
    signature = run([sdktool('apksigner'), 'verify', '--verbose', '--print-certs', output])
    if re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', signature) != signing_identity():
        raise RuntimeError('Packaged signing identity mismatch')
    run([sdktool('zipalign'), '-c', '4', output])
    badging = run([sdktool('aapt2'), 'dump', 'badging', output])
    tree = run([sdktool('aapt2'), 'dump', 'xmltree', output, '--file', 'AndroidManifest.xml'])
    if tree.count('(Raw: "android.intent.category.HOME")') != 1 or f"leanback-launchable-activity: name='{identity['package']}.HomeActivity'" not in badging:
        raise RuntimeError('Invalid HOME/TV entry in packaged APK')
    if set(re.findall(r"uses-permission(?:-sdk-23)?: name='([^']+)'", badging)) != PERMISSIONS:
        raise RuntimeError('Unexpected packaged permissions')
    with zipfile.ZipFile(output) as final:
        if final.read('classes.dex') != (dex / 'classes.dex').read_bytes():
            raise RuntimeError('Packaged DEX mismatch')
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.apk.sha256').write_text(digest + '  ' + output.name + '\n')
    report = dict(identity, apk=str(output), bytes=output.stat().st_size, sha256=digest, signature='verified', home_declaration=True, real_device_tested=False)
    (DIST / 'build-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False))

if __name__ == '__main__':
    main()
