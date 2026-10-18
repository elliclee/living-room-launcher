package io.github.elliclee.livingroom;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import org.json.JSONArray;

/** Emulator-only checks for the first paint and real HOME focus-container boundaries. */
public final class ControlRenderingTestRunner extends Instrumentation {
    private void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}

    private void paint(int width,int height,float radius) {
        Context context=getTargetContext();
        TvAppearance.Glass glass=new TvAppearance.Glass(context,new TvAppearance.Wallpaper(context),radius,1,TvAppearance.CLEAR).clipContent(false);
        TvAppearance.Fill fill=new TvAppearance.Fill(0x00ffffff,radius);glass.focusFill(fill);
        View content=new View(context);content.setBackgroundColor(Color.BLACK);
        glass.addView(content,new FrameLayout.LayoutParams(16,16,android.view.Gravity.CENTER));
        glass.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        glass.layout(0,0,width,height);
        for(float fraction:new float[]{0,.1f,.5f,1}){
            fill.fraction(fraction);Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            glass.draw(new Canvas(bitmap));
            check(Color.alpha(bitmap.getPixel(1,1))==0,"Rounded corner from first paint at fraction "+fraction);
            check(Color.alpha(bitmap.getPixel(width/2,2))>0,"Top edge is painted");
            check(bitmap.getPixel(width/2,height/2)==Color.BLACK,"Content remains above focus paint");
            if(fraction==1)check(bitmap.getPixel(width/2,height/4)==TvAppearance.FOCUS,"Focused interior is white");
            bitmap.recycle();
        }
        check(!glass.getClipChildren()&&!glass.getClipToPadding()&&!glass.getClipToOutline(),"Focus glass does not clip descendants");
        glass.clipContent(true);
        check(glass.getClipChildren()&&glass.getClipToPadding()&&glass.getClipToOutline(),"Scrolling glass retains clipping");
    }
    @Override public void onStart(){
        Bundle result=new Bundle();JSONArray passed=new JSONArray();
        try{
            check(Build.PRODUCT.contains("sdk"),"Emulator only");
            runOnMainSync(()->{paint(260,260,52);paint(260,120,-1);});
            passed.put("First paint and 3 focus fractions stay rounded; content remains visible; scroll clipping retained");
            ActivityMonitor monitor=addMonitor(HomeActivity.class.getName(),null,false);
            Activity started;
            try{
                runOnMainSync(()->getTargetContext().startActivity(new Intent(getTargetContext(),HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)));
                started=monitor.waitForActivityWithTimeout(5000);
            }finally{removeMonitor(monitor);}
            check(started instanceof HomeActivity,"HOME started asynchronously");
            HomeActivity activity=(HomeActivity)started;
            runOnMainSync(()->{
                try{
                    Field field=HomeActivity.class.getDeclaredField("settings");field.setAccessible(true);View gear=(View)field.get(activity);
                    ViewGroup row=(ViewGroup)gear.getParent(),pill=(ViewGroup)row.getParent();
                    check(!row.getClipChildren()&&!row.getClipToPadding(),"Clock row permits focused gear overflow");
                    check(!pill.getClipChildren()&&!pill.getClipToPadding()&&!pill.getClipToOutline(),"Clock glass permits focused gear overflow");
                    gear.requestFocusFromTouch();
                }catch(ReflectiveOperationException failure){throw new RuntimeException(failure);}
            });
            passed.put("Real HOME gear parent chain allows focus expansion, including padding");
            result.putString("passed",passed.toString());finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){result.putString("failure",failure.toString());finish(Activity.RESULT_CANCELED,result);}
    }
}
