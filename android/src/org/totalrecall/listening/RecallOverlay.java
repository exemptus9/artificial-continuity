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
    private static boolean reviewing,restoreAfterReview;
    static void reviewVisible(Context c,boolean shown){
        if(shown==reviewing)return;
        reviewing=shown;
        if(shown){restoreAfterReview=active!=null;stop();return;}
        if(restoreAfterReview && enabled(c)){
            if(RecallService.isListening())show(c.getApplicationContext(),false);
            else if(RecallService.pausedAt(c)>0)show(c.getApplicationContext(),true);
        }
        restoreAfterReview=false;
    }

    static void leaveEndDialog(Context c,boolean ending){
        if(ending)restoreAfterReview=false;
        reviewVisible(c,false);
    }

    private RecallOverlay() {}
    static SharedPreferences options(Context c) { return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE); }
    static boolean enabled(Context c) { return options(c).getBoolean("enabled",false); }

    public static void sessionStarted(Context c) {
        Context app=c.getApplicationContext();
        MAIN.post(()->{ if(RecallService.isListening() && enabled(app)) show(app,false); });
    }
    public static void sessionEnded(Context c) { MAIN.post(RecallOverlay::stop); }

    static void afterService(Context c){
        Context app=c.getApplicationContext();
        MAIN.post(()->{
            if(RecallService.isListening()){stop();return;}
            if(RecallService.pausedAt(app)>0 && org.continuity.recall.ListenPolicy.allowed(app))show(app,true);
            else stop();
        });
    }
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
        if(reviewing){restoreAfterReview=true;return;}
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
        TextView caption, status, detail, keep, missed, words,takeControl;
        ProgressBar meter;
        boolean toolsOpen;
        boolean ultra,expanded,registered,visible,wasWaiting;
        long firstVisibleFinal,requestUntil;
        int requestedAtCount;
        String requestLabel="",partialSeen="",tentativeCue="";
        int screenWidth;
        long expires;
        final BroadcastReceiver screen=new BroadcastReceiver() {
            @Override public void onReceive(Context c,Intent i){
                if(Intent.ACTION_SCREEN_OFF.equals(i.getAction())){
                    if(preview && !resumePanel()) {stop();return;}
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
            expanded=prefs.getBoolean("expanded",false);ultra=!expanded && prefs.getBoolean("ultra",false);
            layout=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
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
            root=null;caption=null;status=null;detail=null;takeControl=null;meter=null;keep=null;missed=null;words=null;visible=false;
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
        void rebuild(){
            long boundary=firstVisibleFinal;detach();build();firstVisibleFinal=boundary;
        }
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
            SpokenTake.Status take=SpokenTake.status;
            if(takeControl!=null){showText(takeControl,take.recording?"Finish & edit":"Record my words");takeControl.setContentDescription(take.recording?"Finish recording and edit your take":"Record a new voice take and edit what you actually said");takeControl.setEnabled(ready || take.recording);}
            int level=Math.round(s.level*100);
            if(meter!=null)meter.setProgress(s.waiting?0:level);
            String headline=s.waiting?"Waiting for microphone":!s.event.isEmpty()?s.event:s.state.startsWith("Keeping")?"Capturing clip":ready?"Listening":s.state;
            if(s.savedCount>requestedAtCount)requestUntil=0;
            if(SystemClock.elapsedRealtime()<requestUntil)headline=requestLabel;
            String elapsed=String.format(Locale.ROOT,"%d:%02d",s.sessionSeconds/60,s.sessionSeconds%60);
            String compact=s.waiting?"Mic busy":!s.event.isEmpty()?s.event:ready?"Live "+elapsed:headline;
            showText(status,expanded?(take.recording?"Voice take · "+take.seconds+" / 60 s":headline+" · "+elapsed):ultra && ready && s.event.isEmpty()?"● "+s.words+"w · "+s.savedCount+" saved":compact);
            String visibleWords=s.waiting?"":s.overlayWords(firstVisibleFinal);
            if(detail!=null){
                String state=s.waiting?s.state:s.words+" words · "+s.flags+" flagged · "+s.savedCount+" saved";
                state=s.registration(firstVisibleFinal)+"\n"+state+"\nJournal "+(JournalStore.enabled(app)?"on":"off")+" · Full audio "+(ArchiveStore.enabled(app)?"on":"off");
                if(!s.lastSave.isEmpty())state+=" · Last save: "+s.lastSave;

                if(LiveCapture.clipping())state+=" · Mic clipping: move farther away";
                String pending=s.waiting || s.finalSequence<firstVisibleFinal?"":s.partial;
                if(!pending.equals(partialSeen)){
                    partialSeen=pending;tentativeCue="";
                    if(!pending.isEmpty())try{
                        Situation provisional=new Situation(app);provisional.add(pending,"");
                        tentativeCue=provisional.cue();
                    }catch(java.io.IOException ignored){}
                }
                state+="\n"+s.situationLines(firstVisibleFinal);
                if(!tentativeCue.isEmpty())state+="\nHearing · "+tentativeCue+" (tentative)";
                showText(detail,state);
            }
            if(caption!=null){
                String line=visibleWords;
                if(!expanded && line.contains("\n"))line=line.substring(line.lastIndexOf('\n')+1);
                showText(caption,s.waiting?"Mic busy":line.isEmpty()?(expanded?"Waiting for words…":"Journal"):line);
            }
        }
        boolean resumePanel(){return preview && RecallService.pausedAt(app)>0;}
        void tick() {
            MAIN.removeCallbacks(pulse);
            if(active!=this)return;
            if(!enabled(app)||!Settings.canDrawOverlays(app)||(!preview && !RecallService.isListening())||
               (preview && ((!resumePanel() && SystemClock.elapsedRealtime()>=expires) || (resumePanel() && !org.continuity.recall.ListenPolicy.allowed(app))))) {stop();return;}
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
            GradientDrawable bg=new GradientDrawable();bg.setColor(0xff123632);bg.setCornerRadius(dp(expanded?12:24));
            bg.setStroke(dp(1),0xff49746b);root.setBackground(bg);root.setPadding(dp(4),dp(1),dp(4),dp(1));
            root.setElevation(dp(4));root.setFilterTouchesWhenObscured(true);
            LinearLayout header=row(root);header.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout readout=new LinearLayout(windowContext);readout.setOrientation(LinearLayout.VERTICAL);readout.setGravity(Gravity.CENTER_VERTICAL);
            readout.setPadding(dp(5),0,dp(2),0);readout.setMinimumHeight(dp(48));header.addView(readout,new LinearLayout.LayoutParams(0,-2,1));
            readout.setContentDescription("Open the complete transcript across saved sessions");readout.setTooltipText("Open full journal");
            readout.setOnClickListener(v->openScreen(FullJournalActivity.class,false));
            status=label(readout,resumePanel()?"Paused":preview?"Mic off":"Starting",expanded?14:12);
            status.setSingleLine(true);status.setEllipsize(TextUtils.TruncateAt.END);status.setTextColor(0xffa6f5d5);status.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            boolean showWords=prefs.getBoolean("captions",true);
            if(!preview && !toolsOpen){
                meter=new ProgressBar(windowContext,null,android.R.attr.progressBarStyleHorizontal);meter.setMax(100);
                meter.setContentDescription("Live microphone level");meter.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff78e0c4));
                readout.addView(meter,new LinearLayout.LayoutParams(-1,dp(2)));
                if(showWords){
                    caption=label(expanded?root:readout,"",expanded?14:11);
                    caption.setMaxLines(expanded?2:1);caption.setEllipsize(expanded?TextUtils.TruncateAt.END:TextUtils.TruncateAt.START);
                    if(!expanded)caption.setSingleLine(true);
                }
            }
            if(!expanded && !ultra && !preview){
                keep=key(header,"Save","Save up to the last minute of available audio as a clip",v->request(RecallService.KEEP,"Saving…"));
                key(header,"Pause","Pause recording and release the microphone",v->command(RecallService.USER_PAUSE));
            }
            if(preview && !expanded && !ultra)key(header,resumePanel()?"Resume":"Start","Open Total Recall and start microphone capture",v->openApp(true));
            if(expanded)key(header,toolsOpen?"Back":"More",toolsOpen?"Back to live controls":"More overlay actions",v->{toolsOpen=!toolsOpen;rebuild();});
            TextView size=key(header,expanded?"↙":"↗",expanded?"Shrink panel":ultra?"Open medium panel":"Expand panel",v->resize());size.setTextSize(22);
            if(expanded && !toolsOpen){
                if(!preview){
                    detail=label(root,"",12);detail.setMaxLines(9);detail.setEllipsize(TextUtils.TruncateAt.END);detail.setTextColor(0xffc0d5cd);
                }
                LinearLayout actions=row(root);
                if(preview)control(actions,resumePanel()?"Resume":"Start","Open Total Recall and start microphone capture",v->openApp(true));
                else{
                    keep=control(actions,"Save 1 min","Save up to the last minute of available audio as a clip",v->request(RecallService.KEEP,"Saving…"));
                    takeControl=control(actions,"Record my words","Record a new voice take and edit what you actually said",v->{
                        Intent intent=new Intent(app,VoiceTakeActivity.class).putExtra("begin",true).putExtra("finishTake",SpokenTake.status.recording).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try{app.startActivity(intent);}catch(RuntimeException e){Toast.makeText(app,"Could not open voice take",Toast.LENGTH_SHORT).show();}
                    });
                }
                LinearLayout browse=preview?actions:row(root);
                if(!preview){
                    control(browse,"Pause","Pause recording and release the microphone",v->command(RecallService.USER_PAUSE));
                    control(browse,"Journal","Read and correct individual journal lines",v->openScreen(FullJournalActivity.class,false));
                }
                control(browse,"Live & saved","Read live words, situation analysis, journals and recordings on this phone",v->openScreen(CaptureReviewActivity.class,false));
            }else if(expanded){
                LinearLayout sizes=row(root);
                control(sizes,"Ultra mini","Use the smallest panel with live status and words",v->setSize(true,false));
                control(sizes,"End & close","Stop listening and close Total Recall",v->openScreen(EndListeningActivity.class,false));
                LinearLayout nav=row(root);
                control(nav,"Voices","Teach or manage remembered voices",v->openScreen(VoiceProfilesActivity.class,false));
                control(nav,"Journal","Open the complete transcript across saved sessions",v->openScreen(FullJournalActivity.class,false));
                control(nav,"Recorder","Open the main listening screen",v->openApp(false));
                control(sizes,"Hide","Dismiss the overlay and keep the current microphone state",v->stop());
                if(!preview){
                    LinearLayout extra=row(root);
                    missed=control(extra,"Missed clip","Save available recent audio as a missed moment for review",v->request(RecallService.MISSED,"Saving…"));
                    words=control(extra,showWords?"Words off":"Words on",showWords?"Hide live captions; transcription continues":"Show live captions as speech is recognized",v->changeCaptions(app,!showWords));
                    control(extra,"Commands","Show wake words and available commands",v->newGuide());
                }
            }
            screenWidth=windowContext.getResources().getDisplayMetrics().widthPixels;
            int width=expanded?300:ultra?148:preview?160:212;
            float font=windowContext.getResources().getConfiguration().fontScale;
            width+=Math.round(Math.max(0,Math.min(font-1,1))*60);
            layout.width=Math.min(dp(width),Math.max(dp(100),screenWidth-dp(8)));
            preserveBoundary();
            try {wm.addView(root,layout);visible=true;}
            catch(RuntimeException e){stop();}
        }
        TextView key(LinearLayout row,String title,String hint,View.OnClickListener tap){
            TextView v=control(row,title,hint,tap);v.setLayoutParams(new LinearLayout.LayoutParams(dp(48),-2));v.setTextSize(12);return v;
        }
        TextView label(LinearLayout parent,String value,int size){
            TextView t=new TextView(windowContext);t.setText(value);t.setTextSize(size);t.setTextColor(Color.WHITE);
            t.setIncludeFontPadding(false);t.setPadding(dp(2),dp(1),dp(2),dp(1));parent.addView(t);return t;
        }
        void resize(){
            setSize(false,!expanded && !ultra);
        }
        void setSize(boolean mini,boolean large){
            toolsOpen=false;ultra=mini;expanded=large;
            prefs.edit().putBoolean("ultra",mini).putBoolean("expanded",large).apply();
            rebuild();
        }
        void newGuide(){
            Intent i=new Intent(app,CaptureReviewActivity.class).putExtra("commands",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try{app.startActivity(i);}catch(RuntimeException e){Toast.makeText(app,"Could not open voice commands",Toast.LENGTH_SHORT).show();}
        }
        void openApp(boolean start){openScreen(RecallListenActivity.class,start);}
        void openScreen(Class<?> destination,boolean start){
            Intent i=new Intent(app,destination).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if(start){i.putExtra("overlayStart",true);if(resumePanel())i.putExtra("resumeAt",RecallService.pausedAt(app));}
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
            t.setMinWidth(0);t.setMinHeight(dp(48));t.setPadding(dp(3),0,dp(3),0);t.setClickable(true);
            t.setContentDescription(hint);t.setTooltipText(hint);t.setFilterTouchesWhenObscured(true);t.setOnClickListener(listener);
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
