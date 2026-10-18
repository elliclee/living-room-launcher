package io.github.elliclee.livingroom;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

/** Owns bounded background downloads and the last valid image of each selected source. */
final class BackgroundRepository {
    private static final int IMAGE_LIMIT = 8 * 1024 * 1024;
    private static final int JSON_LIMIT = 64 * 1024;
    // Fetched by the TV itself (no computer involved). Bing's mainland endpoint first, the global one second.
    private static final String[] DAILY = {
        "https://cn.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN",
        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN"};
    static final String[] MODES = {"recent", "daily", "art", "nasa", "photography", "coast", "custom"};
    static final String[] LABELS = {"最近观看优先", "每日风景", "西方艺术 · 画廊", "NASA · 太空", "摄影集", "原创海岸 · 离线", "自定义图片或接口"};
    private static volatile String lastStatus = "尚未更新";
    private static volatile String lastError = "";
    private static volatile String lastSource = "none";
    private static volatile long lastAt;

    static final class Scene {
        final Bitmap bitmap;
        final String source, title, subtitle, credit, packageName, status;
        final String captionTitle,captionDetail,captionSource;
        Scene(Bitmap bitmap, String source, String title, String subtitle, String credit, String packageName, String status) {
            this(bitmap,source,title,subtitle,credit,packageName,status,"","",credit);
        }
        Scene(Bitmap bitmap,String source,String title,String subtitle,String credit,String packageName,String status,
              String captionTitle,String captionDetail,String captionSource) {
            this.bitmap=bitmap; this.source=source; this.title=title; this.subtitle=subtitle;
            this.captionTitle=captionTitle;this.captionDetail=captionDetail;this.captionSource=captionSource;
            this.credit=credit; this.packageName=packageName; this.status=status;
        }
        Scene status(String value) { return new Scene(bitmap,source,title,subtitle,credit,packageName,value,captionTitle,captionDetail,captionSource); }
    }

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("home-background",Context.MODE_PRIVATE); }
    /** Canonical order is owned here; old single-source preferences are read without losing their choice. */
    static java.util.List<String> selected(Context context) {
        SharedPreferences preferences=prefs(context);
        java.util.Set<String> stored=preferences.getStringSet("sources",null);
        java.util.List<String> values=new java.util.ArrayList<>();
        if(stored==null){
            String legacy=preferences.getString("source","recent");
            for(String source:MODES)if(source.equals(legacy))values.add(source);
        }else for(String source:MODES)if(stored.contains(source))values.add(source);
        if(values.isEmpty())values.add("recent");
        return java.util.Collections.unmodifiableList(values);
    }
    static String mode(Context context) {
        java.util.List<String> values=selected(context);String current=prefs(context).getString("source",values.get(0));
        return values.contains(current)?current:values.get(0);
    }
    private static String sourceLabel(String source){
        for(int i=0;i<MODES.length;i++)if(MODES[i].equals(source))return LABELS[i];
        throw new IllegalArgumentException("Unknown background source");
    }
    static String label(Context context) {
        java.util.List<String> values=selected(context);
        return values.size()>1?values.size()+" 个来源":sourceLabel(values.get(0));
    }
    static long selectionRevision(Context context){return prefs(context).getLong("selection_revision",0);}
    private static void saveSelection(Context context,java.util.List<String> values,String current,String customUrl){
        SharedPreferences.Editor edit=prefs(context).edit().putStringSet("sources",new java.util.HashSet<>(values))
            .putString("source",current).putLong("selection_revision",selectionRevision(context)+1);
        if(customUrl!=null)edit.putString("custom_url",customUrl);edit.apply();
    }
    /** Returns false only when removing the last selected source. */
    static boolean toggle(Context context,String source){
        sourceLabel(source);java.util.List<String> values=new java.util.ArrayList<>(selected(context));String current=mode(context);
        if(values.contains(source)){if(values.size()==1)return false;values.remove(source);}
        else values.add(source);
        if(!values.contains(current))current=values.get(0);
        saveSelection(context,values,current,null);return true;
    }
    static void configureCustom(Context context,String url) throws IOException {
        webUrl(url);java.util.List<String> values=new java.util.ArrayList<>(selected(context));
        if(!values.contains("custom"))values.add("custom");saveSelection(context,values,"custom",url);
    }
    /** Advance the active provider without changing the user's selected set. Main-thread caller owns request ordering. */
    static void advance(Context context){
        java.util.List<String> values=selected(context);int index=values.indexOf(mode(context));
        prefs(context).edit().putString("source",values.get((index+1)%values.size())).apply();
    }
    static boolean activateGallery(Context context){
        if(GalleryCollection.isGallery(mode(context)))return true;
        for(String source:selected(context))if(GalleryCollection.isGallery(source)){
            prefs(context).edit().putString("source",source).apply();return true;
        }
        return false;
    }
    static boolean rotates(Context context){return selected(context).size()>1||GalleryCollection.isGallery(mode(context));}
    static String status() { return lastStatus; }
    static Bitmap offline(Context context) throws IOException {
        try(InputStream input=context.getAssets().open("wallpapers/coast.png")) {
            Bitmap bitmap=BitmapFactory.decodeStream(input);
            if(bitmap==null) throw new IOException("Invalid bundled wallpaper");
            return bitmap;
        }
    }

