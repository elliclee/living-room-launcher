package io.github.elliclee.livingroom;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owns the curated AIC/NASA and user-provided photo catalogs and their source-specific usage metadata; never treats an arbitrary image as licensed art. */
final class GalleryCollection {
    static final long ROTATE_MS=30*60*1000L;
    static final class Work {
        final String id,title,artist,date,image,asset,digest,source,license;
        final long bytes;
        Work(JSONObject value) throws Exception {
            source=value.getString("source");license=value.getString("license");
            id=value.getString("id");title=value.getString("display_title");artist=value.getString("display_artist");
            date=value.getString("date");image=value.getString("image_url");asset=value.optString("asset","");
            digest=value.optString("sha256","");bytes=value.optLong("bytes",0);
            boolean art="artic".equals(source)&&id.matches("[0-9]{1,9}")&&value.optBoolean("is_public_domain",false)&&"CC0-1.0".equals(license)
                &&image.matches("https://www\\.artic\\.edu/iiif/2/[0-9a-f-]{36}/full/!2560,1440/0/default\\.jpg");
            boolean nasa="nasa".equals(source)&&id.matches("[A-Za-z0-9_-]{1,180}")&&value.optBoolean("rights_reviewed",false)&&"NASA-MEDIA-GUIDELINES".equals(license)
                &&image.matches("https://images-assets\\.nasa\\.gov/image/"+id+"/"+id+"~(orig|large|medium)\\.jpg");
            boolean personal="personal".equals(source)&&"USER-PROVIDED".equals(license)&&id.matches("[0-9a-f]{64}")
                &&asset.equals("photography/"+id+".jpg")&&image.equals("asset://"+asset);
            String folder=art?"art":nasa?"nasa":"photography";
            if(!(art||nasa||personal)||title.isEmpty()||!personal&&artist.isEmpty()||title.length()>256||artist.length()>256||date.length()>80
                    ||!asset.isEmpty()&&(!asset.equals(folder+"/"+id+".jpg")||!digest.matches("[0-9a-f]{64}")||bytes<=0||bytes>8L*1024*1024))
                throw new IOException("Invalid gallery catalog entry");
        }
        String attribution(){return "artic".equals(source)?"芝加哥艺术博物馆 · CC0":"nasa".equals(source)?"NASA 图片与媒体使用规范":"摄影集";}
        String detail(){return artist.isEmpty()?date:date.isEmpty()?artist:artist+" · "+date;}
        JSONObject metadata() throws Exception {
            String credit="《"+title+"》"+(detail().isEmpty()?"":" · "+detail())+"\n"+attribution();
            return new JSONObject().put("identity",image).put("artwork_id",id).put("title","").put("subtitle","")
                .put("art_title",title).put("credit",credit)
                .put("caption_title",title).put("caption_detail",detail()).put("caption_source",attribution())
                .put("source_url","personal".equals(source)?"":("artic".equals(source)?"https://www.artic.edu/artworks/":"https://images.nasa.gov/details/")+id).put("license",license);
        }
    }
    static List<Work> read(Context context,String kind) throws Exception {
        if(!isGallery(kind))throw new IOException("Unknown gallery source");
        try(InputStream input=context.getAssets().open(kind+"/catalog.json");ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;
            while((count=input.read(buffer))!=-1){if(output.size()+count>65536)throw new IOException("Art catalog exceeds budget");output.write(buffer,0,count);}
            return parse(new JSONObject(output.toString("UTF-8")),kind);
        }catch(java.io.FileNotFoundException missing){
            if("photography".equals(kind))return Collections.emptyList();throw missing;
        }
    }
    static List<Work> parse(JSONObject catalog,String kind) throws Exception {
        if(!isGallery(kind))throw new IOException("Unknown gallery source");
        if(catalog.getInt("format_version")!=1)throw new IOException("Unsupported art catalog");
        JSONArray rows=catalog.getJSONArray("works");
        if(rows.length()<("photography".equals(kind)?0:1)||rows.length()>64)throw new IOException("Art catalog count exceeds budget");
        List<Work> result=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            Work work=new Work(rows.getJSONObject(i));
            if(!("art".equals(kind)?"artic":"nasa".equals(kind)?"nasa":"personal").equals(work.source))throw new IOException("Gallery source mismatch");
            if(!ids.add(work.id))throw new IOException("Duplicate art catalog identity");result.add(work);
        }
        return Collections.unmodifiableList(result);
    }
    static boolean isGallery(String kind){return "art".equals(kind)||"nasa".equals(kind)||"photography".equals(kind);}
    static Work next(List<Work> works,String previous,boolean bundledOnly,int direction) throws IOException {
        int start=direction<0?0:-1;for(int i=0;i<works.size();i++)if(works.get(i).id.equals(previous)){start=i;break;}
        for(int offset=1;offset<=works.size();offset++){
            int step=direction<0?-1:1;
            int initial=Math.max(1,Math.min(64,Math.abs(direction)));
            Work work=works.get(Math.floorMod(start+step*(initial+offset-1),works.size()));if(!bundledOnly||!work.asset.isEmpty())return work;
        }
        throw new IOException("No bundled gallery image available");
    }
    private GalleryCollection(){}
}
