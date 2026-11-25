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
import java.util.List;

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
        MAIN.post(()->{ if(active!=null && active.visible) { active.detach(); active.build(); } });
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
        TextView caption, status, handle;
        ProgressBar meter;
        boolean expanded,registered,visible,skipToNextFinal,wasWaiting;
        TextView keep;
        String ignoredPartial="";
        String lastFinal="";
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
            root=null;caption=null;status=null;meter=null;handle=null;keep=null;visible=false;
            lastFinal="";skipToNextFinal=true;
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
            if(caption!=null)caption.setText("");
            LiveCapture.Snapshot s=LiveCapture.snapshot();
            lastFinal=latest(s.finals);ignoredPartial=s.partial==null?"":s.partial;skipToNextFinal=!ignoredPartial.isEmpty();
        }
        String latest(List<String> finals){return finals==null||finals.isEmpty()?"":finals.get(finals.size()-1);}
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
            if(active!=this)return;
            if(!preview){
                LiveCapture.Snapshot s=LiveCapture.snapshot();
                boolean waiting=s.waiting;
                if(waiting) {
                    if(!wasWaiting)preserveBoundary();
                    if(caption!=null)caption.setText("");
                    if(handle!=null)handle.setText(expanded?"Waiting for mic":"Wait");
                    if(keep!=null)keep.setEnabled(false);
                }else{
                    if(wasWaiting)preserveBoundary();
                    if(handle!=null)handle.setText("Recall");
                    if(keep!=null)keep.setEnabled(true);
                }
                wasWaiting=waiting;
                if(meter!=null)meter.setProgress(Math.max(0,Math.min(100,Math.round(100*s.level))));
                if(!waiting && caption!=null && prefs.getBoolean("captions",false)) {
                    String finalLine=latest(s.finals);
                    boolean newFinal = !finalLine.isEmpty() && !finalLine.equals(lastFinal);
                    if(newFinal) {
                        lastFinal=finalLine;
                        if(skipToNextFinal){skipToNextFinal=false;ignoredPartial="";caption.setText("");}
                        else ignoredPartial=null;
                    }
                    if(!skipToNextFinal){
                        String line=s.partial==null || s.partial.isEmpty()?(ignoredPartial==null?finalLine:""):s.partial;
                        caption.setText(line);
                    }
                }
            }
            position(layout.x,layout.y);
            MAIN.postDelayed(pulse,preview?1500:300);
        }
        void build(){
            root=new LinearLayout(windowContext);root.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(21,43,45));bg.setCornerRadius(dp(14));
            root.setBackground(bg);root.setPadding(dp(6),dp(4),dp(6),dp(4));root.setElevation(dp(6));root.setFilterTouchesWhenObscured(true);
            LinearLayout row=row(root);
            handle=control(row,"Recall",preview?"Start controls. Drag to move":"Expand or collapse. Drag to move",v->resize());
            handle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            drag(handle);
            if(preview){
                control(row,"Start","Start listening",v->{
                    stop();Intent i=new Intent(app,RecallListenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("overlayStart",true);
                    try{app.startActivity(i);}catch(RuntimeException ignored){}
                });
            }else{
                keep=control(row,"Keep","Keep the last minute",v->command(RecallService.KEEP));
                control(row,"Pause","Pause and clear temporary audio",v->{command(RecallService.PAUSE);stop();});
            }
            control(row,"×","Hide floating controls",v->stop());
            if(!preview){
                meter=new ProgressBar(windowContext,null,android.R.attr.progressBarStyleHorizontal);
                meter.setMax(100);meter.setContentDescription("Live microphone level");
                root.addView(meter,new LinearLayout.LayoutParams(-1,dp(4)));
                if(expanded && prefs.getBoolean("captions",false)){
                    caption=new TextView(windowContext);caption.setTextSize(14);caption.setTextColor(Color.WHITE);caption.setMaxLines(3);
                    caption.setEllipsize(TextUtils.TruncateAt.END);caption.setPadding(dp(6),dp(2),dp(6),dp(6));root.addView(caption);
                }
            }
            int max=Math.max(dp(100),app.getResources().getDisplayMetrics().widthPixels-dp(12));
            layout.width=expanded?Math.min(dp(290),max):-2;
            preserveBoundary();
            try {wm.addView(root,layout);visible=true;}
            catch(RuntimeException e){stop();}
        }
        void resize(){
            if(!preview){
                expanded=!expanded;prefs.edit().putBoolean("expanded",expanded).apply();
                detach();build();
            }
        }
        void command(String action){
            if(RecallService.isListening())try{RecallService.command(app,action);}catch(RuntimeException ignored){}
        }
        LinearLayout row(LinearLayout host){
            LinearLayout row=new LinearLayout(windowContext); row.setOrientation(LinearLayout.HORIZONTAL);host.addView(row);return row;
        }
        TextView control(LinearLayout row,String label,String hint,View.OnClickListener listener){
            TextView t=new TextView(windowContext);
            t.setText(label);t.setTextSize(14);t.setTextColor(Color.WHITE);t.setGravity(Gravity.CENTER);
            t.setMinWidth(dp(42));t.setMinHeight(dp(44));t.setPadding(dp(7),0,dp(7),0);t.setClickable(true);
            t.setContentDescription(hint);t.setFilterTouchesWhenObscured(true);t.setOnClickListener(listener);
            t.setAccessibilityDelegate(new View.AccessibilityDelegate(){
                @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
                    super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.Button");
                }});
            row.addView(t);return t;
        }
        void drag(View v){
            v.setOnTouchListener(new View.OnTouchListener(){
                float downX,downY;int oldX,oldY;boolean moved;
                @Override public boolean onTouch(View touched,MotionEvent e){
                    switch(e.getActionMasked()){
                      case MotionEvent.ACTION_DOWN: downX=e.getRawX();downY=e.getRawY();oldX=layout.x;oldY=layout.y;moved=false;return true;
                      case MotionEvent.ACTION_MOVE:
                        float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
                        if(!moved && Math.hypot(dx,dy)>ViewConfiguration.get(app).getScaledTouchSlop())moved=true;
                        if(moved)position(oldX+Math.round(dx),oldY+Math.round(dy));return true;
                      case MotionEvent.ACTION_UP:
                        if(moved)prefs.edit().putInt("x",layout.x).putInt("y",layout.y).apply();
                        else touched.performClick();return true;
                      case MotionEvent.ACTION_CANCEL:return true;
                      default:return false;
                    }
                }
            });
        }
    }
}