    /** Worker only. A new image replaces the display only after successful decoding. */
    static Scene load(Context context, boolean force) {return load(context,force,1);}
    static Scene load(Context context, boolean force,int direction) {
        java.util.List<String> choices=selected(context);String current=prefs(context).getString("source",choices.get(0)),note="";
        int start=choices.indexOf(current);if(start<0)start=0;
        lastError="";
        for(int offset=0;offset<choices.size();offset++){
            String source=choices.get((start+offset)%choices.size());
            try{
                Scene value=loadSource(context,source,force,direction);
                return finished(note.isEmpty()?value:value.status(note+" · 当前显示"+sourceLabel(value.source)));
            }catch(Exception failure){
                lastError=failure.getClass().getSimpleName();String why=sourceLabel(source)+"不可用（"+reason(failure)+"）";
                if(note.isEmpty())note=why;
                try{
                    Scene cached=cache(context,source,"custom".equals(source)?prefs(context).getString("custom_url","").trim():null);
                    if(cached!=null)return finished(cached.status(why+" · 保留上次背景"));
                }catch(Exception unavailable){/* Continue only to another user-selected provider. */}
            }
        }
        try{return finished(new Scene(offline(context),"coast","","","原创生成的海岸风景","",note+" · 当前显示离线海岸"));}
        catch(IOException unavailable){throw new IllegalStateException("Bundled wallpaper unavailable",unavailable);}
    }
    private static final class SourceUnavailable extends IOException {
        SourceUnavailable(String message){super(message);}
    }
    private static Scene loadSource(Context context,String mode,boolean force,int direction) throws Exception {
        if("coast".equals(mode))return new Scene(offline(context),"coast","","","原创生成的海岸风景","","正在显示离线背景");
        if(GalleryCollection.isGallery(mode))return gallery(context,mode,force,direction);
        if("recent".equals(mode)){
            RecentViewing.Snapshot history=RecentViewing.read(context);
            if(history.item==null)throw new SourceUnavailable("empty".equals(history.status)?"暂无观看记录":"denied".equals(history.status)?"电视未允许读取观看记录":"unsupported".equals(history.status)?"本机未提供已确认的观看记录入口":"观看记录暂不可用");
            RecentViewing.Item item=history.item;
            JSONObject meta=new JSONObject().put("identity",item.imageUri).put("title",item.title).put("subtitle",item.episode)
                .put("credit",item.sourceLabel).put("package",item.packageName);
            return fetchImage(context,"recent",item.imageUri,meta,force).status("已读取 TCL 最近观看");
        }
        if("custom".equals(mode)){
            String endpoint=prefs(context).getString("custom_url","").trim();webUrl(endpoint);
            Scene cached=cache(context,"custom",endpoint);
            if(!force&&cached!=null&&fresh(context,"custom",60*60*1000L))return cached.status("自定义背景 · 已缓存");
            byte[] payload=download(endpoint,IMAGE_LIMIT);int first=0;
            while(first<payload.length&&Character.isWhitespace((char)payload[first]))first++;
            JSONObject meta=new JSONObject().put("identity",endpoint);byte[] image=payload;
            if(first<payload.length&&payload[first]=='{'){
                if(payload.length>JSON_LIMIT)throw new IOException("Content metadata too large");
                JSONObject content=new JSONObject(new String(payload,StandardCharsets.UTF_8));String imageUrl=content.getString("image_url");webUrl(imageUrl);
                image=download(imageUrl,IMAGE_LIMIT);
                for(String key:new String[]{"title","subtitle","credit"})meta.put(key,limited(content.optString(key,"")));
                meta.put("package",limited(content.optString("package_name","")));
            }
            return store(context,"custom",image,meta).status("自定义背景 · 已更新");
        }
        if("daily".equals(mode))return daily(context,force);
        throw new IllegalArgumentException("Unknown background source");
    }

