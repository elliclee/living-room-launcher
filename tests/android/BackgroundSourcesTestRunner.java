package io.github.elliclee.livingroom;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import java.util.Arrays;
import java.util.HashSet;
import org.json.JSONArray;

/** Real preference migration, membership/rotation and selected-provider availability. Emulator only. */
public final class BackgroundSourcesTestRunner extends Instrumentation {
    private void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();JSONArray passed=new JSONArray();
        try{
            check(Build.PRODUCT.contains("sdk"),"Emulator only");Context app=getTargetContext();SharedPreferences prefs=BackgroundRepository.prefs(app);
            prefs.edit().clear().putString("source","nasa").commit();
            check(BackgroundRepository.selected(app).equals(Arrays.asList("nasa")),"Legacy single choice retained");
            check(BackgroundRepository.toggle(app,"art"),"Add art");
            check(BackgroundRepository.selected(app).equals(Arrays.asList("art","nasa"))&&BackgroundRepository.mode(app).equals("nasa"),"Two selected, active preserved");
            long revision=BackgroundRepository.selectionRevision(app);BackgroundRepository.advance(app);
            check(BackgroundRepository.mode(app).equals("art")&&BackgroundRepository.selectionRevision(app)==revision,"Rotation does not change configuration");
            BackgroundRepository.advance(app);check(BackgroundRepository.mode(app).equals("nasa"),"Source rotation wraps");
            check(BackgroundRepository.toggle(app,"nasa")&&BackgroundRepository.mode(app).equals("art"),"Removing active source selects a remaining source");
            check(!BackgroundRepository.toggle(app,"art")&&BackgroundRepository.selected(app).equals(Arrays.asList("art")),"Last source preserved");
            passed.put("Legacy migration, multiple selections, toggle cancellation, last-source guard and stable rotation");
            BackgroundRepository.configureCustom(app,"https://example.com/background.jpg");
            check(BackgroundRepository.selected(app).equals(Arrays.asList("art","custom"))&&BackgroundRepository.mode(app).equals("custom"),"Custom setup preserves art selection");
            check(BackgroundRepository.activateGallery(app)&&BackgroundRepository.mode(app).equals("art")&&BackgroundRepository.selected(app).size()==2,"Gallery browsing preserves selected set");
            try{BackgroundRepository.configureCustom(app,"file:///private.jpg");throw new AssertionError("Unsafe custom URL accepted");}catch(java.io.IOException expected){}
            prefs.edit().putStringSet("sources",new HashSet<>(Arrays.asList("coast"))).putString("source","coast").commit();
            check(!BackgroundRepository.activateGallery(app)&&!BackgroundRepository.rotates(app),"No unselected gallery activated");
            passed.put("Custom configuration and gallery browsing retain the user's set; invalid URL rejected");
            prefs.edit().putStringSet("sources",new HashSet<>(Arrays.asList("recent","coast"))).putString("source","recent").commit();
            BackgroundRepository.Scene available=BackgroundRepository.load(app,false);check(available.source.equals("coast"),"Unavailable recent skips to selected coast, not unselected Bing");available.bitmap.recycle();
            prefs.edit().putStringSet("sources",new HashSet<>(Arrays.asList("custom","coast"))).putString("source","custom").putString("custom_url","invalid").commit();
            available=BackgroundRepository.load(app,true);check(available.source.equals("coast"),"Unavailable custom skips to selected coast");available.bitmap.recycle();
            check(BackgroundRepository.selected(app).equals(Arrays.asList("coast","custom")),"Availability does not rewrite selected sources");
            passed.put("Actual loads only use selected alternatives; no network request to unchecked daily source");
            // Keep gallery tests independent of this suite's preferences.
            prefs.edit().clear().putString("source","art").commit();
            result.putString("passed",passed.toString());finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){result.putString("failure",failure.toString());finish(Activity.RESULT_CANCELED,result);}
    }
}
