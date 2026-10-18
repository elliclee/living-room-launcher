#!/usr/bin/env python3
"""把用户提供的本机照片准备为摄影集；需要已有 ffmpeg/ffprobe，生成物被 Git 忽略。"""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
EXTENSIONS={'.jpg','.jpeg','.png','.webp','.bmp','.tif','.tiff'}

def run(command):
    result=subprocess.run(command,capture_output=True,text=True,timeout=90)
    if result.returncode:raise RuntimeError(result.stderr[-1500:])
    return result.stdout

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('folder',type=Path);parser.add_argument('--titles',type=Path,help='相对文件名到显示名的JSON对照');args=parser.parse_args()
    encoder=shutil.which('ffmpeg');probe=shutil.which('ffprobe')
    if not encoder or not probe:raise RuntimeError('需要已有 ffmpeg 和 ffprobe；此脚本不安装工具')
    folder=args.folder.expanduser().resolve();files=sorted(p for p in folder.rglob('*') if p.is_file() and not p.name.startswith('.') and p.suffix.lower() in EXTENSIONS)
    titles=json.loads(args.titles.read_text()) if args.titles else {}
    relative={str(p.relative_to(folder)) for p in files}
    if not isinstance(titles,dict) or not set(titles)<=relative or any(not isinstance(v,str) or not v.strip() or len(v)>256 for v in titles.values()):raise ValueError('无效的照片显示名对照')
    if not 1<=len(files)<=64:raise ValueError('摄影集需要1至64张支持的照片')
    build=ROOT/'.build';build.mkdir(exist_ok=True);destination=ROOT/'assets/photography';destination.parent.mkdir(exist_ok=True)
    if destination.exists():
        old=json.loads((destination/'catalog.json').read_text())
        if old.get('managed_by')!='living-room-photography-import':raise ValueError('目标目录不是本工具管理的摄影集，拒绝覆盖')
    with tempfile.TemporaryDirectory(prefix='photography-import-',dir=build) as temporary:
        stage=Path(temporary)/'photography';stage.mkdir();works=[];identities=set()
        for source in files:
            if not source.stem.strip() or len(source.stem)>256:raise ValueError("照片文件名需要1至256个字符")
            original=hashlib.sha256(source.read_bytes()).hexdigest()
            if original in identities:continue
            identities.add(original)
            info=json.loads(run([probe,'-v','error','-select_streams','v:0','-show_entries','stream=width,height','-of','json',str(source)]))['streams'][0]
            if not 0<info['width']<=16384 or not 0<info['height']<=16384:raise ValueError('照片尺寸超过处理上限')
            output=stage/(original+'.jpg')
            run([encoder,'-hide_banner','-loglevel','error','-threads','1','-i',str(source),'-frames:v','1','-vf','scale=2560:1440:force_original_aspect_ratio=decrease','-q:v','2','-map_metadata','-1','-threads','1',str(output)])
            if hashlib.sha256(source.read_bytes()).hexdigest()!=original:raise ValueError('处理时源照片发生变化，请重试')
            data=output.read_bytes()
            if not 0<len(data)<=8*1024*1024:raise ValueError('准备后的照片超过8MiB')
            works.append({'source':'personal','license':'USER-PROVIDED','id':original,'display_title':titles.get(str(source.relative_to(folder)),source.stem),'display_artist':'','date':'',
                'image_url':'asset://photography/'+original+'.jpg','asset':'photography/'+original+'.jpg','sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)})
        catalog={'format_version':1,'managed_by':'living-room-photography-import','works':works}
        encoded=json.dumps(catalog,ensure_ascii=False,indent=2).encode()
        if len(encoded)>65536:raise ValueError('摄影集目录超过64KiB')
        (stage/'catalog.json').write_bytes(encoded)
        backup=None
        if destination.exists():
            backup=Path(tempfile.mkdtemp(prefix='photography-backup-',dir=build));backup.rmdir();destination.rename(backup)
        try:stage.rename(destination)
        except OSError:
            if backup is not None:backup.rename(destination)
            raise
    report={'count':len(works),'bytes':sum(w['bytes'] for w in works),'assets':str(destination),'originals_changed':False,'public_upload':False}
    (build/'photography-import-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(report,ensure_ascii=False))

if __name__=='__main__':main()