    private static Scene gallery(Context context,String kind,boolean force,int direction) throws Exception {
        java.util.List<GalleryCollection.Work> works=GalleryCollection.read(context,kind);
        if(works.isEmpty())throw new SourceUnavailable("摄影集尚未添加照片");
        JSONObject previous=metadata(context,kind);
        Scene cached=cache(context,kind,null);
        if(!force&&cached!=null&&fresh(context,kind,GalleryCollection.ROTATE_MS)) {
            // Resolve old and new cache labels from the catalog without parsing display credit or downloading again.
            for(GalleryCollection.Work item:works)if(item.id.equals(previous.optString("artwork_id")))
                return scene(cached.bitmap,kind,item.metadata()).status(sourceLabel(kind)+" · 《"+item.title+"》");
        }
        String before=previous.optString("artwork_id","");
        GalleryCollection.Work work=GalleryCollection.next(works,before,false,direction);
        byte[] bytes;boolean bundled=!work.asset.isEmpty();
        if(!bundled){
            try{return store(context,kind,download(work.image,IMAGE_LIMIT),work.metadata())
                .status(sourceLabel(kind)+" · 《"+work.title+"》");}
            catch(IOException unavailable){lastError=unavailable.getClass().getSimpleName();work=GalleryCollection.next(works,before,true,direction);bundled=true;}
        }
        try(InputStream input=context.getAssets().open(work.asset)){bytes=read(input,IMAGE_LIMIT);}
        if(bytes.length!=work.bytes||!sha(bytes).equals(work.digest))throw new IOException("Bundled art integrity mismatch");
        return store(context,kind,bytes,work.metadata()).status(sourceLabel(kind)+" · 《"+work.title+"》 · 内置图片");
    }

