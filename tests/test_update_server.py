import hashlib
import http.client
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import zipfile
import build
import update_server as updates

ROOT = Path(__file__).resolve().parents[1]

class ServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = patch.object(updates, 'STORE', Path(self.tmp.name))
        self.store.start()
        self.server = updates.make_server({'host': '127.0.0.1', 'port': 0, 'token': 'test-only'})
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.store.stop()
        self.tmp.cleanup()

    def request(self, route, authorization='Bearer test-only'):
        conn = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=3)
        headers = {} if authorization is None else {'Authorization': authorization}
        conn.request('GET', route, headers=headers)
        response = conn.getresponse()
        result = response.status, response.read()
        conn.close()
        return result

    def test_auth_and_path_boundaries(self):
        for token in [None, 'Bearer wrong', 'Bearer non-ascii-é']:
            self.assertEqual(self.request('/manifest.json', token)[0], 401)
        for route in ['/', '/../signing.p12', '/packages/../manifest.json', '/manifest.json?token=test', '/packages/'+'0'*64+'.apk']:
            self.assertEqual(self.request(route)[0], 404)

    def test_snapshot_identity_survives_manifest_switch(self):
        first = b'first-snapshot'
        digest = hashlib.sha256(first).hexdigest()
        (updates.STORE / (digest+'.apk')).write_bytes(first)
        updates.atomic_json(updates.STORE / 'manifest.json', {'sha256': digest})
        self.assertEqual(json.loads(self.request('/manifest.json')[1])['sha256'], digest)
        updates.atomic_json(updates.STORE / 'manifest.json', {'sha256': 'b'*64})
        self.assertEqual(self.request('/packages/'+digest+'.apk'), (200, first))

    def test_config_reuse_does_not_rotate_credentials(self):
        with patch.object(updates, 'STATE', Path(self.tmp.name)/'settings.json'):
            updates.initialize('10.0.0.2', 57816)
            first = updates.config()
            updates.initialize('10.0.0.2', 57816)
            self.assertEqual(updates.config(), first)
            with self.assertRaises(ValueError):
                updates.initialize('10.0.0.3', 57816)
            self.assertEqual(updates.config(), first)

    def test_config_rejects_non_lan_or_invalid_port(self):
        with patch.object(updates, 'STATE', Path(self.tmp.name)/'settings.json'):
            for host, port in [('127.0.0.1',57816), ('8.8.8.8',57816), ('10.0.0.2',0), ('10.0.0.2',65536)]:
                with self.assertRaises(ValueError):
                    updates.initialize(host,port)
            self.assertFalse(updates.STATE.exists())


@unittest.skipUnless((build.DIST/'build-report.json').is_file(), 'Build an APK first for real SDK/signature integration tests')
class PublishingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.folder = Path(cls.temp.name)
        cls.apk = Path(json.loads((build.DIST/'build-report.json').read_text())['apk'])
        build.ENV['LIVINGROOM_STOREPASS']=(build.BUILD/'signing-password').read_text()
        build.ENV['LIVINGROOM_TESTPASS']='public-test-fixture-only'
        cls.foreign_key=cls.folder/'foreign.p12'
        build.run([build.javatool('keytool'),'-genkeypair','-keystore',cls.foreign_key,'-storetype','PKCS12','-storepass:env','LIVINGROOM_TESTPASS','-alias','living-room','-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=Untrusted Integration Fixture'])
        cls.foreign=cls.variant('foreign',foreign=True)
        cls.other=cls.variant('other',other=True)
        cls.nohome=cls.variant('nohome',nohome=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    @classmethod
    def variant(cls,name,foreign=False,other=False,nohome=False):
        manifest=(ROOT/'AndroidManifest.xml').read_text()
        if other:manifest=manifest.replace('package="'+updates.PACKAGE+'"','package="io.github.elliclee.otherfixture"')
        if nohome:manifest=manifest.replace('<category android:name="android.intent.category.HOME" />','')
        path=cls.folder/(name+'.xml');path.write_text(manifest)
        unsigned=cls.folder/(name+'-unsigned.apk')
        build.run([build.sdktool('aapt2'),'link','-o',unsigned,'-I',build.PLATFORM,'--manifest',path,'-R',build.BUILD/'resources.zip'])
        with zipfile.ZipFile(unsigned,'a') as out:out.write(build.BUILD/'dex/classes.dex','classes.dex')
        aligned=cls.folder/(name+'-aligned.apk');build.run([build.sdktool('zipalign'),'-f','4',unsigned,aligned])
        target=cls.folder/(name+'.apk')
        build.run([build.sdktool('apksigner'),'sign','--ks',cls.foreign_key if foreign else build.BUILD/'signing.p12','--ks-key-alias','living-room','--ks-pass','env:LIVINGROOM_TESTPASS' if foreign else 'env:LIVINGROOM_STOREPASS','--out',target,aligned])
        return target

    def test_real_identity_and_failed_publication_preserve_manifest(self):
        with tempfile.TemporaryDirectory() as store,patch.object(updates,'STORE',Path(store)):
            good=updates.publish(self.apk,'integration')
            self.assertEqual(good['package'],updates.PACKAGE)
            self.assertEqual(good['version_code'],build.project_identity()['version_code'])
            self.assertEqual(good['sha256'],hashlib.sha256(self.apk.read_bytes()).hexdigest())
            snapshot=updates.STORE/(good['sha256']+'.apk')
            self.assertEqual(updates.inspect_apk(snapshot)['sha256'],good['sha256'])
            for invalid in [self.foreign,self.other,self.nohome]:
                with self.subTest(apk=invalid.name),self.assertRaises(ValueError):
                    updates.publish(invalid,'')
                self.assertEqual(json.loads((updates.STORE/'manifest.json').read_text())['sha256'],good['sha256'])
            self.assertEqual(list(updates.STORE.glob('*.apk')),[snapshot])

if __name__ == '__main__':
    unittest.main()
