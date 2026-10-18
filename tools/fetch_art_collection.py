#!/usr/bin/env python3
"""从 AIC 官方 API 核对固定作品、获取开放图像；只在维护素材时运行。"""
from pathlib import Path
import concurrent.futures
import datetime
import hashlib
import json
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
WORKS=[
 (64818,'夏末的麦垛','莫奈',True),
 (14598,'圣阿德雷斯的海滩','莫奈',True),
 (14586,'诗人的花园','梵高',True),
 (81557,'海景','雷诺阿',True),
 (16487,'从埃斯塔克眺望马赛湾','塞尚',True),
 (16633,'马尔利港的塞纳河与沙堆','西斯莱',True),
 (110541,'水晶宫','毕沙罗',True),
 (6005,'冬日的马恩河畔','毕沙罗',True),
 (14624,'秋日黄昏的麦垛','莫奈',False),
 (14620,'普尔维尔的悬崖漫步','莫奈',False),
 (28560,'卧室','梵高',False),
 (96559,'道路的转弯','西斯莱',False),
]
LIMIT=8*1024*1024

def request(url,budget):
 req=urllib.request.Request(url,headers={'User-Agent':'LivingRoomLauncher-ArtCatalog/1.0 (open-source curated wallpaper)'})
 with urllib.request.urlopen(req,timeout=25) as response:
  data=response.read(budget+1)
  if len(data)>budget:raise ValueError('Response exceeds resource budget')
  return data

def refresh():
 fields='id,title,artist_title,date_display,is_public_domain,image_id,thumbnail'
 api='https://api.artic.edu/api/v1/artworks?ids='+','.join(str(w[0]) for w in WORKS)+'&fields='+fields+'&limit=20'
 response=json.loads(request(api,65536));rows={r['id']:r for r in response['data']}
 if response['config']['iiif_url']!='https://www.artic.edu/iiif/2':raise ValueError('Unexpected official image service')
 folder=ROOT/'assets/art';folder.mkdir(parents=True,exist_ok=True)
 def prepare(spec):
  work_id,title,artist,bundled=spec;row=rows[work_id]
  if row.get('is_public_domain') is not True or not row.get('image_id'):raise ValueError('Artwork is not verified public domain: '+str(work_id))
  thumb=row.get('thumbnail') or {}
  if not (thumb.get('width',0)>thumb.get('height',0)>0):raise ValueError('Artwork is not landscape: '+str(work_id))
  prefix='https://www.artic.edu/iiif/2/'+row['image_id']+'/full/'
  value={'id':str(work_id),'title':row['title'],'display_title':title,'artist':row['artist_title'],'display_artist':artist,'date':row['date_display'],
   'is_public_domain':True,'license':'CC0-1.0','source':'artic','source_url':'https://www.artic.edu/artworks/'+str(work_id),
   'api_url':'https://api.artic.edu/api/v1/artworks/'+str(work_id),'image_url':prefix+'!2560,1440/0/default.jpg'}
  if bundled:
   data=request(prefix+'!2560,1440/0/default.jpg',LIMIT)
   if not data.startswith(b'\xff\xd8'):raise ValueError('Expected JPEG')
   target=folder/(str(work_id)+'.jpg');target.write_bytes(data)
   value.update(asset='art/'+target.name,sha256=hashlib.sha256(data).hexdigest(),bytes=len(data))
  return value
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:works=list(pool.map(prepare,WORKS))
 catalog={'format_version':1,'checked_at':datetime.datetime.now(datetime.timezone.utc).date().isoformat(),
  'museum':'Art Institute of Chicago','museum_zh':'芝加哥艺术博物馆','license_url':'https://creativecommons.org/publicdomain/zero/1.0/',
  'documentation_url':'https://api.artic.edu/docs/','works':works}
 (folder/'catalog.json').write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n')
 print(f'Verified {len(works)} public-domain paintings; bundled {sum("asset" in w for w in works)} JPEGs')
 print(f'Bundled bytes: {sum(w.get("bytes",0) for w in works)}')

if __name__=='__main__':refresh()
