package io.github.elliclee.livingroom;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;

/** Native, remote-first HOME modelled on the tvOS Home Screen. Package identity owns app order and visibility. */
public final class HomeActivity extends Activity {
    private static final int COLUMNS=6, REQUEST_HOME_ROLE=4101;
    // tvOS six-column grid in 1920×1080 design points (HIG: 260 pt items, 40 pt gutters, 80 pt side margins, 100 pt rows).
    private static final float SIDE=80, GUTTER=40, ROW_GAP=100, CARD_RADIUS=20, FOCUS_SCALE=1.2f;
    // Dock: tvOS keeps ~36 pt above the first row; the lower band also holds the focused app name.
    private static final float DOCK_TOP=36, DOCK_SIDE=46, DOCK_BOTTOM=70, PEEK=28, GRID_TOP=110, NAME_SPACE=60;
    // tvOS text styles (HIG, pt): Title 1 76, Headline 38, Callout 31, Body 29, Caption 1 25, Caption 2 23.
    private static final float TITLE1=76, HEADLINE=38, CALLOUT=31, BODY=29, CAPTION1=25, CAPTION2=23;
    private static final int GLIDE_MS=450, FOCUS_IN_MS=320, FOCUS_OUT_MS=220, CONTROL_MS=180;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService backgrounds=Executors.newSingleThreadExecutor();
    private final ExecutorService updates=Executors.newSingleThreadExecutor();
    private final List<App> all=new ArrayList<>(),visible=new ArrayList<>();
    private final Tiles adapter=new Tiles();
    private final Set<String> pendingArt=new HashSet<>();
    private final List<Panel> openPanels=new ArrayList<>();
    private final LruCache<String,Bitmap> art=new LruCache<String,Bitmap>((int)Math.max(16L<<20,Math.min(64L<<20,Runtime.getRuntime().maxMemory()/8))) {
        @Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    private final Interpolator ease=new PathInterpolator(.2f,.8f,.2f,1f), glide=new PathInterpolator(.3f,0f,.1f,1f),
        spring=new PathInterpolator(.3f,1.3f,.5f,1f), leave=new PathInterpolator(.4f,0f,1f,1f);
    private SharedPreferences preferences;
    private FrameLayout root;
    private TvAppearance.Wallpaper wallpaper;
    private TvAppearance.Glass dock,pill,notice;
    private GridView grid;
    private LinearLayout story,hint;
    private TextView clock,empty,sceneTitle,sceneSubtitle,focusedName,noticeText;
    private Button sceneOpen;
    private View settings,scrim,backgroundTarget;
    private TextView backgroundCaption;
    private boolean browsing;
    private int queuedGallerySteps;
    private Control gearControl;
    private PanelDialog controls;
    private UpdateScreen updateScreen;
    private BackgroundRepository.Scene scene;
    private ValueAnimator backdrop;
    private int selectedPosition,cardWidth,cardHeight,rowPitch,dockRow,scrollRow,lastKey,scanGeneration,backgroundGeneration,width,height,scrimUsers;
    private float unit,blurLevel,titleLevel;
    private boolean destroyed,firstLoad=true,expanded,shelf,inMotion,backgroundBusy,forceQueued,pendingInitialConfirm;
    private long lastBackground;
    private String catalogueError,moving;
    private List<String> watched=Collections.emptyList();

    private static final class App {
        final String id,label,key;
        final ComponentName component;
        final int prior;
        App(String id,String label,long updated,ComponentName component,int prior){this.id=id;this.label=label;key=id+":"+updated;this.component=component;this.prior=prior;}
    }
    private int px(float value){return Math.round(value*unit);}
    private TextView text(String value,float size,int color,int weight){
        TextView view=new TextView(this);view.setText(value);view.setTextColor(color);view.setTypeface(TvAppearance.face(weight));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP,size*unit/getResources().getDisplayMetrics().density);
        view.setFontFeatureSettings("tnum");view.setIncludeFontPadding(true);return view;
    }
    private boolean animations(){return Build.VERSION.SDK_INT<26||ValueAnimator.areAnimatorsEnabled();}
    private FrameLayout.LayoutParams box(int w,int h,int x,int y){
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(w,h);p.leftMargin=x;p.topMargin=y;return p;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);DeviceNavigation.rememberOriginalHome(this);preferences=getSharedPreferences("home-layout",MODE_PRIVATE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        width=getResources().getDisplayMetrics().widthPixels;height=getResources().getDisplayMetrics().heightPixels;
        unit=Math.min(width/1920f,height/1080f);
        cardWidth=Math.max(1,Math.round((width-px(SIDE)*2-px(GUTTER)*(COLUMNS-1))/(float)COLUMNS));cardHeight=Math.round(cardWidth*.6f);
        rowPitch=cardHeight+px(ROW_GAP);dockRow=height-px(PEEK)-rowPitch;
        root=new FrameLayout(this);root.setBackgroundColor(0xff000000);setContentView(root);
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
            @Override public boolean onPreDraw(){
                if(!firstLoad&&root.hasWindowFocus()){
                    root.getViewTreeObserver().removeOnPreDrawListener(this);focusInitial();
                }return true;
            }
        });
        wallpaper=new TvAppearance.Wallpaper(this);root.addView(wallpaper,new FrameLayout.LayoutParams(-1,-1));
        backgroundTarget=new View(this);backgroundTarget.setContentDescription("浏览背景，左右切换图片，下键返回应用");
        if(Build.VERSION.SDK_INT>=26)backgroundTarget.setDefaultFocusHighlightEnabled(false);
        root.addView(backgroundTarget,box(1,1,width/2,height/2));
        backgroundTarget.setOnKeyListener((v,key,event)->{
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
            if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){if(event.getRepeatCount()==0)changeBackground(key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1);return true;}
            if(key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_BACK){focusGrid();return true;}
            if(key==KeyEvent.KEYCODE_DPAD_UP){settings.requestFocus();return true;}
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){if(event.getRepeatCount()==0&&scene!=null&&canLaunch(scene.packageName))launchPackage(scene.packageName);return true;}
            return false;
        });


        story=new LinearLayout(this);story.setOrientation(LinearLayout.VERTICAL);story.setGravity(Gravity.START);
        sceneTitle=text("",TITLE1,TvAppearance.TEXT,500);sceneTitle.setMaxLines(2);sceneTitle.setEllipsize(TextUtils.TruncateAt.END);legible(sceneTitle);
        sceneSubtitle=text("",CALLOUT,TvAppearance.MUTED,400);sceneSubtitle.setMaxLines(2);sceneSubtitle.setEllipsize(TextUtils.TruncateAt.END);legible(sceneSubtitle);
        story.addView(sceneTitle,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams subtitleParams=new LinearLayout.LayoutParams(-1,-2);subtitleParams.topMargin=px(8);story.addView(sceneSubtitle,subtitleParams);
        sceneOpen=capsule("打开视频应用",()->{if(scene!=null)launchPackage(scene.packageName);});sceneOpen.setAlpha(0);sceneOpen.setFocusable(false);
        sceneOpen.setOnKeyListener((v,key,event)->{
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
            if(key==KeyEvent.KEYCODE_DPAD_DOWN){focusGrid();return true;}
            if(key==KeyEvent.KEYCODE_DPAD_UP){settings.requestFocus();return true;}
            return key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        LinearLayout.LayoutParams openParams=new LinearLayout.LayoutParams(-2,px(68));openParams.topMargin=px(34);story.addView(sceneOpen,openParams);
        story.setVisibility(View.GONE);root.addView(story,box(px(1100),-2,px(SIDE),px(96)));

        hint=new LinearLayout(this);hint.setOrientation(LinearLayout.VERTICAL);hint.setGravity(Gravity.CENTER_HORIZONTAL);hint.setAlpha(0);
        View chevron=new View(this);chevron.setBackground(new TvAppearance.Glyph(TvAppearance.Glyph.CHEVRON_UP,0xd9ffffff).weight(.12f));
        hint.addView(chevron,new LinearLayout.LayoutParams(px(34),px(22)));
        TextView hintText=text("按上键全屏显示",CAPTION2,0xd9ffffff,500);legible(hintText);hint.addView(hintText,new LinearLayout.LayoutParams(-2,-2));
        FrameLayout.LayoutParams hintParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        hintParams.topMargin=dockRow-px(DOCK_TOP)-px(78);root.addView(hint,hintParams);

        dock=new TvAppearance.Glass(this,wallpaper,px(CARD_RADIUS+DOCK_TOP),px(1.6f),TvAppearance.CLEAR).clipContent(false);
        root.addView(dock,box(width-px(SIDE-DOCK_SIDE)*2,px(DOCK_TOP)+cardHeight+px(DOCK_BOTTOM),px(SIDE-DOCK_SIDE),dockRow-px(DOCK_TOP)));
        empty=text("正在读取应用……",BODY,TvAppearance.TEXT,500);empty.setGravity(Gravity.CENTER);
        dock.addView(empty,new FrameLayout.LayoutParams(-1,-1));

        grid=new AppGrid();grid.setId(View.generateViewId());grid.setNumColumns(COLUMNS);grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(px(GUTTER));grid.setVerticalSpacing(px(ROW_GAP));grid.setPadding(px(SIDE),px(GRID_TOP),px(SIDE),px(GRID_TOP));
        grid.setClipChildren(false);grid.setClipToPadding(false);grid.setSelector(new ColorDrawable(Color.TRANSPARENT));grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setVerticalScrollBarEnabled(false);grid.setVerticalFadingEdgeEnabled(false);grid.setDrawSelectorOnTop(false);grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent,view,position,id)->{if(moving==null)launch(visible.get(position));});
        grid.setOnItemLongClickListener((parent,view,position,id)->{if(moving==null)itemMenu(position);return true;});
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> p,View v,int position,long id){selectedPosition=position;follow(position);project();}
            @Override public void onNothingSelected(AdapterView<?> p){project();}
        });
        grid.setOnKeyListener((v,key,event)->{
            if(moving!=null)return moveKey(key,event);
            if(event.getAction()==KeyEvent.ACTION_DOWN&&(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT||key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN))lastKey=key;
            if((key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)&&pendingInitialConfirm){
                if(event.getAction()==KeyEvent.ACTION_UP){pendingInitialConfirm=false;if(!visible.isEmpty())launch(visible.get(focusedPosition()));}return true;
            }
            if(event.getAction()==KeyEvent.ACTION_DOWN&&grid.getSelectedItemPosition()<0&&!visible.isEmpty()){
                int at=focusedPosition(),next=at;
                if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){pendingInitialConfirm=true;return true;}
                if(key==KeyEvent.KEYCODE_DPAD_RIGHT)next=Math.min(visible.size()-1,at+1);
                else if(key==KeyEvent.KEYCODE_DPAD_LEFT)next=Math.max(0,at-1);
                else if(key==KeyEvent.KEYCODE_DPAD_DOWN)next=Math.min(visible.size()-1,at+COLUMNS);
                else if(key==KeyEvent.KEYCODE_DPAD_UP&&at>=COLUMNS)next=at-COLUMNS;
                if(next!=at){selectedPosition=next;grid.setSelection(next);follow(next);project();return true;}
            }
            // The grid is drawn lower than it is laid out; keep DOWN on the last row from searching outside it.
            if(event.getAction()==KeyEvent.ACTION_DOWN&&key==KeyEvent.KEYCODE_DPAD_DOWN&&!visible.isEmpty()&&focusedPosition()/COLUMNS==(visible.size()-1)/COLUMNS)return true;
            if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0&&key==KeyEvent.KEYCODE_DPAD_UP&&focusedPosition()<COLUMNS){
                enterBackground();return true;
            }return false;
        });
        grid.setEmptyView(empty);
        root.addView(grid,new FrameLayout.LayoutParams(-1,gridHeight(0)));

        focusedName=text("",BODY,TvAppearance.TEXT,500);focusedName.setGravity(Gravity.CENTER_HORIZONTAL);focusedName.setSingleLine(true);focusedName.setEllipsize(TextUtils.TruncateAt.END);
        legible(focusedName);focusedName.setFocusable(false);focusedName.setClickable(false);focusedName.setAlpha(0);
        focusedName.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(focusedName,new FrameLayout.LayoutParams(nameWidth(),-2));

        pill=new TvAppearance.Glass(this,wallpaper,-1,px(1.4f),TvAppearance.CLEAR).clipContent(false);
        LinearLayout time=new LinearLayout(this);time.setGravity(Gravity.CENTER_VERTICAL);time.setPadding(px(24),0,px(6),0);
        clock=text("",CAPTION1,TvAppearance.TEXT,500);time.addView(clock,new LinearLayout.LayoutParams(-2,-2));
        settings=new View(this);settings.setId(View.generateViewId());settings.setFocusable(true);settings.setClickable(true);settings.setContentDescription("控制中心");
        if(Build.VERSION.SDK_INT>=26)settings.setDefaultFocusHighlightEnabled(false);
        TvAppearance.Glyph gear=new TvAppearance.Glyph(TvAppearance.Glyph.GEAR,TvAppearance.TEXT).weight(.085f);
        settings.setForeground(new android.graphics.drawable.InsetDrawable(gear,px(8)));
        gearControl=new Control(settings,new TvAppearance.Fill(0x00ffffff,-1),1.08f);gearControl.glyph(gear);
        settings.setOnClickListener(v->showSettings());
        settings.setOnKeyListener((v,key,event)->{
            if(event.getAction()==KeyEvent.ACTION_DOWN&&key==KeyEvent.KEYCODE_DPAD_UP){enterBackground();return true;}
            if(event.getAction()==KeyEvent.ACTION_DOWN&&(key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_DPAD_LEFT)){
                if(!visible.isEmpty()){focusGrid();return true;}
            }return event.getAction()==KeyEvent.ACTION_DOWN&&(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_RIGHT);
        });
        LinearLayout.LayoutParams gearParams=new LinearLayout.LayoutParams(px(46),px(46));gearParams.leftMargin=px(14);time.addView(settings,gearParams);
        pill.addView(time,new FrameLayout.LayoutParams(-2,-1));
        FrameLayout.LayoutParams pillParams=new FrameLayout.LayoutParams(-2,px(56),Gravity.TOP|Gravity.RIGHT);pillParams.rightMargin=px(56);pillParams.topMargin=px(50);root.addView(pill,pillParams);

        notice=new TvAppearance.Glass(this,wallpaper,px(28),px(1.4f),TvAppearance.REGULAR).clipContent(false);notice.setVisibility(View.GONE);
        noticeText=text("",CAPTION1,TvAppearance.TEXT,500);noticeText.setPadding(px(28),px(16),px(28),px(18));noticeText.setMaxWidth(px(760));
        notice.addView(noticeText,new FrameLayout.LayoutParams(-2,-2));
        FrameLayout.LayoutParams noticeParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT);noticeParams.rightMargin=px(56);noticeParams.topMargin=px(126);root.addView(notice,noticeParams);

        backgroundCaption=text("",CAPTION1,TvAppearance.TEXT,400);backgroundCaption.setMaxLines(4);legible(backgroundCaption);backgroundCaption.setVisibility(View.GONE);
        FrameLayout.LayoutParams creditParams=new FrameLayout.LayoutParams(width-px(160),-2,Gravity.BOTTOM|Gravity.START);creditParams.leftMargin=px(80);creditParams.bottomMargin=px(40);root.addView(backgroundCaption,creditParams);
        scrim=new View(this);scrim.setBackgroundColor(0xff000000);scrim.setAlpha(0);scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        applyMotion(false);
        backgrounds.execute(()->{
            try{Bitmap image=BackgroundRepository.offline(getApplicationContext());Bitmap frost=TvAppearance.frost(image);
                ui.post(()->{if(!destroyed&&scene==null)wallpaper.image(image,frost);});}
            catch(Exception failure){ui.post(()->{if(!destroyed)failure("离线背景读取失败");});}
        });
    }
    private void legible(TextView view){view.setShadowLayer(px(12),0,px(2),0x73000000);}
    private int nameWidth(){return Math.round(cardWidth*FOCUS_SCALE)+px(GUTTER);}
    private int gridHeight(int count){int rows=Math.max(1,(count+COLUMNS-1)/COLUMNS);return px(GRID_TOP)*2+rows*rowPitch-px(ROW_GAP)+px(NAME_SPACE);}
    private final Runnable tick=new Runnable(){
        @Override public void run(){if(destroyed)return;clock.setText(android.text.format.DateFormat.getTimeFormat(HomeActivity.this).format(new Date()));if(lastBackground>0&&!backgroundBusy&&GalleryCollection.isGallery(BackgroundRepository.mode(HomeActivity.this))&&System.currentTimeMillis()-lastBackground>=GalleryCollection.ROTATE_MS)refreshBackground(false);ui.postDelayed(this,60000-System.currentTimeMillis()%60000);}
    };
    @Override protected void onResume(){
        super.onResume();ui.removeCallbacks(tick);tick.run();loadApps();
        if(System.currentTimeMillis()-lastBackground>5*60*1000L)refreshBackground(false);
        if(hasHomeDeclaration(this)&&!isDefaultHome()&&!preferences.getBoolean("home_role_prompted",false)){
            preferences.edit().putBoolean("home_role_prompted",true).apply();ui.post(this::requestHome);
        }
    }
    @Override protected void onPause(){ui.removeCallbacks(tick);if(moving!=null)finishMove();super.onPause();}
    private void refreshBackground(boolean force){refreshBackground(force,1);}
    private void refreshBackground(boolean force,int direction){
        // A manual refresh while an automatic one runs waits for it instead of being reported as a failure.
        if(backgroundBusy){if(force){forceQueued=true;if(!browsing)notice("正在更新背景……",false);}return;}
        backgroundBusy=true;if(force&&!browsing)notice("正在更新背景……",false);
        final int generation=++backgroundGeneration;final String requestedMode=BackgroundRepository.mode(this);
        backgrounds.execute(()->{
            BackgroundRepository.Scene result=null;Bitmap frosted=null;RuntimeException error=null;
            try{result=BackgroundRepository.load(getApplicationContext(),force,direction);frosted=TvAppearance.frost(result.bitmap);}catch(RuntimeException failure){error=failure;}
            final BackgroundRepository.Scene value=result;final Bitmap frost=frosted;final RuntimeException problem=error;
            ui.post(()->{if(destroyed||generation!=backgroundGeneration)return;backgroundBusy=false;lastBackground=System.currentTimeMillis();
                boolean again=forceQueued;forceQueued=false;int steps=queuedGallerySteps;queuedGallerySteps=0;
                if(!requestedMode.equals(BackgroundRepository.mode(this))){refreshBackground(force||again||steps!=0,steps==0?direction:steps);return;}
                if(value!=null&&frost!=null){scene=value;wallpaper.image(value.bitmap,frost);applyScene();}
                else failure("背景读取失败（"+BackgroundRepository.reason(problem)+"）");
                if(steps!=0)refreshBackground(true,steps);
                else if(again)refreshBackground(true);
                else if(force&&value!=null&&!browsing)failure(value.status);
            });
        });
    }
    private void enterBackground(){
        browsing=true;expanded=false;shelf=true;backgroundTarget.setFocusable(true);backgroundTarget.requestFocusFromTouch();applyMotion(true);updateBackgroundCaption();
    }
    private void updateBackgroundCaption(){
        backgroundCaption.setVisibility(browsing?View.VISIBLE:View.GONE);
        if(browsing)backgroundCaption.setText((scene==null||scene.credit.isEmpty()?"":scene.credit+"\n")+"左右切换图片 · 下键返回应用");
    }
    private void changeBackground(int direction){
        if(!GalleryCollection.isGallery(BackgroundRepository.mode(this))){
            BackgroundRepository.prefs(this).edit().putString("source","art").apply();
        }
        if(backgroundBusy){queuedGallerySteps=Math.max(-8,Math.min(8,queuedGallerySteps+direction));return;}
        refreshBackground(true,direction);
    }
    private boolean titled(){return scene!=null&&!scene.title.isEmpty();}
    private boolean shelfAvailable(){return titled()&&sceneOpen.getVisibility()==View.VISIBLE;}
    private void applyScene(){
        story.setVisibility(titled()?View.VISIBLE:View.GONE);
        if(scene!=null){
            sceneTitle.setText(scene.title);sceneSubtitle.setText(("recent".equals(scene.source)?"最近在看":"自定义内容")+(scene.subtitle.isEmpty()?"":" · "+scene.subtitle));
            sceneOpen.setVisibility(canLaunch(scene.packageName)?View.VISIBLE:View.GONE);
        }
        if(shelf&&!browsing&&!shelfAvailable()){shelf=false;if(sceneOpen.hasFocus())focusGrid();}
        updateBackgroundCaption();applyMotion(true);
    }

    /** Derives dock, top-shelf and grid positions from the selected row; the grid itself never scrolls. */
    private void follow(int position){
        int row=Math.max(0,position)/COLUMNS,visibleRows=Math.max(1,(height-px(GRID_TOP)-px(NAME_SPACE))/rowPitch);
        boolean grow=row>0;int scroll=grow?scrollRow:0;
        if(grow){if(row<scroll)scroll=row;if(row>scroll+visibleRows-1)scroll=row-visibleRows+1;}
        boolean changed=grow!=expanded||scroll!=scrollRow||grow&&shelf;
        expanded=grow;scrollRow=scroll;if(grow)shelf=false;
        if(changed)applyMotion(true);
    }
    private void setShelf(boolean value){if(shelf==value||value&&!shelfAvailable())return;shelf=value;applyMotion(true);}
    /** The hidden Top Shelf button must not catch default focus search from the translated grid. */
    private void shelfFocus(){if(!shelf&&sceneOpen.hasFocus())focusGrid();sceneOpen.setFocusable(shelf&&!browsing);}
    private float shelfDrop(){return height-(dockRow-px(DOCK_TOP))+px(24);}
    private void applyMotion(boolean animate){
        float base=dockRow-px(GRID_TOP),gridY=expanded?-scrollRow*rowPitch:base+(shelf?shelfDrop():0),dockY=gridY-base;
        float storyAlpha=titled()&&!expanded&&!browsing?1:0,hintAlpha=!expanded&&!shelf?1:0,openAlpha=shelf&&!browsing&&shelfAvailable()?1:0,pillAlpha=expanded?0:1;
        float blurTarget=expanded?1:0,titleTarget=storyAlpha;
        shelfFocus();
        for(View v:new View[]{grid,dock,story,hint,sceneOpen,pill})v.animate().cancel();
        if(backdrop!=null)backdrop.cancel();
        if(!animate||!animations()){
            grid.setTranslationY(gridY);dock.setTranslationY(dockY);dock.invalidate();story.setAlpha(storyAlpha);hint.setAlpha(hintAlpha);sceneOpen.setAlpha(openAlpha);pill.setAlpha(pillAlpha);
            blurLevel=blurTarget;titleLevel=titleTarget;wallpaper.expanded(blurLevel);wallpaper.titleScrim(titleLevel);inMotion=false;placeName(false);return;
        }
        inMotion=true;placeName(false);
        if(expanded&&notice.getVisibility()==View.VISIBLE){ui.removeCallbacks(hideNotice);hideNotice.run();}
        grid.animate().translationY(gridY).setDuration(GLIDE_MS).setInterpolator(glide).withEndAction(()->{inMotion=false;placeName(true);}).start();
        dock.animate().translationY(dockY).setDuration(GLIDE_MS).setInterpolator(glide).setUpdateListener(a->dock.invalidate()).start();
        story.animate().alpha(storyAlpha).setDuration(GLIDE_MS/2).start();
        hint.animate().alpha(hintAlpha).setDuration(hintAlpha>0?GLIDE_MS:GLIDE_MS/3).start();
        sceneOpen.animate().alpha(openAlpha).setStartDelay(openAlpha>0?GLIDE_MS/3:0).setDuration(GLIDE_MS/2).start();
        pill.animate().alpha(pillAlpha).setDuration(GLIDE_MS/2).start();
        final float blurFrom=blurLevel,titleFrom=titleLevel;
        backdrop=ValueAnimator.ofFloat(0,1);backdrop.setDuration(GLIDE_MS);backdrop.setInterpolator(glide);
        backdrop.addUpdateListener(a->{float f=(float)a.getAnimatedValue();blurLevel=blurFrom+(blurTarget-blurFrom)*f;titleLevel=titleFrom+(titleTarget-titleFrom)*f;
            wallpaper.expanded(blurLevel);wallpaper.titleScrim(titleLevel);});
        backdrop.start();
    }
    private void loadApps(){
        final int generation=++scanGeneration;
        worker.execute(()->{
            try{
                PackageManager pm=getPackageManager();Map<String,ResolveInfo> found=new LinkedHashMap<>();
                for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER})
                    for(ResolveInfo row:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category),0)){
                        if(row.activityInfo==null||!row.activityInfo.enabled||!row.activityInfo.exported)continue;String id=row.activityInfo.packageName;
                        if(id.equals(getPackageName())||id.equals("com.android.tv.frameworkpackagestubs"))continue;
                        if(row.activityInfo.permission!=null&&checkSelfPermission(row.activityInfo.permission)!=PackageManager.PERMISSION_GRANTED)continue;
                        if(!found.containsKey(id))found.put(id,row);
                    }
                List<App> results=new ArrayList<>();
                for(Map.Entry<String,ResolveInfo> entry:found.entrySet()){
                    if(Thread.currentThread().isInterrupted())return;ApplicationInfo info=entry.getValue().activityInfo.applicationInfo;
                    CharSequence label=pm.getApplicationLabel(info);long updated=pm.getPackageInfo(entry.getKey(),0).lastUpdateTime;
                    String name=label==null?entry.getKey():label.toString();boolean system=(info.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;
                    results.add(new App(entry.getKey(),name,updated,new ComponentName(entry.getKey(),entry.getValue().activityInfo.name),AppRanking.prior(entry.getKey(),name,system)));
                }
                // TCL's own watch history is real usage on this TV; it only ranks apps and is not stored.
                final List<String> history=RecentViewing.read(getApplicationContext()).packages;
                ui.post(()->{if(destroyed||generation!=scanGeneration)return;String old=selectedPosition<visible.size()?visible.get(selectedPosition).id:null;
                    all.clear();all.addAll(results);watched=history;catalogueError=null;arrange(old);
                    if(firstLoad){firstLoad=false;focusInitial();}
                });
            }catch(Exception failure){ui.post(()->{if(!destroyed&&generation==scanGeneration){catalogueError="应用读取失败："+failure.getClass().getSimpleName();empty.setText("应用列表读取失败\n请在控制中心刷新");failure(catalogueError);settings.requestFocus();}});}
        });
    }
    private void arrange(String selectId) {
        Map<String, Integer> order = new HashMap<>();
        try {
            JSONArray saved = new JSONArray(preferences.getString("order", "[]"));
            for (int i = 0; i < saved.length(); i++) { String id = saved.getString(i); if (!order.containsKey(id)) order.put(id, i); }
        } catch (JSONException failure) { catalogueError = "保存的应用排序无法读取：" + failure.toString(); }
        Collator collation = Collator.getInstance(Locale.CHINA);
        // A hand-made order always wins; apps outside it (all of them in automatic mode) follow by usage score.
        SharedPreferences usage = AppRanking.prefs(this);
        Map<String, Double> scores = new HashMap<>();
        long now = System.currentTimeMillis();
        for (App app : all) scores.put(app.id, AppRanking.score(usage, app.id, app.prior, watched, now));
        Collections.sort(all, (a, b) -> {
            int rankA = order.containsKey(a.id) ? order.get(a.id) : Integer.MAX_VALUE;
            int rankB = order.containsKey(b.id) ? order.get(b.id) : Integer.MAX_VALUE;
            int rank = Integer.compare(rankA, rankB);
            if (rank != 0) return rank;
            int score = Double.compare(scores.get(b.id), scores.get(a.id));
            if (score != 0) return score;
            int name = collation.compare(a.label, b.label);
            return name != 0 ? name : a.id.compareTo(b.id);
        });
        Set<String> hidden = preferences.getStringSet("hidden", Collections.emptySet());
        visible.clear();
        int selection = 0;
        for (App app : all) if (!hidden.contains(app.id)) {
            if (app.id.equals(selectId)) selection = visible.size();
            visible.add(app);
        }
        if (moving != null && !idsOf(visible).contains(moving)) stopMoving();
        selectedPosition = selection;
        empty.setText("还没有显示的应用\n在控制中心的“显示应用”中选择");
        ViewGroup.LayoutParams size = grid.getLayoutParams();
        if (size.height != gridHeight(visible.size())) { size.height = gridHeight(visible.size()); grid.setLayoutParams(size); }
        adapter.notifyDataSetChanged();
        if (!visible.isEmpty()) { grid.setSelection(selection); follow(selection); }
        else { follow(0); settings.requestFocus(); }
        if (catalogueError != null) failure(catalogueError);
    }
    /** No saved hand-made order means the home follows AppRanking. */
    private boolean automaticOrder(){String saved=preferences.getString("order","[]").trim();return saved.isEmpty()||saved.equals("[]");}
    private static List<String> idsOf(List<App> apps){List<String> ids=new ArrayList<>();for(App app:apps)ids.add(app.id);return ids;}
    /** Persists the visible order by package identity; hidden apps keep their place after it. */
    private void saveOrder(){
        JSONArray saved=new JSONArray();Set<String> seen=new HashSet<>();
        for(App entry:visible)if(seen.add(entry.id))saved.put(entry.id);
        for(App entry:all)if(seen.add(entry.id))saved.put(entry.id);
        preferences.edit().putString("order",saved.toString()).apply();
    }

    private final class AppGrid extends GridView {
        AppGrid(){super(HomeActivity.this);if(Build.VERSION.SDK_INT>=26)setDefaultFocusHighlightEnabled(false);}
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){super.onLayout(changed,l,t,r,b);project();}
        @Override protected void onFocusChanged(boolean focused,int direction,Rect old){super.onFocusChanged(focused,direction,old);project();}
    }
    private int focusedPosition(){
        int nativePosition=grid.getSelectedItemPosition();
        return nativePosition>=0&&nativePosition<visible.size()?nativePosition:visible.isEmpty()?-1:Math.min(selectedPosition,visible.size()-1);
    }
    private Tile tileAt(int position){
        int index=position-grid.getFirstVisiblePosition();View child=index>=0&&index<grid.getChildCount()?grid.getChildAt(index):null;
        return child instanceof Tile?(Tile)child:null;
    }
    /** Projects the single selection onto tiles and the one name label after every layout, selection or focus change. */
    private void project(){
        int position=focusedPosition();if(position>=0&&position<visible.size())selectedPosition=position;
        boolean focused=grid.hasFocus();
        for(int i=0;i<grid.getChildCount();i++){View child=grid.getChildAt(i);if(child instanceof Tile)((Tile)child).active(focused&&grid.getFirstVisiblePosition()+i==position);}
        placeName(true);
    }
    private void placeName(boolean fade){
        if(focusedName==null)return;
        int position=focusedPosition();Tile tile=position>=0?tileAt(position):null;
        if(!grid.hasFocus()||inMotion||tile==null||position>=visible.size()){focusedName.animate().cancel();focusedName.setAlpha(0);focusedName.setTag(null);return;}
        App app=visible.get(position);String name=app.id.equals(moving)?"按确认键完成移动":app.label;
        float x=grid.getX()+tile.getLeft()+tile.getWidth()/2f-nameWidth()/2f,y=grid.getY()+tile.getTop()+cardHeight*(1+FOCUS_SCALE)/2f+px(8);
        String place=name+"@"+Math.round(x)+","+Math.round(y);
        if(place.equals(focusedName.getTag()))return;
        focusedName.setTag(place);focusedName.setText(name);focusedName.setX(x);focusedName.setY(y);
        focusedName.animate().cancel();
        if(fade&&animations()){focusedName.setAlpha(0);focusedName.animate().alpha(1).setStartDelay(90).setDuration(200).setInterpolator(ease).start();}
        else focusedName.setAlpha(1);
    }
    private final class Tile extends FrameLayout {
        final TvAppearance.Shadow shadow;
        final TvAppearance.Artwork card;
        String boundKey,appId;
        Boolean active;
        float focus;
        ValueAnimator glow;
        ObjectAnimator wiggle;
        Tile(){
            super(HomeActivity.this);setClipChildren(false);setClipToPadding(false);
            int spread=px(30);shadow=new TvAppearance.Shadow(HomeActivity.this,px(CARD_RADIUS),spread);shadow.setAlpha(0);
            FrameLayout.LayoutParams around=new FrameLayout.LayoutParams(cardWidth+spread*2,cardHeight+spread*2);around.leftMargin=-spread;around.topMargin=-spread;addView(shadow,around);
            card=new TvAppearance.Artwork(HomeActivity.this,px(CARD_RADIUS));card.setCameraDistance(12000*getResources().getDisplayMetrics().density);
            addView(card,new FrameLayout.LayoutParams(-1,-1));
            // Set once: GridView marks the measured child's params (forceAdd); replacing them on bind detaches it from the window.
            setLayoutParams(new AbsListView.LayoutParams(-1,cardHeight));
        }
        void bind(App app){
            if(!app.id.equals(appId)){appId=app.id;rest();}
            setContentDescription(app.label);boundKey=artKey(app);Bitmap image=art.get(boundKey);card.image(image);
            if(image==null)requestArtwork(app);
            boolean jiggle=app.id.equals(moving);
            if(jiggle&&wiggle==null&&animations()){wiggle=ObjectAnimator.ofFloat(card,"rotation",-1.4f,1.4f);wiggle.setDuration(130);wiggle.setRepeatMode(ValueAnimator.REVERSE);wiggle.setRepeatCount(ValueAnimator.INFINITE);wiggle.start();}
            else if(!jiggle&&wiggle!=null){wiggle.cancel();wiggle=null;card.setRotation(0);}
            active=null;
        }
        /** Resets a recycled tile without animating someone else's focus away. */
        void rest(){
            card.animate().cancel();shadow.animate().cancel();if(glow!=null)glow.cancel();
            card.setScaleX(1);card.setScaleY(1);card.setRotationX(0);card.setRotationY(0);shadow.setScaleX(1);shadow.setScaleY(1);shadow.setAlpha(0);shadow.setTranslationY(0);
            focus=0;card.focus(0,.5f);setTranslationZ(0);active=false;
        }
        void active(boolean value){
            setSelected(value);if(active!=null&&active==value)return;active=value;setTranslationZ(value?px(4):0);
            card.animate().cancel();shadow.animate().cancel();if(glow!=null)glow.cancel();
            float scale=value?FOCUS_SCALE:1,shade=value?.62f:.16f,drop=value?px(18):px(4);
            if(!animations()){card.setScaleX(scale);card.setScaleY(scale);shadow.setScaleX(scale);shadow.setScaleY(scale);shadow.setAlpha(shade);shadow.setTranslationY(drop);focus=value?1:0;card.focus(focus,.5f);return;}
            final float glareFrom=value?(lastKey==KeyEvent.KEYCODE_DPAD_RIGHT?.18f:lastKey==KeyEvent.KEYCODE_DPAD_LEFT?.82f:.5f):.5f,from=focus,to=value?1:0;
            if(value){card.setRotationY(lastKey==KeyEvent.KEYCODE_DPAD_RIGHT?-4:lastKey==KeyEvent.KEYCODE_DPAD_LEFT?4:0);card.setRotationX(lastKey==KeyEvent.KEYCODE_DPAD_DOWN?4:lastKey==KeyEvent.KEYCODE_DPAD_UP?-4:0);}
            int duration=value?FOCUS_IN_MS:FOCUS_OUT_MS;Interpolator curve=value?spring:ease;
            card.animate().scaleX(scale).scaleY(scale).rotationX(0).rotationY(0).setDuration(duration).setInterpolator(curve).start();
            shadow.animate().scaleX(scale).scaleY(scale).alpha(shade).translationY(drop).setDuration(duration).setInterpolator(ease).start();
            glow=ValueAnimator.ofFloat(0,1);glow.setDuration(duration);glow.setInterpolator(ease);
            glow.addUpdateListener(a->{float f=(float)a.getAnimatedValue();focus=from+(to-from)*f;card.focus(focus,glareFrom+(.5f-glareFrom)*f);});glow.start();
        }
    }
    private final class Tiles extends BaseAdapter {
        @Override public int getCount(){return visible.size();}
        @Override public Object getItem(int position){return visible.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public View getView(int position,View recycled,ViewGroup parent){Tile tile=recycled instanceof Tile?(Tile)recycled:new Tile();tile.bind(visible.get(position));return tile;}
    }
    private int artWidth(){return Math.min(1024,Math.round(cardWidth*FOCUS_SCALE*1.05f));}
    private String artKey(App app){return app.key+"@"+artWidth();}
    private void requestArtwork(App app){
        final String key=artKey(app);final int size=artWidth();
        if(!pendingArt.add(key))return;
        worker.execute(()->{
            Bitmap bitmap=AppArtwork.render(getApplicationContext(),app.component,size);art.put(key,bitmap);
            ui.post(()->{pendingArt.remove(key);if(destroyed)return;for(int i=0;i<grid.getChildCount();i++){
                View view=grid.getChildAt(i);if(view instanceof Tile&&key.equals(((Tile)view).boundKey))((Tile)view).card.image(bitmap);
            }});
        });
    }
    private boolean canLaunch(String id){
        if(id==null||id.isEmpty()||id.equals(getPackageName()))return false;
        return getPackageManager().getLeanbackLaunchIntentForPackage(id)!=null||getPackageManager().getLaunchIntentForPackage(id)!=null;
    }
    private void launchPackage(String id){
        if(!canLaunch(id)){failure("视频应用入口暂不可用");return;}
        Intent intent=getPackageManager().getLeanbackLaunchIntentForPackage(id);if(intent==null)intent=getPackageManager().getLaunchIntentForPackage(id);
        if(open(intent))AppRanking.launched(this,id);
    }
    private void launch(App app) {
        Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(app.id);
        if (intent == null) intent = getPackageManager().getLaunchIntentForPackage(app.id);
        if (intent == null) { failure("该应用的启动入口已不可用，请在控制中心刷新应用。"); return; }
        if (open(intent)) AppRanking.launched(this, app.id);
    }

    private boolean open(Intent intent) {
        try { startActivity(intent); return true; }
        catch (ActivityNotFoundException | SecurityException failure) { failure("电视未开放该入口：" + failure.getClass().getSimpleName()); return false; }
    }

    /** Messages appear in the open panel when there is one, otherwise as a glass notice under the clock. */
    private void failure(String message) {
        Panel top = null;
        for (Panel panel : openPanels) if (panel.dialog.open()) top = panel;
        if (top != null) top.note(message); else notice(message, false);
        root.announceForAccessibility(message);
    }
    private final Runnable hideNotice=()->{
        if(!animations()){notice.setVisibility(View.GONE);return;}
        notice.animate().cancel();notice.animate().alpha(0).translationX(px(36)).setDuration(220).setInterpolator(leave).setUpdateListener(a->notice.invalidate()).withEndAction(()->notice.setVisibility(View.GONE)).start();
    };
    private void notice(String message,boolean sticky){
        noticeText.setText(message);ui.removeCallbacks(hideNotice);notice.animate().cancel();
        if(notice.getVisibility()!=View.VISIBLE){notice.setVisibility(View.VISIBLE);notice.setAlpha(0);notice.setTranslationX(px(36));}
        if(animations())notice.animate().alpha(1).translationX(0).setDuration(260).setInterpolator(ease).setUpdateListener(a->notice.invalidate()).start();
        else{notice.setAlpha(1);notice.setTranslationX(0);}
        if(!sticky)ui.postDelayed(hideNotice,Math.min(6500,2600+message.length()*70L));
    }

    private void itemMenu(int position){
        if(position<0||position>=visible.size())return;App app=visible.get(position);
        Panel panel=new Panel(app.label,null,px(560),Panel.ANCHOR);
        Tile tile=tileAt(position);
        if(tile!=null){float grow=(FOCUS_SCALE-1)/2;int left=Math.round(grid.getX()+tile.getLeft()-tile.getWidth()*grow),top=Math.round(grid.getY()+tile.getTop()-tile.getHeight()*grow);
            panel.anchor=new Rect(left,top,left+Math.round(tile.getWidth()*FOCUS_SCALE),top+Math.round(tile.getHeight()*FOCUS_SCALE));}
        panel.action("移动",TvAppearance.Glyph.MOVE,()->{panel.dialog.dismiss();startMove(app.id);});
        if(position>0)panel.action("移到最前",TvAppearance.Glyph.FRONT,()->{panel.dialog.dismiss();
            int current=idsOf(visible).indexOf(app.id);if(current<0){failure("应用列表已更新，请重新选择");return;}
            visible.remove(current);visible.add(0,app);saveOrder();arrange(app.id);grid.requestFocusFromTouch();});
        panel.action("隐藏",TvAppearance.Glyph.HIDE,()->{panel.dialog.dismiss();
            int current=idsOf(visible).indexOf(app.id);if(current<0){failure("应用列表已更新，请重新选择");return;}
            Set<String> hidden=new HashSet<>(preferences.getStringSet("hidden",Collections.emptySet()));hidden.add(app.id);preferences.edit().putStringSet("hidden",hidden).apply();
            String next=visible.size()>1?visible.get(current==visible.size()-1?current-1:current+1).id:null;arrange(next);
            if(visible.isEmpty())settings.requestFocusFromTouch();else grid.requestFocusFromTouch();});
        panel.action("应用信息",TvAppearance.Glyph.INFO,()->{panel.dialog.dismiss();open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+app.id)));});
        panel.show();
    }
    /** tvOS-style edit mode: the held app jiggles and follows the arrow keys until Select or Back. */
    private void startMove(String id){
        if(!idsOf(visible).contains(id))return;moving=id;adapter.notifyDataSetChanged();focusGrid();
        notice("用方向键移动位置，按确认键完成",false);
    }
    private boolean moveKey(int key,KeyEvent event){
        boolean down=event.getAction()==KeyEvent.ACTION_DOWN;int at=idsOf(visible).indexOf(moving);
        if(at<0){stopMoving();return false;}
        if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_BACK||key==KeyEvent.KEYCODE_MENU||key==KeyEvent.KEYCODE_ESCAPE){
            if(!down)finishMove();return true;
        }
        int target=at;
        if(key==KeyEvent.KEYCODE_DPAD_LEFT)target=at-1;
        else if(key==KeyEvent.KEYCODE_DPAD_RIGHT)target=at+1;
        else if(key==KeyEvent.KEYCODE_DPAD_UP)target=at-COLUMNS;
        else if(key==KeyEvent.KEYCODE_DPAD_DOWN)target=at+COLUMNS<visible.size()?at+COLUMNS:at/COLUMNS<(visible.size()-1)/COLUMNS?visible.size()-1:at;
        else return false;
        if(!down||target<0||target>=visible.size()||target==at)return true;
        lastKey=key;App app=visible.remove(at);visible.add(target,app);saveOrder();
        adapter.notifyDataSetChanged();selectedPosition=target;grid.setSelection(target);follow(target);project();return true;
    }
    private void stopMoving(){moving=null;if(notice.getVisibility()==View.VISIBLE){ui.removeCallbacks(hideNotice);ui.post(hideNotice);}}
    private void finishMove(){if(moving==null)return;String id=moving;stopMoving();arrange(id);grid.requestFocus();}
    private void manageApps(){
        if(all.isEmpty()){loadApps();failure("正在刷新应用列表");return;}
        Panel panel=new Panel("显示应用","选择显示在主屏的应用。常用优先排序按使用频率与观看记录自动排列；移动应用后改为自定义顺序。",px(820),Panel.CENTER);
        Set<String> hidden=new HashSet<>(preferences.getStringSet("hidden",Collections.emptySet()));
        final Button[] ranking=new Button[1];
        ranking[0]=panel.action("常用优先排序",()->{
            if(!automaticOrder()){String current=focusedPosition()>=0&&focusedPosition()<visible.size()?visible.get(focusedPosition()).id:null;
                preferences.edit().remove("order").apply();arrange(current);selectionMark(ranking[0],true);panel.note("已恢复常用优先排序");}
            else panel.note("当前已是常用优先排序");
        });selectionMark(ranking[0],automaticOrder());
        for(App app:new ArrayList<>(all)){
            final Button[] row=new Button[1];row[0]=panel.action(app.label,()->{
                if(!hidden.remove(app.id))hidden.add(app.id);selectionMark(row[0],!hidden.contains(app.id));
            });selectionMark(row[0],!hidden.contains(app.id));
        }
        panel.action("保存",()->{panel.dialog.dismiss();preferences.edit().putStringSet("hidden",hidden).apply();arrange(null);if(visible.isEmpty())settings.requestFocusFromTouch();else grid.requestFocusFromTouch();});
        panel.show();
    }
    private void selectionMark(Button button,boolean selected){
        button.setSelected(selected);button.setContentDescription(button.getText()+(selected?"，已选择":"，未选择"));
        Control control=(Control)button.getTag();
        TvAppearance.Glyph mark=selected?new TvAppearance.Glyph(TvAppearance.Glyph.CHECK,TvAppearance.TEXT).weight(.11f):null;
        if(mark!=null)mark.setBounds(0,0,px(28),px(28));button.setCompoundDrawablesRelative(null,null,mark,null);button.setCompoundDrawablePadding(px(18));
        control.mark(mark);
    }

    static boolean hasHomeDeclaration(Context context) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.getPackageName());
        return !context.getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY).isEmpty();
    }

    private boolean isDefaultHome() {
        if (Build.VERSION.SDK_INT < 29) {
            ResolveInfo match = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            return match != null && match.activityInfo != null && getPackageName().equals(match.activityInfo.packageName);
        }
        RoleManager roles = getSystemService(RoleManager.class);
        return roles != null && roles.isRoleHeld(RoleManager.ROLE_HOME);
    }

    private void requestHome() {
        if (!hasHomeDeclaration(this)) {
            failure("当前安装包未声明首页入口");
            return;
        }
        if (Build.VERSION.SDK_INT >= 29 && !isDefaultHome()) {
            RoleManager roles = getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                try { startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME_ROLE); }
                catch (ActivityNotFoundException | SecurityException unavailable) { failure("系统无法打开默认首页确认：" + unavailable.getClass().getSimpleName()); }
                return;
            }
        }
        open(new Intent(Settings.ACTION_HOME_SETTINGS));
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_HOME_ROLE && isDefaultHome()) failure("已设为默认首页");
    }

    /** Focus state for one launcher-owned control: glass fill to white, labels and glyphs to ink, a small lift. */
    private final class Control implements View.OnFocusChangeListener {
        final View view;
        final TvAppearance.Fill fill;
        final float scale;
        final List<TextView> labels=new ArrayList<>();
        final List<Integer> restColors=new ArrayList<>();
        TvAppearance.Glyph glyph,mark;
        ValueAnimator animator;
        float value;
        boolean held;
        Control(View view,TvAppearance.Fill fill,float scale){this(view,view,fill,scale);}
        /** The fill may live on a child layer so glass can draw its backdrop underneath it. */
        Control(View view,View host,TvAppearance.Fill fill,float scale){
            this.view=view;this.fill=fill;this.scale=scale;host.setBackground(fill);view.setTag(this);
            if(Build.VERSION.SDK_INT>=26)view.setDefaultFocusHighlightEnabled(false);
            view.setOnFocusChangeListener(this);
        }
        Control label(TextView label){labels.add(label);restColors.add(label.getCurrentTextColor());apply(value);return this;}
        Control glyph(TvAppearance.Glyph value){glyph=value;apply(this.value);return this;}
        void mark(TvAppearance.Glyph value){mark=value;apply(this.value);}
        void hold(boolean value){held=value;run(value||view.hasFocus()?1:0);}
        @Override public void onFocusChange(View v,boolean focused){run(focused||held?1:0);}
        void run(float target){
            if(animator!=null)animator.cancel();
            if(!animations()){apply(target);return;}
            animator=ValueAnimator.ofFloat(value,target);animator.setDuration(target>value?CONTROL_MS+40:CONTROL_MS);animator.setInterpolator(ease);
            animator.addUpdateListener(a->apply((float)a.getAnimatedValue()));animator.start();
        }
        void apply(float f){
            value=f;fill.fraction(f);float s=1+(scale-1)*f;view.setScaleX(s);view.setScaleY(s);view.setTranslationZ(px(10)*f);view.invalidateOutline();
            for(int i=0;i<labels.size();i++){int rest=restColors.get(i);labels.get(i).setTextColor(TvAppearance.lerp(rest,rest==TvAppearance.TEXT?TvAppearance.INK:0x991d1d1f,f));}
            int ink=TvAppearance.lerp(TvAppearance.TEXT,TvAppearance.INK,f);
            if(glyph!=null)glyph.color(ink);if(mark!=null)mark.color(ink);
            if(view instanceof TextView&&labels.isEmpty())((TextView)view).setTextColor(ink);
            if(view instanceof EditText)((EditText)view).setHintTextColor(TvAppearance.lerp(TvAppearance.MUTED,0x801d1d1f,f));
        }
    }
    private Button row(String label,int glyphKind,Runnable action){
        Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        button.setTypeface(TvAppearance.face(500));button.setTextSize(TypedValue.COMPLEX_UNIT_SP,BODY*unit/getResources().getDisplayMetrics().density);
        button.setStateListAnimator(null);button.setBackgroundTintList(null);button.setMinHeight(px(76));button.setMinimumHeight(px(76));
        button.setPadding(px(28),px(16),px(28),px(16));button.setTextColor(TvAppearance.TEXT);
        Control control=new Control(button,new TvAppearance.Fill(TvAppearance.REST,px(20)),1.03f);
        if(glyphKind!=0){TvAppearance.Glyph glyph=new TvAppearance.Glyph(glyphKind,TvAppearance.TEXT).weight(.085f);glyph.setBounds(0,0,px(32),px(32));
            button.setCompoundDrawablesRelative(null,null,glyph,null);button.setCompoundDrawablePadding(px(18));control.glyph(glyph);}
        button.setOnClickListener(v->action.run());return button;
    }
    private Button capsule(String label,Runnable action){
        Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setGravity(Gravity.CENTER);
        button.setTypeface(TvAppearance.face(500));button.setTextSize(TypedValue.COMPLEX_UNIT_SP,BODY*unit/getResources().getDisplayMetrics().density);
        button.setStateListAnimator(null);button.setBackgroundTintList(null);button.setMinWidth(px(260));button.setMinimumWidth(px(260));button.setMinHeight(0);button.setMinimumHeight(0);
        button.setPadding(px(40),0,px(40),0);button.setTextColor(TvAppearance.TEXT);
        new Control(button,new TvAppearance.Fill(0x38ffffff,-1),1.06f);button.setOnClickListener(v->action.run());return button;
    }

    /** Owns one launcher panel window: placement, enter/leave motion, the shared scrim and inline notes. */
    private final class Panel {
        static final int CENTER=0,ANCHOR=1,CONTROLS=2;
        final PanelDialog dialog;
        final FrameLayout frame;
        final ViewGroup surface;
        final LinearLayout content;
        final List<TvAppearance.Glass> glass=new ArrayList<>();
        final int panelWidth,placement,margin;
        final TextView note;
        Rect anchor;
        View first;
        Panel(String title,String description,int w,int placement){
            this.placement=placement;panelWidth=w;margin=px(28);
            dialog=new PanelDialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            frame=new FrameLayout(HomeActivity.this);frame.setPadding(margin,margin,margin,margin);frame.setClipChildren(false);frame.setClipToPadding(false);
            content=new LinearLayout(HomeActivity.this);content.setOrientation(LinearLayout.VERTICAL);content.setClipChildren(false);content.setClipToPadding(false);
            if(placement==CONTROLS){
                surface=content;
            }else{
                TvAppearance.Glass panelGlass=new TvAppearance.Glass(HomeActivity.this,wallpaper,px(placement==ANCHOR?34:44),px(1.4f),TvAppearance.REGULAR);glass.add(panelGlass);
                ScrollView scroll=new ScrollView(HomeActivity.this);scroll.setVerticalScrollBarEnabled(false);scroll.setClipChildren(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
                content.setPadding(px(34),px(placement==ANCHOR?26:36),px(34),px(placement==ANCHOR?22:30));scroll.addView(content);panelGlass.addView(scroll,new FrameLayout.LayoutParams(-1,-2));
                surface=panelGlass;
                TextView heading=text(title,placement==ANCHOR?BODY:HEADLINE,TvAppearance.TEXT,500);heading.setSingleLine(placement==ANCHOR);heading.setEllipsize(TextUtils.TruncateAt.END);
                content.addView(heading);
                if(description!=null&&!description.isEmpty()){
                    TextView detail=text(description,CAPTION1,TvAppearance.MUTED,400);detail.setPadding(0,px(12),0,px(26));content.addView(detail);
                }else heading.setPadding(0,0,0,px(placement==ANCHOR?16:24));
            }
            frame.addView(surface,new FrameLayout.LayoutParams(w,-2));
            note=text("",CAPTION1,TvAppearance.TEXT,500);note.setPadding(px(8),px(14),px(8),px(4));note.setVisibility(View.GONE);
            dialog.setContentView(frame);Window window=dialog.getWindow();window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.setWindowAnimations(0);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        Button action(String title,Runnable action){return action(title,0,action);}
        Button action(String title,int glyph,Runnable action){
            Button button=row(title,glyph,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=px(12);content.addView(button,p);if(first==null)first=button;return button;
        }
        void note(String message){
            if(note.getParent()==null)content.addView(note,new LinearLayout.LayoutParams(-1,-2));
            note.setText(message);note.setVisibility(View.VISIBLE);resize();
        }
        void refreshGlass(){for(TvAppearance.Glass g:glass)g.invalidate();}
        private void resize(){
            if(!dialog.isShowing())return;
            int max=height-px(placement==CONTROLS?126:120)+margin*2;
            frame.measure(View.MeasureSpec.makeMeasureSpec(panelWidth+margin*2,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(max,View.MeasureSpec.AT_MOST));
            int h=Math.min(max,frame.getMeasuredHeight());
            Window window=dialog.getWindow();WindowManager.LayoutParams wp=window.getAttributes();wp.width=panelWidth+margin*2;wp.height=h;
            if(placement==CONTROLS){wp.gravity=Gravity.TOP|Gravity.RIGHT;wp.x=px(56)-margin;wp.y=px(126)-margin;}
            else if(placement==ANCHOR&&anchor!=null){
                wp.gravity=Gravity.TOP|Gravity.LEFT;wp.x=Math.max(px(40)-margin,Math.min(width-px(40)-wp.width+margin,anchor.centerX()-wp.width/2));
                int above=anchor.top-px(20)-h+margin,below=anchor.bottom+px(20)-margin;
                wp.y=above>=px(30)?above:below+h<=height-px(30)?below:Math.max(0,(height-h)/2);
            }else{wp.gravity=Gravity.CENTER;wp.x=0;wp.y=0;}
            window.setAttributes(wp);
        }
        void show(){
            openPanels.add(this);holdScrim(placement==CONTROLS?.22f:placement==ANCHOR?.18f:.38f);
            dialog.show();resize();
            float dx=placement==CONTROLS?px(40):0,dy=placement==CENTER?px(22):placement==ANCHOR?px(12):0;
            if(animations()){surface.setAlpha(0);surface.setTranslationX(dx);surface.setTranslationY(dy);
                surface.animate().alpha(1).translationX(0).translationY(0).setDuration(placement==CONTROLS?300:260).setInterpolator(ease).setUpdateListener(a->refreshGlass()).start();}
        }
        void closing(){releaseScrim();}
    }
    private final class PanelDialog extends Dialog {
        final Panel owner;
        boolean leaving;
        PanelDialog(Panel owner){super(HomeActivity.this);this.owner=owner;}
        @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus&&!leaving&&owner.first!=null)owner.first.requestFocusFromTouch();}
        /** Showing and not leaving; a leaving panel no longer counts as open. */
        boolean open(){return isShowing()&&!leaving;}
        /** Keys already queued to a leaving panel belong to the home screen it is revealing. */
        @Override public boolean dispatchKeyEvent(KeyEvent event){return leaving?!destroyed&&HomeActivity.this.dispatchKeyEvent(event):super.dispatchKeyEvent(event);}
        @Override public void dismiss(){
            if(leaving)return;leaving=true;owner.closing();
            // Return focus and keys to whatever opens next while this window animates out.
            if(getWindow()!=null)getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            if(!animations()||!isShowing()||destroyed){finishDismiss();return;}
            float dx=owner.placement==Panel.CONTROLS?px(40):0,dy=owner.placement==Panel.CENTER?px(16):owner.placement==Panel.ANCHOR?px(10):0;
            owner.surface.animate().cancel();
            owner.surface.animate().alpha(0).translationX(dx).translationY(dy).setDuration(180).setInterpolator(leave).setUpdateListener(a->owner.refreshGlass()).withEndAction(this::finishDismiss).start();
        }
        void dismissNow(){if(!leaving){leaving=true;owner.closing();}finishDismiss();}
        private void finishDismiss(){openPanels.remove(owner);try{super.dismiss();}catch(IllegalArgumentException detached){/* window already removed with the activity */}}
    }
    private void holdScrim(float level){
        scrimUsers++;scrim.animate().cancel();
        if(animations())scrim.animate().alpha(level).setDuration(260).setInterpolator(ease).start();else scrim.setAlpha(level);
    }
    private void releaseScrim(){
        scrimUsers=Math.max(0,scrimUsers-1);
        ui.postDelayed(()->{if(scrimUsers==0&&!destroyed){scrim.animate().cancel();if(animations())scrim.animate().alpha(0).setDuration(220).setInterpolator(ease).start();else scrim.setAlpha(0);}},60);
    }

    /** tvOS Control Center: floating glass modules under the clock, no enclosing sheet. */
    private void showSettings(){
        if(controls!=null&&controls.open())return;
        Panel panel=new Panel("控制中心",null,px(540),Panel.CONTROLS);controls=panel.dialog;
        boolean home=isDefaultHome();
        LinearLayout top=moduleRow(false);
        View big=module(panel,0,TvAppearance.Glyph.GEAR,"电视设置",null,()->open(DeviceNavigation.settings()));
        top.addView(big,new LinearLayout.LayoutParams(px(260),px(260)));
        LinearLayout pair=moduleRow(true);
        pair.addView(module(panel,1,TvAppearance.Glyph.PHOTO,"背景来源",BackgroundRepository.label(this),this::backgroundSettings),new LinearLayout.LayoutParams(px(260),px(120)));
        LinearLayout.LayoutParams lower=new LinearLayout.LayoutParams(px(260),px(120));lower.topMargin=px(20);
        pair.addView(module(panel,1,TvAppearance.Glyph.CYCLE,"更新背景","立即获取",()->refreshBackground(true)),lower);
        LinearLayout.LayoutParams pairParams=new LinearLayout.LayoutParams(-2,-2);pairParams.leftMargin=px(20);top.addView(pair,pairParams);
        panel.content.addView(top);
        LinearLayout middle=moduleRow(false);
        middle.addView(module(panel,1,TvAppearance.Glyph.GRID,"显示应用",visible.size()+" 个应用",this::manageApps),new LinearLayout.LayoutParams(px(260),px(120)));
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(px(260),px(120));right.leftMargin=px(20);
        middle.addView(module(panel,1,TvAppearance.Glyph.DOWNLOAD,"检查更新","客厅桌面 "+UpdateFlow.version(this),this::showUpdates),right);
        LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-2,-2);rowParams.topMargin=px(20);panel.content.addView(middle,rowParams);
        LinearLayout round=moduleRow(false);
        String[] names={"刷新应用","原首页","默认首页","关于恢复"},details={"重新读取已安装应用","打开之前的系统首页",home?"当前默认首页，可更换":"尚未设为默认首页","版本信息与首页恢复"};
        int[] glyphs={TvAppearance.Glyph.RELOAD,TvAppearance.Glyph.HOUSE,TvAppearance.Glyph.HOUSE_CHECK,TvAppearance.Glyph.LIFEBUOY};
        Runnable[] actions={this::loadApps,()->open(DeviceNavigation.originalHome(this)),this::requestHome,this::showAbout};
        for(int i=0;i<names.length;i++){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(px(120),-2);if(i>0)p.leftMargin=px(20);round.addView(module(panel,2,glyphs[i],names[i],details[i],actions[i]),p);}
        LinearLayout.LayoutParams roundParams=new LinearLayout.LayoutParams(-2,-2);roundParams.topMargin=px(20);panel.content.addView(round,roundParams);
        panel.first=big;gearControl.hold(true);panel.dialog.setOnDismissListener(d->gearControl.hold(false));
        panel.show();
    }
    private void showAbout(){
        Panel panel=new Panel("关于与恢复","客厅桌面 "+UpdateFlow.version(this)+"\n公开参考版 · 小米兼容性需要本机验证",px(800),Panel.CENTER);
        panel.action("打开电视设置",()->{panel.dialog.dismiss();open(DeviceNavigation.settings());});
        panel.action("打开原首页",()->{panel.dialog.dismiss();open(DeviceNavigation.originalHome(this));});
        panel.action("更换默认首页",()->{panel.dialog.dismiss();requestHome();});
        panel.action("返回",()->panel.dialog.dismiss());panel.show();
    }
    /** Module rows must not clip: a focused module scales past its row, and a clipped row cuts its rounded ends. */
    private LinearLayout moduleRow(boolean vertical){LinearLayout l=new LinearLayout(this);l.setOrientation(vertical?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);l.setClipChildren(false);l.setClipToPadding(false);return l;}
    /** One Control Center module: 0 large square, 1 capsule with value, 2 round button labelled underneath. */
    private View module(Panel panel,int shape,int glyphKind,String title,String value,Runnable action){
        TvAppearance.Glass glass=new TvAppearance.Glass(this,wallpaper,shape==0?px(52):-1,px(1.4f),TvAppearance.CLEAR).clipContent(false);panel.glass.add(glass);
        glass.setFocusable(true);glass.setClickable(true);glass.setContentDescription(value==null?title:title+"，"+value);
        View fillLayer=new View(this);TvAppearance.Fill fill=new TvAppearance.Fill(0x00ffffff,shape==0?px(52):-1);
        glass.addView(fillLayer,new FrameLayout.LayoutParams(-1,-1));
        TvAppearance.Glyph glyph=new TvAppearance.Glyph(glyphKind,TvAppearance.TEXT).weight(shape==0?.06f:.085f);
        View mark=new View(this);mark.setBackground(glyph);
        Control control=new Control(glass,fillLayer,fill,shape==0?1.05f:1.08f).glyph(glyph);
        glass.setOnClickListener(v->{panel.dialog.dismiss();action.run();});
        if(shape==0){
            LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.CENTER);
            column.addView(mark,new LinearLayout.LayoutParams(px(92),px(92)));
            TextView label=text(title,CAPTION1,TvAppearance.TEXT,500);label.setGravity(Gravity.CENTER);label.setPadding(0,px(18),0,0);column.addView(label);control.label(label);
            glass.addView(column,new FrameLayout.LayoutParams(-1,-1));return glass;
        }
        if(shape==1){
            LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);line.setPadding(px(26),0,px(22),0);
            line.addView(mark,new LinearLayout.LayoutParams(px(40),px(40)));
            LinearLayout texts=new LinearLayout(this);texts.setOrientation(LinearLayout.VERTICAL);
            TextView label=text(title,CAPTION1,TvAppearance.TEXT,500);label.setSingleLine(true);label.setEllipsize(TextUtils.TruncateAt.END);texts.addView(label);control.label(label);
            if(value!=null){TextView detail=text(value,CAPTION2,TvAppearance.MUTED,400);detail.setSingleLine(true);detail.setEllipsize(TextUtils.TruncateAt.END);texts.addView(detail);control.label(detail);}
            LinearLayout.LayoutParams textParams=new LinearLayout.LayoutParams(0,-2,1);textParams.leftMargin=px(16);line.addView(texts,textParams);
            glass.addView(line,new FrameLayout.LayoutParams(-1,-1));return glass;
        }
        FrameLayout.LayoutParams markParams=new FrameLayout.LayoutParams(px(48),px(48),Gravity.CENTER);glass.addView(mark,markParams);
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.CENTER_HORIZONTAL);column.setClipChildren(false);
        column.addView(glass,new LinearLayout.LayoutParams(px(120),px(120)));
        TextView label=text(title,CAPTION2,TvAppearance.TEXT,500);label.setGravity(Gravity.CENTER);label.setSingleLine(true);label.setEllipsize(TextUtils.TruncateAt.END);legible(label);
        LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(px(140),-2);labelParams.topMargin=px(10);column.addView(label,labelParams);
        return column;
    }
    private void showUpdates(){
        if(updateScreen!=null&&updateScreen.panel.dialog.open())return;
        updateScreen=new UpdateScreen();updateScreen.start();
    }
    private final class UpdateScreen {
        final Panel panel=new Panel("检查更新","客厅桌面 "+UpdateFlow.version(HomeActivity.this)+" · 电脑 "+UpdateFlow.host(HomeActivity.this),px(860),Panel.CENTER);
        final TextView message=text("正在连接电脑……",BODY,TvAppearance.TEXT,400);
        final UpdateFlow.Job job=new UpdateFlow.Job(HomeActivity.this);
        final Button primary,close;
        volatile boolean closed;
        UpdateFlow.Release release;
        final SharedPreferences.OnSharedPreferenceChangeListener listener=(prefs,key)->{
            if("message".equals(key))ui.post(()->{if(!closed&&!destroyed){message.setText(UpdateFlow.status(HomeActivity.this));panel.resize();}});
        };
        UpdateScreen(){
            message.setPadding(0,0,0,px(28));panel.content.addView(message);
            primary=panel.action("正在检查……",TvAppearance.Glyph.DOWNLOAD,this::download);primary.setEnabled(false);
            panel.action("修改电脑 IP",TvAppearance.Glyph.COMPUTER,()->{panel.dialog.dismiss();updateComputer();});
            close=panel.action("返回",()->panel.dialog.dismiss());panel.first=close;
            panel.dialog.setOnDismissListener(d->close());
            UpdateFlow.prefs(HomeActivity.this).registerOnSharedPreferenceChangeListener(listener);
        }
        void start(){
            panel.show();updates.execute(()->{
                try{
                    UpdateFlow.Release found=job.check();long own=UpdateFlow.code(UpdateFlow.installed(HomeActivity.this));
                    ui.post(()->{if(closed||destroyed)return;release=found;
                        if(found.versionCode<=own){message.setText("已是最新版本 · "+UpdateFlow.version(HomeActivity.this));primary.setVisibility(View.GONE);}
                        else{message.setText("发现新版本 "+found.version+"\n"+String.format(java.util.Locale.CHINA,"%.1f MB",found.bytes/1048576.0)+(found.notes.isEmpty()?"":"\n\n"+found.notes));
                            primary.setText("下载并安装 · "+found.version);primary.setEnabled(true);panel.first=primary;primary.requestFocusFromTouch();}
                        panel.resize();
                    });
                }catch(Exception failure){showError("无法检查更新，请确认电脑已开机、服务已启动，且与电视连接同一网络。");}
            });
        }
        void showError(String problem){ui.post(()->{if(closed||destroyed)return;message.setText(problem);primary.setText("重试");primary.setEnabled(true);primary.setVisibility(View.VISIBLE);
            primary.setOnClickListener(v->{panel.dialog.dismiss();showUpdates();});panel.first=primary;primary.requestFocusFromTouch();panel.resize();});}
        void download(){
            if(release==null)return;
            if(Build.VERSION.SDK_INT>=26&&!getPackageManager().canRequestPackageInstalls()){
                message.setText("请在系统设置中允许客厅桌面安装更新，然后返回点击下载并安装。");panel.resize();
                open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));return;
            }
            primary.setEnabled(false);primary.setText("正在下载……");close.requestFocusFromTouch();
            updates.execute(()->{
                java.io.File file=null;
                try{
                    file=job.download(release,progress->ui.post(()->{if(!closed&&!destroyed)message.setText(progress);}));
                    job.active();
                    ui.post(()->{if(!closed&&!destroyed){message.setText("校验通过，正在准备系统安装确认……");primary.setText("正在准备安装……");}});
                    UpdateFlow.install(HomeActivity.this,file,release,job);
                    ui.post(()->{if(!closed&&!destroyed){message.setText(UpdateFlow.status(HomeActivity.this));primary.setVisibility(View.GONE);panel.resize();}});
                }catch(Exception failure){showError(failure instanceof java.io.IOException?failure.getMessage():"更新未能完成，请重试。");}
                finally{if(file!=null)file.delete();}
            });
        }
        void close(){closed=true;job.close();UpdateFlow.prefs(HomeActivity.this).unregisterOnSharedPreferenceChangeListener(listener);}
    }
    private EditText field(String value,String hint,int inputType){
        EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(inputType);
        input.setTypeface(TvAppearance.face(400));input.setTextSize(TypedValue.COMPLEX_UNIT_SP,BODY*unit/getResources().getDisplayMetrics().density);input.setTextColor(TvAppearance.TEXT);
        input.setHintTextColor(TvAppearance.MUTED);input.setHint(hint);input.setPadding(px(26),px(18),px(26),px(18));input.setText(value);input.setSelection(input.getText().length());
        new Control(input,new TvAppearance.Fill(TvAppearance.FIELD,px(20)),1.02f);return input;
    }
    private void updateComputer(){
        Panel panel=new Panel("电脑地址","填写提供更新包的电脑的局域网 IP。",px(860),Panel.CENTER);
        EditText input=field(UpdateFlow.host(this),"192.168.x.x",android.text.InputType.TYPE_CLASS_PHONE);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=px(24);panel.content.addView(input,p);
        panel.action("保存并检查",()->{try{UpdateFlow.setHost(this,input.getText().toString().trim());panel.dialog.dismiss();showUpdates();}catch(java.io.IOException problem){failure(problem.getMessage());}});
        panel.action("返回",()->{panel.dialog.dismiss();showUpdates();});panel.show();
    }
    private void backgroundSettings(){
        Panel panel=new Panel("背景来源",BackgroundRepository.status()+(scene!=null&&!scene.credit.isEmpty()?"\n"+scene.credit:""),px(820),Panel.CENTER);
        String mode=BackgroundRepository.mode(this);
        for(int i=0;i<BackgroundRepository.MODES.length;i++){
            final String source=BackgroundRepository.MODES[i];Button button=panel.action(BackgroundRepository.LABELS[i],()->{
                panel.dialog.dismiss();if("custom".equals(source)){customBackground();return;}
                BackgroundRepository.prefs(this).edit().putString("source",source).apply();refreshBackground(false);
            });selectionMark(button,mode.equals(source));
            if(mode.equals(source))panel.first=button;
        }
        panel.action("接口状态",TvAppearance.Glyph.INFO,()->{panel.dialog.dismiss();Panel detail=new Panel("背景更新状态",BackgroundRepository.status()+"\n\n最近观看来自本机 TCL 桌面提供的数据入口。当前按钮打开视频应用；指定集数续播仍在核对。\n\n系统当前壁纸尚无已确认的读取入口。",px(840),Panel.CENTER);detail.action("返回",()->{detail.dialog.dismiss();backgroundSettings();});detail.show();});
        panel.action("返回",()->panel.dialog.dismiss());panel.show();
    }
    private void customBackground(){
        Panel panel=new Panel("自定义背景","填写 HTTP(S) 图片地址，或返回 image_url、title、subtitle 的 JSON 接口。",px(900),Panel.CENTER);
        EditText input=field(BackgroundRepository.prefs(this).getString("custom_url",""),"https://…",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,-2);ep.bottomMargin=px(24);panel.content.addView(input,ep);
        panel.action("保存并更新",()->{String value=input.getText().toString().trim();Uri uri=Uri.parse(value);
            if(!("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null){failure("请输入有效的 HTTP(S) 地址");input.requestFocus();return;}
            panel.dialog.dismiss();BackgroundRepository.prefs(this).edit().putString("custom_url",value).putString("source","custom").apply();refreshBackground(true);
        });panel.action("取消",()->panel.dialog.dismiss());panel.show();
    }
    private void focusGrid(){
        browsing=false;backgroundTarget.setFocusable(false);updateBackgroundCaption();
        if(visible.isEmpty()){settings.requestFocus();if(shelf)setShelf(false);return;}
        grid.requestFocus();grid.setSelection(selectedPosition);
        if(shelf)setShelf(false);
    }
    private void focusInitial(){
        if(firstLoad||!hasWindowFocus())return;
        if(browsing){backgroundTarget.requestFocusFromTouch();return;}
        if(visible.isEmpty())settings.requestFocusFromTouch();else{grid.requestFocusFromTouch();grid.setSelection(selectedPosition);}
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);if(grid!=null&&grid.getSelectedItemPosition()<0)focusInitial();}
    @Override public boolean onKeyDown(int key,KeyEvent event){
        if(key==KeyEvent.KEYCODE_MENU&&event.getRepeatCount()==0){if(grid.hasFocus()&&!visible.isEmpty())itemMenu(focusedPosition());else showSettings();return true;}
        if(key==KeyEvent.KEYCODE_BACK&&event.getRepeatCount()==0&&expanded){
            int column=Math.min(focusedPosition()%COLUMNS,visible.size()-1);selectedPosition=Math.max(0,column);grid.setSelection(selectedPosition);follow(selectedPosition);project();return true;
        }
        if(key==KeyEvent.KEYCODE_BACK&&event.getRepeatCount()==0&&shelf){focusGrid();return true;}
        return super.onKeyDown(key,event);
    }
    @Override protected void onDestroy(){
        destroyed=true;scanGeneration++;backgroundGeneration++;ui.removeCallbacksAndMessages(null);worker.shutdownNow();backgrounds.shutdownNow();
        if(updateScreen!=null)updateScreen.close();updates.shutdownNow();
        for(Panel panel:new ArrayList<>(openPanels))panel.dialog.dismissNow();
        super.onDestroy();
    }
}
