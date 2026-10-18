package io.github.elliclee.livingroom;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;

/** Owns the update protocol, private download and user-confirmed installation. */
final class UpdateFlow {
    static final long MAX_APK = 64L * 1024 * 1024;
    static final String ACTION_RESULT = "io.github.elliclee.livingroom.UPDATE_RESULT";
    private static final String NONCE = "update_nonce";
    interface Progress { void set(String message); }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("launcher-updates", Context.MODE_PRIVATE);
    }
    static String host(Context context) { return prefs(context).getString("host", UpdateConfig.HOST); }
    static void setHost(Context context, String host) throws IOException {
        String[] groups = host.split("\\.", -1);
        if (groups.length != 4) throw new IOException("请填写电脑的局域网 IPv4 地址");
        int[] parts = new int[4];
        for (int i=0;i<4;i++) {
            if (!groups[i].matches("0|[1-9][0-9]{0,2}")) throw new IOException("IP 地址格式不正确");
            parts[i] = Integer.parseInt(groups[i]);
            if (parts[i]>255) throw new IOException("IP 地址格式不正确");
        }
        if (!(parts[0]==10 || parts[0]==192&&parts[1]==168 || parts[0]==172&&parts[1]>=16&&parts[1]<=31))
            throw new IOException("请使用 10、172.16–31 或 192.168 开头的局域网地址");
        if (!prefs(context).edit().putString("host", host).commit()) throw new IOException("无法保存电脑地址");
    }
    static long code(PackageInfo info) { return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode; }
    static PackageInfo installed(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), signingFlags());
    }
    static int signingFlags() { return Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES; }
    static String version(Context context) {
        try { return installed(context).versionName; }
        catch (PackageManager.NameNotFoundException failure) { return "未知"; }
    }
    static String status(Context context) {
        SharedPreferences state=prefs(context);
        long target=state.getLong("target_code",0);
        try { if(target>0&&code(installed(context))>=target) return "已安装更新 "+version(context); }
        catch (PackageManager.NameNotFoundException ignored) { }
        return state.getString("message", "");
    }
    static final class Release {
        final String packageName, version, sha256, notes;
        final long versionCode, bytes;
        Release(JSONObject value) throws Exception {
            if(value.getInt("format_version")!=1) throw new IOException("电脑的更新格式不受支持");
            packageName=value.getString("package"); version=value.getString("version");
            versionCode=value.getLong("version_code"); bytes=value.getLong("bytes");
            sha256=value.getString("sha256"); notes=value.optString("notes", "");
            if(!packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")||version.isEmpty()||version.length()>80||versionCode<=0
                    ||bytes<=0||bytes>MAX_APK||!sha256.matches("[0-9a-f]{64}")||notes.length()>1000)
                throw new IOException("电脑提供的更新信息无效");
        }
    }

    static final class Job implements AutoCloseable {
        private final Context context;
        private final String origin;
        private volatile boolean canceled;
        private volatile HttpURLConnection connection;
        Job(Context context) { this(context, "http://"+host(context)+":"+UpdateConfig.PORT); }
        Job(Context context,String origin) { this.context=context.getApplicationContext();this.origin=origin; }
        void active() throws IOException { if(canceled||Thread.currentThread().isInterrupted())throw new IOException("已取消更新"); }
        HttpURLConnection connect(String path) throws Exception {
            active();
            HttpURLConnection conn=(HttpURLConnection)new URL(origin+path).openConnection();connection=conn;
            conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(5000);conn.setReadTimeout(8000);
            conn.setRequestProperty("Authorization","Bearer "+UpdateConfig.TOKEN);conn.setRequestProperty("Accept-Encoding","identity");
            active();int status=conn.getResponseCode();
            if(status!=200) { conn.disconnect();throw new IOException(status==401?"电脑的更新授权不匹配":"电脑更新服务返回错误（"+status+"）"); }
            return conn;
        }
        Release check() throws Exception {
            HttpURLConnection conn=connect("/manifest.json");
            try(InputStream input=conn.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] buffer=new byte[2048];int count;
                while((count=input.read(buffer))!=-1){active();if(bytes.size()+count>16384)throw new IOException("更新信息超过大小限制");bytes.write(buffer,0,count);}
                Release release=new Release(new JSONObject(bytes.toString("UTF-8")));
                if(!context.getPackageName().equals(release.packageName))throw new IOException("更新包不是当前桌面应用");
                return release;
            }finally{conn.disconnect();connection=null;}
        }
        File download(Release release,Progress progress) throws Exception {
            if(code(installed(context))>=release.versionCode)throw new IOException("当前版本已经是最新，无需重复安装");
            File directory=new File(context.getCacheDir(),"launcher-updates");
            if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建更新缓存");
            File file=File.createTempFile("update-",".apk",directory);boolean complete=false;
            HttpURLConnection conn=null;
            try {
                if(directory.getUsableSpace()<release.bytes*2)throw new IOException("电视存储空间不足");
                conn=connect("/packages/"+release.sha256+".apk");
                long length=conn.getContentLengthLong();
                if(length>=0&&length!=release.bytes)throw new IOException("更新包大小与版本信息不符");
                long total=0,deadline=android.os.SystemClock.elapsedRealtime()+120000;int percent=-1;
                try(InputStream input=conn.getInputStream();FileOutputStream output=new FileOutputStream(file)){
                    byte[] buffer=new byte[65536];int count;
                    while((count=input.read(buffer))!=-1){
                        active();if(android.os.SystemClock.elapsedRealtime()>deadline)throw new IOException("下载超时，请检查网络后重试");
                        if(count>release.bytes-total)throw new IOException("更新包超过声明大小");
                        output.write(buffer,0,count);total+=count;
                        int next=(int)(100*total/release.bytes);if(next!=percent){percent=next;progress.set("正在下载 "+release.version+" · "+percent+"%");}
                    }
                    output.getFD().sync();
                }
                active();verify(context,file,release);complete=true;return file;
            }finally{
                if(conn!=null)conn.disconnect();connection=null;
                if(!complete&&!file.delete())file.deleteOnExit();
            }
        }
        @Override public void close(){canceled=true;HttpURLConnection conn=connection;if(conn!=null)conn.disconnect();}
    }
    static Set<String> certificates(PackageInfo info) throws Exception {
        Signature[] signatures;
        if(Build.VERSION.SDK_INT>=28){
            if(info.signingInfo==null)throw new IOException("无法读取更新包签名");
            signatures=info.signingInfo.getApkContentsSigners();
        }else signatures=info.signatures;
        if(signatures==null||signatures.length==0)throw new IOException("更新包缺少签名");
        Set<String> result=new HashSet<>();for(Signature signature:signatures)result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));return result;
    }
    static void verify(Context context,File file,Release release) throws Exception {
        if(file.length()!=release.bytes)throw new IOException("更新包不完整，请重新下载");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}
        if(!hex(digest.digest()).equals(release.sha256))throw new IOException("更新包校验失败，请重新下载");
        PackageInfo archive=context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),signingFlags());
        if(archive==null||!context.getPackageName().equals(archive.packageName)||!release.packageName.equals(archive.packageName))throw new IOException("更新包不是客厅桌面");
        PackageInfo own=installed(context);
        if(code(archive)!=release.versionCode||!release.version.equals(archive.versionName)||code(archive)<=code(own))throw new IOException("更新包版本不匹配或不是较新版本");
        if(!certificates(own).equals(certificates(archive)))throw new IOException("更新包签名不匹配，已阻止安装");
    }
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}

    static void install(Context context,File file,Release release,Job job) throws Exception {
        job.active();
        verify(context,file,release);
        PackageInstaller installer=context.getPackageManager().getPackageInstaller();SharedPreferences state=prefs(context);
        int previous=state.getInt("session",-1);
        for(PackageInstaller.SessionInfo info:installer.getMySessions())if(info.getSessionId()==previous)installer.abandonSession(previous);
        PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());params.setSize(release.bytes);
        if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int id=installer.createSession(params);boolean committed=false;
        try(PackageInstaller.Session session=installer.openSession(id)){
            String nonce=UUID.randomUUID().toString();
            if(!state.edit().putInt("session",id).putString(NONCE,nonce).putLong("target_code",release.versionCode)
                    .putString("message","等待电视系统确认安装 "+release.version).remove("system_status").commit())throw new IOException("无法保存更新安装状态");
            try(InputStream input=new FileInputStream(file);OutputStream output=session.openWrite("base.apk",0,release.bytes)){
                byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1){job.active();output.write(buffer,0,count);}session.fsync(output);
            }
            Intent result=new Intent(context,UpdateResultReceiver.class).setAction(ACTION_RESULT).putExtra(NONCE,nonce);
            int flags=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
            job.active();session.commit(PendingIntent.getBroadcast(context,id,result,flags).getIntentSender());committed=true;
        }finally{if(!committed)installer.abandonSession(id);}
    }
    static void receive(Context context,Intent intent){
        SharedPreferences state=prefs(context);
        if(intent==null||!ACTION_RESULT.equals(intent.getAction())||!intent.hasExtra(PackageInstaller.EXTRA_STATUS)
                ||!state.getString(NONCE,"").equals(intent.getStringExtra(NONCE))
                ||intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-2)!=state.getInt("session",-1))return;
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        String message=status==PackageInstaller.STATUS_SUCCESS?"系统报告更新安装成功":
            status==PackageInstaller.STATUS_PENDING_USER_ACTION?"请在电视系统弹窗中确认更新":
            status==PackageInstaller.STATUS_FAILURE_ABORTED?"已取消安装，可再次检查更新":"系统未能完成安装（"+status+"）";
        state.edit().putInt("system_status",status).putString("system_detail",intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            .putString("message",message).commit();
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
            Intent confirmation=intent.getParcelableExtra(Intent.EXTRA_INTENT);
            try{if(confirmation==null)throw new IOException("缺少安装确认入口");context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
            catch(Exception failure){state.edit().putString("message","系统未能打开安装确认，请返回后重试").commit();}
        }
    }
}
