package io.github.elliclee.livingroom;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import java.util.List;

/** Resolves public settings and HOME components on this device, without vendor policy writes. */
final class DeviceNavigation {
    private static final String PREFS="device-navigation", ORIGINAL="original-home";
    private static Intent homes(){return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);}
    private static boolean eligible(Context context,ResolveInfo info){
        return info!=null&&info.activityInfo!=null&&info.activityInfo.enabled&&info.activityInfo.exported
            &&!context.getPackageName().equals(info.activityInfo.packageName)&&!"android".equals(info.activityInfo.packageName);
    }
    static void rememberOriginalHome(Context context){
        ResolveInfo current=context.getPackageManager().resolveActivity(homes(),PackageManager.MATCH_DEFAULT_ONLY);
        if(eligible(context,current))context.getSharedPreferences(PREFS,0).edit().putString(ORIGINAL,
            new ComponentName(current.activityInfo.packageName,current.activityInfo.name).flattenToString()).apply();
    }
    static Intent settings(){return new Intent(Settings.ACTION_SETTINGS);}
    static Intent originalHome(Context context){
        PackageManager pm=context.getPackageManager();
        List<ResolveInfo> candidates=pm.queryIntentActivities(homes(),PackageManager.MATCH_DEFAULT_ONLY);
        ComponentName saved=ComponentName.unflattenFromString(context.getSharedPreferences(PREFS,0).getString(ORIGINAL,""));
        for(ResolveInfo info:candidates)if(eligible(context,info)){
            ComponentName component=new ComponentName(info.activityInfo.packageName,info.activityInfo.name);
            if(component.equals(saved))return homes().setComponent(component);
        }
        ResolveInfo unique=null;
        for(ResolveInfo info:candidates)if(eligible(context,info)){if(unique!=null)return new Intent(Settings.ACTION_HOME_SETTINGS);unique=info;}
        if(unique!=null)return homes().setComponent(new ComponentName(unique.activityInfo.packageName,unique.activityInfo.name));
        return new Intent(Settings.ACTION_HOME_SETTINGS);
    }
}
