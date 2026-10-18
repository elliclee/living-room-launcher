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
    private Object field(Object owner,String name) throws ReflectiveOperationException {
        Field value=owner.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(owner);
    }
    private void call(Object owner,String name) throws ReflectiveOperationException {
        java.lang.reflect.Method method=owner.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(owner);
    }
    private View find(View view,String description){
        if(view.getContentDescription()!=null&&view.getContentDescription().toString().startsWith(description))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            View found=find(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;
        }
        return null;
    }
    /** Window pixels cover the GPU alpha layer, which software Canvas cannot reproduce. */
    private void windowAlphaBoundary(Object panel,boolean right) throws Exception {
        if(Build.VERSION.SDK_INT<26||!android.animation.ValueAnimator.areAnimatorsEnabled())return;
        java.util.concurrent.CountDownLatch copied=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        Runnable capture=()->{
            try{
                View surface=(View)field(panel,"surface"),frame=(View)field(panel,"frame");
                View first=right?find(frame,"背景来源"):(View)field(panel,"first");
                View fade=(View)field(panel,"fade");
                surface.animate().cancel();frame.animate().cancel();surface.setTranslationX(right?first.getWidth()/260f*40:0);surface.setTranslationY(0);fade.setAlpha(.5f);
                android.view.Window window=((android.app.Dialog)field(panel,"dialog")).getWindow();
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));frame.setBackgroundColor(Color.BLACK);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->{
                    try{
                    check(frame.isAttachedToWindow(),"Dialog remains attached for pixel copy");
                    Bitmap image=Bitmap.createBitmap(window.getDecorView().getWidth(),window.getDecorView().getHeight(),Bitmap.Config.ARGB_8888);
                    android.view.PixelCopy.request(window,image,status->{
                        try{
                            check(status==android.view.PixelCopy.SUCCESS,"Copy the actual composited dialog pixels");
                            int[] origin=new int[2];first.getLocationInWindow(origin);
                            int y=origin[1]+Math.round(first.getHeight()*first.getScaleY()/2);
                            int width=Math.round(first.getWidth()*first.getScaleX());
                            int edge=image.getPixel(origin[0]+(right?width-3:2),y);
                            int inside=image.getPixel(origin[0]+(right?width-16:width/8),y);
                            check(Color.red(inside)>90,"Reference pixel is in the selected module");
                            check(Math.abs(Color.red(edge)-Color.red(inside))<=8,"GPU alpha composition preserves focused overflow during "+(right?"right capsule exit":"initial module entrance"));
                        }catch(Throwable value){failure.set(value);}finally{image.recycle();fade.setAlpha(1);copied.countDown();}
                    },new android.os.Handler(android.os.Looper.getMainLooper()));
                    }catch(Throwable value){failure.set(value);copied.countDown();}
                },80);
            }catch(Throwable value){failure.set(value);copied.countDown();}
        };
        // Freeze the maximum exit offset without racing the window's final removal.
        runOnMainSync(capture);
        check(copied.await(5,java.util.concurrent.TimeUnit.SECONDS),"Composited dialog pixels arrived for "+(right?"maximum exit overflow":"entrance"));
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private void entrance(HomeActivity activity) throws Exception {
        java.util.concurrent.CountDownLatch painted=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        final Object[] owner=new Object[1];
        runOnMainSync(()->{
            try{
                call(activity,"showSettings");
                owner[0]=((java.util.Collection<?>)field(activity,"openPanels")).iterator().next();
                View first=(View)field(owner[0],"first");Object control=first.getTag();
                check(((Float)field(control,"value"))==1f,"Initial selection must be complete before the control center can draw");
                check(field(control,"animator")==null,"Window focus must not start a second entrance animation");
                first.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
                    int frames;
                    @Override public boolean onPreDraw(){
                        try{
                            check(first.hasFocus(),"Initial TV settings owns focus at draw");
                            check(((Float)field(control,"value"))==1f,"Selection stays complete during panel entrance");
                            check(Math.abs(first.getScaleX()-1.05f)<.001f,"Initial module scale is already complete");
                            Bitmap pixels=Bitmap.createBitmap(first.getWidth(),first.getHeight(),Bitmap.Config.ARGB_8888);
                            try{
                                first.draw(new Canvas(pixels));
                                check(Color.alpha(pixels.getPixel(1,1))==0,"Actual module corner is rounded from first draw");
                                check(pixels.getPixel(first.getWidth()/8,first.getHeight()/2)==TvAppearance.FOCUS,"Actual module has its final white selection fill");
                            }finally{pixels.recycle();}
                        }catch(Throwable value){failure.set(value);}
                        if(failure.get()!=null||++frames>=3){first.getViewTreeObserver().removeOnPreDrawListener(this);painted.countDown();}
                        return true;
                    }
                });
            }catch(Throwable value){failure.set(value);painted.countDown();}
        });
        check(painted.await(5,java.util.concurrent.TimeUnit.SECONDS),"Three real panel entrance draws completed");
        if(failure.get()!=null)throw new AssertionError(failure.get());
        windowAlphaBoundary(owner[0],false);
        runOnMainSync(()->{
            try{
                View other=find((View)field(owner[0],"frame"),"背景来源");check(other!=null,"Next control is available");
                check(other.requestFocusFromTouch(),"Move focus after opening");
                android.animation.ValueAnimator animator=(android.animation.ValueAnimator)field(other.getTag(),"animator");
                boolean animated=Build.VERSION.SDK_INT<26||android.animation.ValueAnimator.areAnimatorsEnabled();
                if(animated)check(animator!=null&&animator.isStarted(),"Subsequent focus moves retain their animation");
                else check(animator==null&&((Float)field(other.getTag(),"value"))==1f,"Disabled animations settle the next focus immediately");
            }catch(Throwable value){failure.set(value);}
        });
        if(failure.get()!=null)throw new AssertionError(failure.get());
        android.os.SystemClock.sleep(450);
        windowAlphaBoundary(owner[0],true);
        runOnMainSync(()->{try{call(field(owner[0],"dialog"),"dismissNow");}catch(Throwable value){failure.set(value);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
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
            entrance(activity);
            passed.put("Actual entrance focus is complete; GPU alpha preserves initial and right-capsule exit overflow; later focus moves animate");
            result.putString("passed",passed.toString());finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){result.putString("failure",failure.toString());finish(Activity.RESULT_CANCELED,result);}
    }
}
