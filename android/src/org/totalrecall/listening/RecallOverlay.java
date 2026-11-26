package org.totalrecall.listening;

import android.app.KeyguardManager;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.Locale;

/** Session-scoped floating controls. Microphone acquisition always belongs to RecallService. */
public final class RecallOverlay {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String PREFS = "selective-floating-controls";
    private static Controller active;

    private RecallOverlay() {}
    static SharedPreferences options(Context c) { return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE); }
    static boolean enabled(Context c) { return options(c).getBoolean("enabled",false); }

    public static void sessionStarted(Context c) {
        Context app=c.getApplicationContext();
        MAIN.post(()->{ if(RecallService.isListening() && enabled(app)) show(app,false); });
    }
    public static void sessionEnded(Context c) { MAIN.post(RecallOverlay::stop); }

    static void idlePreview(Context c) {
        Context app=c.getApplicationContext();
        MAIN.post(()->show(app,!RecallService.isListening()));
    }
    static void choice(Context c,boolean value) {
        options(c).edit().putBoolean("enabled",value).apply();
        if(value && RecallService.isListening()) sessionStarted(c);
        else if(!value) MAIN.post(RecallOverlay::stop);
    }
    static void changeCaptions(Context c,boolean value) {
        options(c).edit().putBoolean("captions",value).apply();
        MAIN.post(()->{ if(active!=null && active.visible) { active.rebuild(); } });
    }
    public static void reset(Context c) {
        options(c).edit().clear().apply();
        MAIN.post(RecallOverlay::stop);
    }
    static boolean visible() { return active!=null && active.visible; }
    static boolean expanded() { return active!=null && active.expanded; }

    private static void show(Context c,boolean preview) {
        stop();
        if(!enabled(c)||!Settings.canDrawOverlays(c))return;
        try {
            active=new Controller(c,preview);
            active.start();
        } catch(RuntimeException e) {
            stop();
        }
    }
    private static void stop() {
        Controller controller=active;
        active=null;
        if(controller!=null)controller.finish();
    }
    private static final class Controller {
        final Context app,windowContext;
        final WindowManager wm;
        final boolean preview;
        final SharedPreferences prefs;
        final WindowManager.LayoutParams layout;
        LinearLayout root;
        TextView caption, status, detail, keep, missed, words;
        ProgressBar meter;
        boolean expanded,registered,visible,wasWaiting;
        long firstVisibleFinal,requestUntil;
        int requestedAtCount;
        String requestLabel="",lastContextLine="",liveCue="";
        int screenWidth;
        long expires;
        final BroadcastReceiver screen=new BroadcastReceiver() {
            @Override public void onReceive(Context c,Intent i){
                if(Intent.ACTION_SCREEN_OFF.equals(i.getAction())){
                    if(preview) {stop();return;}
                    detach();
                }
                tick();
            }
        };
        final Runnable pulse=this::tick;

        Controller(Context c,boolean idle) {
            app=c;preview=idle;prefs=options(c);
            if(Build.VERSION.SDK_INT>=30) {
                android.hardware.display.DisplayManager dm=c.getSystemService(android.hardware.display.DisplayManager.class);
                Display display=dm==null?null:dm.getDisplay(Display.DEFAULT_DISPLAY);
                if(display==null)throw new IllegalStateException("No active display");
                windowContext=c.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
            }else windowContext=c;
            wm=(WindowManager)windowContext.getSystemService(Context.WINDOW_SERVICE);
            expanded=prefs.getBoolean("expanded",false);
            layout=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|
                WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT);
            layout.gravity=Gravity.TOP|Gravity.LEFT;
            layout.setTitle("Total Recall floating controls");
            layout.x=prefs.getInt("x",dp(8));layout.y=prefs.getInt("y",dp(100));
            expires=SystemClock.elapsedRealtime()+120000L;
        }
        void start() {
            IntentFilter f=new IntentFilter(Intent.ACTION_SCREEN_OFF); f.addAction(Intent.ACTION_USER_PRESENT);
            if(Build.VERSION.SDK_INT>=33)app.registerReceiver(screen,f,Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(screen,f);
            registered=true;tick();
        }
        void finish() {
            MAIN.removeCallbacks(pulse);
            if(registered){registered=false;try{app.unregisterReceiver(screen);}catch(RuntimeException ignored){}}
            detach();
        }
        void detach() {
            if(caption!=null)caption.setText("");
            if(visible && root!=null)try{wm.removeViewImmediate(root);}catch(RuntimeException ignored){}
            lastContextLine="";liveCue="";root=null;caption=null;status=null;detail=null;meter=null;keep=null;missed=null;words=null;visible=false;
        }
        int dp(int value){return (int)(value*app.getResources().getDisplayMetrics().density+0.5f);}
        int[] limits() {
            Point point=new Point();
            wm.getDefaultDisplay().getSize(point);
            int w=root==null?dp(140):Math.max(dp(60),root.getMeasuredWidth());
            int h=root==null?dp(48):Math.max(dp(40),root.getMeasuredHeight());
            return new int[]{Math.max(0,point.x-w),Math.max(0,point.y-h-dp(20))};
        }
        void position(int x,int y) {
            int[] bounds=limits();
            int newX=Math.max(0,Math.min(x,bounds[0])),newY=Math.max(0,Math.min(y,bounds[1]));
            if(layout.x==newX && layout.y==newY)return;
            layout.x=newX;layout.y=newY;
            if(visible)try{wm.updateViewLayout(root,layout);}catch(RuntimeException lost){stop();}
        }
        void preserveBoundary() {
            LiveCapture.Snapshot s=LiveCapture.snapshot();
            firstVisibleFinal=s.finalSequence+(s.partial.isEmpty()?0:1);
            if(caption!=null)caption.setText("");
        }
        void rebuild(){detach();build();}
        void showText(TextView view,String value){
            if(view==null||TextUtils.equals(view.getText(),value))return;
            view.setText(value);
            if(view==caption)view.post(()->{
                android.text.Layout lines=view.getLayout();
                if(view==caption && lines!=null)view.scrollTo(0,Math.max(0,lines.getHeight()-view.getHeight()+view.getCompoundPaddingTop()+view.getCompoundPaddingBottom()));
            });
        }
        void paint(LiveCapture.Snapshot s){
            if(s.waiting!=wasWaiting){preserveBoundary();wasWaiting=s.waiting;requestUntil=0;}
            boolean ready=s.receiving;
            if(keep!=null)keep.setEnabled(ready);
            if(missed!=null)missed.setEnabled(ready);
            int level=Math.round(s.level*100);
            meter.setProgress(s.waiting?0:level);
            String headline=s.waiting?"Waiting for microphone":ready?"Listening":s.state;
            if(s.savedCount>requestedAtCount)requestUntil=0;
            if(SystemClock.elapsedRealtime()<requestUntil)headline=requestLabel;
            String elapsed=String.format(Locale.ROOT,"%d:%02d",s.sessionSeconds/60,s.sessionSeconds%60);
            showText(status,headline+"  ·  "+elapsed);
            String visibleWords=caption==null||s.waiting?"":s.overlayWords(firstVisibleFinal);
            if(!visibleWords.equals(lastContextLine)){
                lastContextLine=visibleWords;liveCue="";
                if(!visibleWords.isEmpty())try{
                    Situation context=new Situation(app);context.add(visibleWords,"");liveCue=context.cue();
                }catch(java.io.IOException ignored){}
            }
            if(detail!=null){
                String state=s.waiting?s.state:ready?"Mic level "+level+"%  ·  "+s.savedCount+" saved this session":s.state;
                if(!s.lastSave.isEmpty()&&!s.waiting)state+="\nLast saved: "+s.lastSave;
                if(!liveCue.isEmpty())state+="\nWords suggest · "+liveCue;
                showText(detail,state);
            }
            if(caption!=null){
                String line=visibleWords;
                showText(caption,s.waiting?"Words paused while the mic is busy":line.isEmpty()?"Waiting for words…":line);
            }
        }
        void tick() {
            MAIN.removeCallbacks(pulse);
            if(active!=this)return;
            if(!enabled(app)||!Settings.canDrawOverlays(app)||(!preview && !RecallService.isListening())||
               (preview && SystemClock.elapsedRealtime()>=expires)) {stop();return;}
            PowerManager pm=app.getSystemService(PowerManager.class);
            KeyguardManager km=app.getSystemService(KeyguardManager.class);
            if(pm==null||km==null||!pm.isInteractive()||km.isKeyguardLocked()){
                if(visible)detach();
                MAIN.postDelayed(pulse,1200);return;
            }
            if(!visible)build();
            else if(screenWidth!=windowContext.getResources().getDisplayMetrics().widthPixels)rebuild();
            if(active!=this)return;
            if(!preview)paint(LiveCapture.snapshot());
            position(layout.x,layout.y);
            MAIN.postDelayed(pulse,preview?1500:300);
        }
        void build(){
            root=new DragSurface();root.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(21,43,45));bg.setCornerRadius(dp(14));
            root.setBackground(bg);root.setPadding(dp(6),dp(4),dp(6),dp(4));root.setElevation(dp(6));root.setFilterTouchesWhenObscured(true);
            status=label(preview?"Preview · microphone off":"Opening microphone",13);
            status.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            if(!preview){
                meter=new ProgressBar(windowContext,null,android.R.attr.progressBarStyleHorizontal);
                meter.setMax(100);meter.setContentDescription("Live microphone level");
                meter.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff78e0c4));
                root.addView(meter,new LinearLayout.LayoutParams(-1,dp(5)));
            }
            LinearLayout actions=row(root);
            if(preview)control(actions,"Start","Start listening",v->openApp(true));
            else keep=control(actions,expanded?"Keep last 1m":"Keep 1m","Keep the last minute",v->request(RecallService.KEEP,"Keep requested"));
            control(actions,"Open app","Open Total Recall",v->openApp(false));
            control(actions,expanded?"Shrink":"Expand",expanded?"Shrink overlay":"Expand overlay",v->resize());
            control(actions,"×","Hide floating controls",v->stop());
            if(expanded && !preview){
                detail=label("",13);
                LinearLayout shortcuts=row(root);
                missed=control(shortcuts,"Missed one","Mark a missed moment",v->request(RecallService.MISSED,"Missed moment requested"));
                boolean showWords=prefs.getBoolean("captions",false);
                words=control(shortcuts,showWords?"Hide words":"Live words",showWords?"Hide live words":"Show live words",v->changeCaptions(app,!showWords));
                control(shortcuts,"Archive","Open archive and sync",v->openScreen(ArchiveSyncActivity.class,false));
                control(shortcuts,"Pause","Pause and clear temporary audio",v->{command(RecallService.PAUSE);});
                if(showWords){
                    caption=label("Waiting for words…",15);caption.setMaxLines(3);
                    caption.setGravity(Gravity.BOTTOM);
                }
            }
            screenWidth=windowContext.getResources().getDisplayMetrics().widthPixels;
            layout.width=Math.min(dp(expanded?326:266),Math.max(dp(100),screenWidth-dp(12)));
            preserveBoundary();
            try {wm.addView(root,layout);visible=true;}
            catch(RuntimeException e){stop();}
        }
        TextView label(String value,int size){
            TextView t=new TextView(windowContext);t.setText(value);t.setTextSize(size);t.setTextColor(Color.WHITE);
            t.setPadding(dp(7),dp(2),dp(7),dp(2));root.addView(t);return t;
        }
        void resize(){
            expanded=!expanded;prefs.edit().putBoolean("expanded",expanded).apply();
            rebuild();
        }
        void openApp(boolean start){openScreen(RecallListenActivity.class,start);}
        void openScreen(Class<?> destination,boolean start){
            Intent i=new Intent(app,destination).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if(start)i.putExtra("overlayStart",true);
            try{app.startActivity(i);if(start)stop();}
            catch(RuntimeException e){Toast.makeText(app,"Could not open Total Recall",Toast.LENGTH_SHORT).show();}
        }
        void request(String action,String message){
            if(!LiveCapture.snapshot().receiving)return;
            requestedAtCount=LiveCapture.snapshot().savedCount;
            requestLabel=message;requestUntil=SystemClock.elapsedRealtime()+1800;
            command(action);
        }
        void command(String action){
            if(RecallService.isListening())try{RecallService.command(app,action);}
            catch(RuntimeException e){requestUntil=0;Toast.makeText(app,"Recorder could not accept this action",Toast.LENGTH_SHORT).show();}
        }
        LinearLayout row(LinearLayout host){
            LinearLayout row=new LinearLayout(windowContext); row.setOrientation(LinearLayout.HORIZONTAL);host.addView(row);return row;
        }
        TextView control(LinearLayout row,String label,String hint,View.OnClickListener listener){
            TextView t=new TextView(windowContext);
            t.setText(label);t.setTextSize(14);t.setTextColor(Color.WHITE);t.setGravity(Gravity.CENTER);
            t.setMinWidth(0);t.setMinHeight(dp(48));t.setPadding(dp(2),0,dp(2),0);t.setClickable(true);
            t.setContentDescription(hint);t.setFilterTouchesWhenObscured(true);t.setOnClickListener(listener);
            t.setAccessibilityDelegate(new View.AccessibilityDelegate(){
                @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
                    super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.Button");
                }});
            row.addView(t,new LinearLayout.LayoutParams(0,-2,"×".equals(label)?0.65f:1f));return t;
        }
        /** Observe the whole window before child dispatch. A drag cancels the original button press. */
        final class DragSurface extends LinearLayout {
            float downX,downY;int oldX,oldY;
            boolean moving,blocked;
            final int slop=ViewConfiguration.get(windowContext).getScaledTouchSlop();
            DragSurface(){super(windowContext);}
            void cancelChild(MotionEvent e){
                MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);cancel.recycle();
            }
            @Override public boolean dispatchTouchEvent(MotionEvent e){
                int action=e.getActionMasked();
                if(!onFilterTouchEventForSecurity(e)){cancelChild(e);blocked=true;return true;}
                if(action==MotionEvent.ACTION_DOWN){
                    moving=false;blocked=false;downX=e.getRawX();downY=e.getRawY();oldX=layout.x;oldY=layout.y;
                }
                if(action==MotionEvent.ACTION_POINTER_DOWN){cancelChild(e);blocked=true;}
                if(blocked)return true;
                float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
                if(action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP){
                    if(!moving && Math.hypot(dx,dy)>slop){cancelChild(e);moving=true;}
                    if(moving){
                        position(oldX+Math.round(dx),oldY+Math.round(dy));
                        if(action==MotionEvent.ACTION_UP)prefs.edit().putInt("x",layout.x).putInt("y",layout.y).apply();
                        return true;
                    }
                }
                super.dispatchTouchEvent(e);return true;
            }
        }
    }
}
