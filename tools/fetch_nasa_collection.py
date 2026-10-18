#!/usr/bin/env python3
"""核对 NASA 官方图库的固定航天摄影，保留出处与署名，不把全部 NASA 托管内容当作 CC0。"""
from pathlib import Path
import json,hashlib,datetime,urllib.parse,concurrent.futures
from fetch_art_collection import request
ROOT=Path(__file__).resolve().parents[1]
POLICY='https://www.nasa.gov/nasa-brand-center/images-and-media/'
WORKS=[('art002e021278','月球背后的地球',True),('iss045e013851','空间站眼中的地球之夜',True),
 ('S39-23-020','南极光的蜿蜒光环',False),('S39-23-036','南极光的红色天幕',False),('S39-25-006','地球边缘的绿色气辉',False)]

def refresh():
 folder=ROOT/'assets/nasa';folder.mkdir(parents=True,exist_ok=True)
 def fetch(spec):
  work_id,title,bundled=spec
  api='https://images-api.nasa.gov/search?'+urllib.parse.urlencode({'nasa_id':work_id,'media_type':'image','page_size':5})
  items=json.loads(request(api,262144))['collection']['items']
  item=next(i for i in items if i['data'][0]['nasa_id']==work_id);row=item['data'][0]
  # Curated NASA/JSC mission photography: no identifiable people, logos, or third-party credit.
  if row.get('center')!='JSC' or row.get('secondary_creator') not in [None,'NASA'] or row.get('photographer') not in [None,'NASA']:
   raise ValueError('NASA item rights need a new manual review: '+work_id)
  links=[link for link in item.get('links',[]) if link.get('render')=='image' and link.get('href','').startswith('https://images-assets.nasa.gov/image/'+work_id+'/')
   and link['href'].endswith('.jpg') and 1000<=link.get('width',0)<=2560 and 0<link.get('height',0)<=1440 and link.get('size',0)<=8*1024*1024]
  if not links:raise ValueError('No bounded landscape image for '+work_id)
  image=max(links,key=lambda link:link['width'])['href']
  value={'id':work_id,'title':row['title'],'display_title':title,'artist':'NASA / Johnson Space Center','display_artist':'NASA / JSC',
   'date':row.get('date_created','')[:10],'image_url':image,'source':'nasa','source_url':'https://images.nasa.gov/details/'+work_id,
   'api_url':api,'license':'NASA-MEDIA-GUIDELINES','license_url':POLICY,'rights_reviewed':True,'credit':'NASA / JSC'}
  if bundled:
   data=request(image,8*1024*1024)
   if not data.startswith(b'\xff\xd8'):raise ValueError('Expected NASA JPEG')
   target=folder/(work_id+'.jpg');target.write_bytes(data);value.update(asset='nasa/'+target.name,bytes=len(data),sha256=hashlib.sha256(data).hexdigest())
  return value
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:works=list(pool.map(fetch,WORKS))
 catalog={'format_version':1,'checked_at':datetime.datetime.now(datetime.timezone.utc).date().isoformat(),'museum':'NASA Image and Video Library',
  'license_url':POLICY,'documentation_url':'https://images.nasa.gov/docs/images.nasa.gov_api_docs.pdf','works':works}
 (folder/'catalog.json').write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n')
 print(f'Verified {len(works)} NASA mission photographs; bundled {sum("asset" in w for w in works)} JPEGs')

if __name__=='__main__':refresh()
