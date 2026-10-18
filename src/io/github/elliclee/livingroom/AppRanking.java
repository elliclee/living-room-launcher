package io.github.elliclee.livingroom;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Owns the default "most used first" order used until the user arranges apps by hand.
 * Inputs: a living-room prior for apps seen on the TCL TV, real launches from this home
 * (decayed, half-life 14 days) and the packages in TCL's own watch history.
 */
final class AppRanking {
    private static final long HALF_LIFE = 14L * 24 * 60 * 60 * 1000;
    private static final int MAX_ENTRIES = 200;
    private static final double LAUNCH_WEIGHT = 15, HISTORY_FIRST = 24, HISTORY_OTHER = 12;
    private static final Map<String, Integer> KNOWN = new HashMap<>();
    // Package/label pairs come from the TV's inventory (analysis/received-20261018-114711) and its 4.x home capture.
    static {
        known(52, "com.tcl.tv", "com.tcl.tliveplay");                                   // 电视、有线频道
        known(46, "com.tcl.vod");                                                       // VOD
        known(44, "com.tcl.miracast", "com.tcl.MultiScreenInteraction_TV", "com.tcl.ui_mediaCenter"); // 无线投屏、多屏互动、媒体中心
        known(38, "com.tcl.ffkaraoke", "com.tcl.ffeducation");                          // 音乐、雷咚咚少儿
        known(32, "com.tcl.appmarket2", "com.shafa.market", "com.ant.store.appstore");  // 应用商店与已允许的市场
        known(28, "com.tcl.tvsmartalbum", "com.tcl.appreciate.art", "com.tcl.common.weather"); // 智能相册、氛围屏、天气预报
        known(24, "com.tcl.settings", "com.tcl.wholenetsearch", "com.kookong.tvplus");  // 设置、搜索、机顶盒设置
        known(12, "com.tcl.guard", "com.tcl.t_solo", "com.tcl.walleve", "com.tcl.gamebar", "com.tcl.repairguide",
            "com.tcl.playskill", "com.tcl.versionUpdateApp", "com.tcl.usercenter", "com.tcl.workmode", "com.tcl.soundbox",
            "com.tcl.c2dm.client", "com.tcl.micmanager", "com.tcl.hearaid", "com.tcl.initsetup", "com.audiocn.karaoke.micmanager",
            "com.tcl.common.shortcutmenu", "com.tcl.atomic.servicecenter", "com.sohu.inputmethod.sogou.tv");
    }
    private static final Pattern VIDEO = Pattern.compile("(?<![A-Za-z])TV(?![A-Za-z])|视频|影视|影院|芒果|爱奇艺|奇异果|腾讯|极光|优酷|酷喵|哔哩|小电视|西瓜|抖音|快手|咪咕|央视|CCTV|宝盒|欢喜|南瓜|直播|斗鱼|虎牙|(?i:netflix|youtube|disney)");
    private static final Pattern MUSIC = Pattern.compile("音乐|K歌|酷狗|酷我|网易云|(?i:music|spotify)");
    private static final Pattern MEDIA = Pattern.compile("网盘|云盘|播放器|投屏|相册");

    private AppRanking() { }

    private static void known(int prior, String... packages) { for (String id : packages) KNOWN.put(id, prior); }

    /** Living-room prior: video first, then live TV, casting and media, music and kids, store, ambient, settings, utilities. */
    static int prior(String id, String label, boolean system) {
        Integer known = KNOWN.get(id);
        if (known != null) return known;
        if (MUSIC.matcher(label).find()) return 40;
        if (VIDEO.matcher(label).find()) return 60;
        if (MEDIA.matcher(label).find()) return 36;
        return system ? 16 : 30;
    }

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("home-usage", Context.MODE_PRIVATE); }

    /** Score for sorting; watched lists TCL history packages, most recent first. */
    static double score(SharedPreferences usage, String id, int prior, List<String> watched, long now) {
        int recency = watched.indexOf(id);
        return prior + LAUNCH_WEIGHT * launches(usage, id, now) + (recency < 0 ? 0 : recency == 0 ? HISTORY_FIRST : HISTORY_OTHER);
    }

    /** Decayed launch count; unreadable owned entries count as zero and are overwritten on the next launch. */
    static double launches(SharedPreferences usage, String id, long now) {
        String value = usage.getString(id, null);
        if (value == null) return 0;
        int at = value.indexOf('@');
        try {
            double count = Double.parseDouble(value.substring(0, at));
            long when = Long.parseLong(value.substring(at + 1));
            return count * Math.pow(.5, Math.max(0, now - when) / (double) HALF_LIFE);
        } catch (RuntimeException malformed) { return 0; }
    }

    /** Called after this home successfully started an app. */
    static void launched(Context context, String id) {
        SharedPreferences usage = prefs(context);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor edit = usage.edit().putString(id, String.format(Locale.ROOT, "%.4f@%d", launches(usage, id, now) + 1, now));
        Map<String, ?> all = usage.getAll();
        if (all.size() >= MAX_ENTRIES) {
            String weakest = null; double low = Double.MAX_VALUE;
            for (String key : all.keySet()) { double v = launches(usage, key, now); if (!key.equals(id) && v < low) { low = v; weakest = key; } }
            if (weakest != null) edit.remove(weakest);
        }
        edit.apply();
    }
}
