#!/usr/bin/env python3
"""在明确指定的模拟器上验证真实图像解码/目录约束/背景缓存；不会连接实际电视。"""
from pathlib import Path
import argparse,subprocess,sys,zipfile,json,os
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT));import build

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--serial',required=True);args=parser.parse_args()
 if not args.serial.startswith('emulator-'):raise ValueError('Only explicitly selected emulators are allowed')
 adb=build.SDK/'platform-tools'/('adb.exe' if sys.platform=='win32' else 'adb')
 def device(*values):
  result=subprocess.run([str(adb),'-s',args.serial,*map(str,values)],capture_output=True,text=True,timeout=60)
  if result.returncode:raise RuntimeError(result.stdout+result.stderr)
  return result.stdout
 if 'sdk' not in device('shell','getprop','ro.product.name'):raise ValueError('Device is not an emulator')
 identity=build.project_identity();work=build.BUILD/'android-gallery';work.mkdir(exist_ok=True);classes=work/'classes';dex=work/'dex';classes.mkdir(exist_ok=True);dex.mkdir(exist_ok=True)
 build.run([build.javatool('javac'),'--release','8','-encoding','UTF-8','-cp',str(build.PLATFORM)+os.pathsep+str(build.BUILD/'classes'),'-d',classes,ROOT/'tests/android/GalleryTestRunner.java'])
 build.run([build.sdktool('d8'),'--min-api','24','--lib',build.PLATFORM,'--classpath',build.BUILD/'classes','--output',dex,*classes.rglob('*.class')])
 manifest=f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.elliclee.gallerytests" android:versionCode="1"><uses-sdk android:minSdkVersion="24" android:targetSdkVersion="35"/><application android:testOnly="true"/><instrumentation android:name="io.github.elliclee.livingroom.GalleryTestRunner" android:targetPackage="{identity['package']}"/></manifest>'''
 (work/'AndroidManifest.xml').write_text(manifest);unsigned=work/'unsigned.apk'
 build.run([build.sdktool('aapt2'),'link','-o',unsigned,'-I',build.PLATFORM,'--manifest',work/'AndroidManifest.xml'])
 with zipfile.ZipFile(unsigned,'a') as out:out.write(dex/'classes.dex','classes.dex')
 aligned=work/'aligned.apk';test=work/'tests.apk';build.run([build.sdktool('zipalign'),'-f','4',unsigned,aligned]);build.ENV['LIVINGROOM_STOREPASS']=(build.BUILD/'signing-password').read_text()
 build.run([build.sdktool('apksigner'),'sign','--ks',build.BUILD/'signing.p12','--ks-key-alias','living-room','--ks-pass','env:LIVINGROOM_STOREPASS','--out',test,aligned])
 apk=Path(json.loads((build.DIST/'build-report.json').read_text())['apk'])
 device('install','--no-incremental','-r',apk);device('install','--no-incremental','-r','-t',test)
 result=device('shell','am','instrument','-w','io.github.elliclee.gallerytests/io.github.elliclee.livingroom.GalleryTestRunner')
 (work/'result.txt').write_text(result);print(result)
 if 'INSTRUMENTATION_CODE: -1' not in result or 'INSTRUMENTATION_RESULT: failure=' in result:raise RuntimeError('Gallery instrumentation failed')

if __name__=='__main__':main()