    private static Scene daily(Context context, boolean force) throws Exception {
        String today=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date());
        Scene cached=cache(context,"daily",null);
        JSONObject previous=metadata(context,"daily");
        if(!force && cached!=null && today.equals(previous.optString("checked_day"))) return cached.status("每日风景 · 今日已更新");
        Exception last=null;
        for(String endpoint:DAILY) {
            try {
                JSONObject item=new JSONObject(new String(download(endpoint,JSON_LIMIT),StandardCharsets.UTF_8)).getJSONArray("images").getJSONObject(0);
                URL image=new URL(new URL(endpoint),item.getString("url"));
                if(!bingHost(image.getHost())) throw new IOException("Unexpected daily image host");
                JSONObject meta=new JSONObject().put("identity",image.toString()).put("checked_day",today)
                    .put("credit",limited(item.optString("copyright","Bing 每日图片"))).put("title","").put("subtitle","");
                return dailyStored(context,today,fetchImage(context,"daily",image.toString(),meta,force));
            } catch(Exception failure) { last=failure; }
        }
        throw last;
    }

    /** Bing serves the daily image from bing.com and its mainland CDN hosts. */
    private static boolean bingHost(String host) {
        String h=host==null?"":host.toLowerCase(Locale.ROOT);
        return h.equals("bing.com")||h.endsWith(".bing.com")||h.equals("bing.net")||h.endsWith(".bing.net");
    }

    private static Scene dailyStored(Context context,String today,Scene scene) throws Exception {
        // The same daily image may span two local days; retain the successful feed check.
        JSONObject stored=metadata(context,"daily");
        if(!today.equals(stored.optString("checked_day"))) writeMeta(context,"daily",stored.put("checked_day",today).put("saved_at",System.currentTimeMillis()));
        return scene.status("每日风景 · 已更新");
    }

    private static Scene fetchImage(Context context,String kind,String uri,JSONObject meta,boolean force) throws Exception {
        Scene cached=cache(context,kind,uri);
        if(cached!=null && !force) return scene(cached.bitmap,kind,meta);
        byte[] bytes;
        if("content".equals(Uri.parse(uri).getScheme())) {
            try(InputStream input=context.getContentResolver().openInputStream(Uri.parse(uri))) {
                if(input==null) throw new IOException("Artwork stream unavailable");
                bytes=read(input,IMAGE_LIMIT);
            }
        } else bytes=download(uri,IMAGE_LIMIT);
        return store(context,kind,bytes,meta);
    }
    private static Scene store(Context context,String kind,byte[] bytes,JSONObject meta) throws Exception {
        Bitmap decoded=decode(bytes);
        meta.put("sha256",sha(bytes)).put("saved_at",System.currentTimeMillis());
        write(new File(directory(context),kind+".img"),bytes);
        writeMeta(context,kind,meta);
        return scene(decoded,kind,meta);
    }
    private static Scene cache(Context context,String kind,String identity) throws Exception {
        JSONObject meta=metadata(context,kind);
        if(meta.length()==0 || identity!=null && !identity.equals(meta.optString("identity"))) return null;
        File file=new File(directory(context),kind+".img");if(!file.isFile()) return null;
        byte[] bytes;
        try(InputStream in=new AtomicFile(file).openRead()){bytes=read(in,IMAGE_LIMIT);}
        catch(IOException unavailable){return null;}
        if(!sha(bytes).equals(meta.optString("sha256"))) return null;
        try{return scene(decode(bytes),kind,meta);}
        catch(IOException invalid){return null;}
    }
    private static Scene scene(Bitmap image,String kind,JSONObject meta) {
        return new Scene(image,kind,meta.optString("title"),meta.optString("subtitle"),meta.optString("credit"),meta.optString("package"),"",
            meta.optString("caption_title"),meta.optString("caption_detail"),meta.optString("caption_source",meta.optString("credit")));
    }
    private static boolean fresh(Context context,String kind,long age) throws Exception {
        long elapsed=System.currentTimeMillis()-metadata(context,kind).optLong("saved_at",0);return elapsed>=0 && elapsed<age;
    }
    private static JSONObject metadata(Context context,String kind) throws Exception {
        File file=new File(directory(context),kind+".json");if(!file.isFile()) return new JSONObject();
        try(InputStream in=new AtomicFile(file).openRead()){return new JSONObject(new String(read(in,JSON_LIMIT),StandardCharsets.UTF_8));}
        catch(IOException | org.json.JSONException invalid){return new JSONObject();}
    }
    private static void writeMeta(Context context,String kind,JSONObject value) throws Exception {
        write(new File(directory(context),kind+".json"),value.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static File directory(Context context) throws IOException {
        File dir=new File(context.getFilesDir(),"backgrounds");if(!dir.isDirectory()&&!dir.mkdirs()) throw new IOException("Background cache unavailable");return dir;
    }
    private static void write(File path,byte[] bytes) throws IOException {
        AtomicFile file=new AtomicFile(path);FileOutputStream out=null;
        try{out=file.startWrite();out.write(bytes);file.finishWrite(out);}catch(IOException failure){if(out!=null)file.failWrite(out);throw failure;}
    }
    private static Bitmap decode(byte[] bytes) throws IOException {
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>16384||bounds.outHeight>16384||
                (long)bounds.outWidth*bounds.outHeight>64L*1024*1024) throw new IOException("Unsupported background dimensions");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
        while(bounds.outWidth/options.inSampleSize>2560||bounds.outHeight/options.inSampleSize>1440)options.inSampleSize*=2;
        options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap value=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(value==null) throw new IOException("Background cannot be decoded");return value;
    }
    private static URL webUrl(String value) throws IOException {
        URL url=new URL(value);
        if(!("https".equalsIgnoreCase(url.getProtocol())||"http".equalsIgnoreCase(url.getProtocol()))||
            url.getHost().isEmpty()||url.getUserInfo()!=null) throw new IOException("Expected HTTP image or content endpoint");
        return url;
    }
    private static byte[] download(String value,int budget) throws IOException {
        URL url=webUrl(value);
        for(int redirects=0;redirects<=4;redirects++) {
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(8000);connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept","image/*, application/json");
            try {
                int status=connection.getResponseCode();
                if(status>=300&&status<400) {
                    String location=connection.getHeaderField("Location");if(location==null) throw new IOException("Missing redirect location");
                    url=webUrl(new URL(url,location).toString());continue;
                }
                if(status!=200) throw new IOException("Background request failed: HTTP "+status);
                if(connection.getContentLengthLong()>budget) throw new IOException("Background response exceeds budget");
                try(InputStream input=connection.getInputStream()){return read(input,budget);}
            } finally {connection.disconnect();}
        }
        throw new IOException("Too many redirects");
    }
    private static byte[] read(InputStream input,int budget) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int count;
        while((count=input.read(buffer))!=-1){if(out.size()+count>budget)throw new IOException("Background response exceeds budget");out.write(buffer,0,count);}return out.toByteArray();
    }
    private static String sha(byte[] data) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder result=new StringBuilder();
        for(byte value:digest)result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();
    }
    /** Human reason for a failed fetch; only the failure kind, never URLs or content. */
    static String reason(Throwable failure) {
        if(failure instanceof SourceUnavailable)return failure.getMessage();
        if(failure==null) return "未知原因";
        String message=failure.getMessage()==null?"":failure.getMessage();
        if(failure instanceof java.net.SocketTimeoutException) return "连接超时";
        if(failure instanceof java.net.UnknownHostException) return "无法解析服务器地址，请检查电视网络";
        if(failure instanceof java.net.ConnectException||failure instanceof java.net.NoRouteToHostException) return "无法连接服务器";
        if(failure instanceof javax.net.ssl.SSLException) return "安全连接失败，请检查电视的日期和时间";
        if(message.startsWith("Background request failed: HTTP ")) return "服务器返回 "+message.substring(27);
        if(message.contains("exceeds budget")) return "图片超过 8 MB";
        if(message.contains("dimensions")||message.contains("decoded")) return "图片无法解码";
        if(failure instanceof org.json.JSONException) return "接口数据格式变化";
        if(message.contains("Unexpected daily image host")) return "图片地址不在允许范围";
        return failure.getClass().getSimpleName();
    }
    private static String limited(String value){return value.length()>512?value.substring(0,512):value;}
    private static Scene finished(Scene result){lastStatus=result.status;lastSource=result.source;lastAt=System.currentTimeMillis();return result;}
    static JSONObject diagnostics(Context context) throws Exception {
        return new JSONObject().put("selected_source",mode(context)).put("selected_sources",new org.json.JSONArray(selected(context))).put("displayed_source",lastSource).put("status",lastStatus).put("error_class",lastError)
            .put("last_completed_at_epoch_ms",lastAt).put("history",RecentViewing.diagnostics(context))
            .put("system_wallpaper_read_contract","not_confirmed");
    }
}
