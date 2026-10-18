package io.github.elliclee.livingroom;
import android.app.*;import android.content.*;import android.graphics.*;import android.os.*;import android.view.*;import android.widget.*;import java.io.*;import java.util.*;import org.json.*;
public final class GalleryTestRunner extends Instrumentation {
 private Context app;private final JSONArray passed=new JSONArray();
 interface Attempt{void run()throws Exception;}
 void check(boolean value,String label){if(!value)throw new AssertionError(label);}
 void reject(Attempt attempt,String label)throws Exception{try{attempt.run();throw new AssertionError("Accepted "+label);}catch(IOException expected){}}
 String current(String kind)throws Exception{File path=new File(app.getFilesDir(),"backgrounds/"+kind+".json");try(InputStream in=new FileInputStream(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new JSONObject(out.toString("UTF-8")).getString("artwork_id");}}
 JSONObject catalog(String kind)throws Exception{try(InputStream in=app.getAssets().open(kind+"/catalog.json");ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new JSONObject(out.toString("UTF-8"));}}
 public void onCreate(Bundle args){super.onCreate(args);start();}
 public void onStart(){Bundle result=new Bundle();try{
  app=getTargetContext();check(Build.PRODUCT.contains("sdk"),"Emulator only");
  for(String kind:new String[]{"art","nasa"}){
   List<GalleryCollection.Work> works=GalleryCollection.read(app,kind);check(works.size()==(kind.equals("art")?12:5),"Catalog count");
   for(GalleryCollection.Work work:works)if(!work.asset.isEmpty()){try(InputStream input=app.getAssets().open(work.asset)){Bitmap image=BitmapFactory.decodeStream(input);check(image!=null&&image.getWidth()<=2560&&image.getHeight()<=1440,"Real bundled image decode and dimensions");image.recycle();}}
   String first=works.get(0).id,next=GalleryCollection.next(works,first,false,1).id;
   check(!first.equals(next),"Next differs");check(GalleryCollection.next(works,next,false,-1).id.equals(first),"Previous restores identity");
   check(GalleryCollection.next(works,first,true,-1).asset.length()>0,"Offline reverse wraps");
  }
  passed.put("17 catalog entries, 10 actual JPEG decodes, next/previous and offline wrap");
  List<GalleryCollection.Work> photos=GalleryCollection.read(app,"photography");
  for(GalleryCollection.Work photo:photos){
   check(photo.source.equals("personal")&&photo.license.equals("USER-PROVIDED")&&photo.detail().isEmpty(),"Personal provenance has no invented author/date");
   try(InputStream input=app.getAssets().open(photo.asset)){Bitmap image=BitmapFactory.decodeStream(input);check(image!=null&&image.getWidth()<=2560&&image.getHeight()<=1440,"Actual photography decode");image.recycle();}
  }
  check(GalleryCollection.parse(new JSONObject().put("format_version",1).put("works",new JSONArray()),"photography").isEmpty(),"Empty optional photo collection");
  if(!photos.isEmpty()){
   String photoId=photos.get(0).id,photoNext=GalleryCollection.next(photos,photoId,true,1).id;
   check(GalleryCollection.next(photos,photoNext,true,-1).id.equals(photoId),"Personal gallery reverse restores photo");
   JSONObject badPhoto=catalog("photography");badPhoto.getJSONArray("works").getJSONObject(0).put("asset","../private.jpg");reject(()->GalleryCollection.parse(badPhoto,"photography"),"personal asset traversal");
  }
  passed.put("Optional photography: "+photos.size()+" real decodes, personal metadata, empty collection and reverse/path constraints");
  JSONObject invalid=catalog("art");invalid.getJSONArray("works").getJSONObject(0).put("is_public_domain",false);reject(()->GalleryCollection.parse(invalid,"art"),"non-public-domain art");
  JSONObject unsafe=catalog("nasa");unsafe.getJSONArray("works").getJSONObject(0).put("image_url","https://example.com/fake.jpg");reject(()->GalleryCollection.parse(unsafe,"nasa"),"foreign NASA host");
  JSONObject traversal=catalog("art");traversal.getJSONArray("works").getJSONObject(0).put("asset","../private.jpg");reject(()->GalleryCollection.parse(traversal,"art"),"asset traversal");
  reject(()->GalleryCollection.parse(catalog("art"),"nasa"),"mixed provider identity");passed.put("rights flags, provider identity, image origin and asset path enforced");
  BackgroundRepository.prefs(app).edit().remove("sources").commit();
  File dir=new File(app.getFilesDir(),"backgrounds");for(String kind:new String[]{"art","nasa"})for(String suffix:new String[]{".json",".img"})new File(dir,kind+suffix).delete();
  BackgroundRepository.prefs(app).edit().putString("source","art").commit();
  BackgroundRepository.Scene first=BackgroundRepository.load(app,true);String id=current("art");check(first.source.equals("art")&&first.credit.contains("CC0"),"Art metadata");
  GalleryCollection.Work artwork=GalleryCollection.read(app,"art").get(0);check(first.captionTitle.equals(artwork.title)&&first.captionDetail.equals(artwork.artist+" · "+artwork.date)&&first.captionSource.equals(artwork.attribution()),"Structured art caption");
  check(first.status("changed").captionTitle.equals(first.captionTitle),"Status preserves caption");first.bitmap.recycle();
  BackgroundRepository.Scene cached=BackgroundRepository.load(app,false);check(current("art").equals(id)&&cached.captionTitle.equals(artwork.title),"Fresh art cache and caption stable");cached.bitmap.recycle();
  File metaPath=new File(dir,"art.json");JSONObject legacy;try(InputStream input=new FileInputStream(metaPath);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1)out.write(b,0,n);legacy=new JSONObject(out.toString("UTF-8"));}
  for(String key:new String[]{"caption_title","caption_detail","caption_source"})legacy.remove(key);legacy.put("credit","Legacy formatted credit");
  try(OutputStream output=new FileOutputStream(metaPath)){output.write(legacy.toString().getBytes("UTF-8"));}
  BackgroundRepository.Scene upgraded=BackgroundRepository.load(app,false);check(current("art").equals(id)&&upgraded.captionTitle.equals(artwork.title)&&upgraded.captionSource.equals(artwork.attribution()),"Legacy cache gets catalog caption without rotation");upgraded.bitmap.recycle();
  BackgroundRepository.Scene next=BackgroundRepository.load(app,true,1);check(!current("art").equals(id),"Forced next");next.bitmap.recycle();
  BackgroundRepository.Scene prev=BackgroundRepository.load(app,true,-1);check(current("art").equals(id),"Actual previous");prev.bitmap.recycle();
  BackgroundRepository.prefs(app).edit().putString("source","nasa").commit();BackgroundRepository.Scene nasa=BackgroundRepository.load(app,true);check(nasa.source.equals("nasa")&&!nasa.credit.contains("CC0")&&nasa.captionTitle.length()>0&&nasa.captionDetail.contains("NASA")&&nasa.captionSource.equals("NASA 图片与媒体使用规范"),"NASA structured provenance distinct");nasa.bitmap.recycle();
  passed.put("real loading, structured captions, legacy cache upgrade, stable cache, next/previous and NASA credit");
  if(!photos.isEmpty()){
   BackgroundRepository.prefs(app).edit().putString("source","photography").commit();BackgroundRepository.Scene photo=BackgroundRepository.load(app,true);
   check(photo.source.equals("photography")&&photo.captionSource.equals("摄影集")&&!photo.captionTitle.isEmpty()&&photo.captionDetail.isEmpty(),"Photography actual load and caption");photo.bitmap.recycle();
  }
  BackgroundRepository.prefs(app).edit().putString("source","art").commit();app.getSharedPreferences("home-layout",0).edit().putBoolean("home_role_prompted",true).commit();
  result.putString("passed",passed.toString());finish(Activity.RESULT_OK,result);
 }catch(Throwable failure){result.putString("failure",failure.toString());finish(Activity.RESULT_CANCELED,result);}}
}
