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
import java.text.DateFormat;
import java.util.Date;

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
    static void changeSize(Context c,boolean mini,boolean large){
        options(c).edit().putBoolean("ultra",mini).putBoolean("expanded",large).apply();
        MAIN.post(()->{if(active!=null && active.visible)active.setSize(mini,large);});
    }
    static void changeCaptions(Context c,boolean value) {
        options(c).edit().putBoolean("captions",value).apply();
        MAIN.post(()->{ if(active!=null && active.visible) { active.preserveBoundary();active.rebuild(); } });
    }
    static final String[] SAVE_NAMES={"Recent audio","Forge word","Poem line","Project decision"};
    static final String[] SAVE_ACTIONS={RecallService.KEEP,RecallService.KEEP_WORD,RecallService.KEEP_LINE,RecallService.KEEP_DECISION};
    static int holdChoice(Context c){
        int index=options(c).getInt("holdSave",0);
        return index>=0 && index<SAVE_ACTIONS.length?index:0;
    }
    static void applyWritingSetup(Context c,int index){
        if(index!=0 && index!=1)return;
        SharedPreferences p=options(c);
        SharedPreferences.Editor edit=p.edit();
        if(!p.contains("previousFocus")){
            edit.putString("previousFocus",p.getString("miniFocus","auto"))
                .putInt("previousHold",holdChoice(c)).putInt("previousLines",p.getInt("captionLines",4))
                .putBoolean("hadFocus",p.contains("miniFocus")).putBoolean("hadHold",p.contains("holdSave"))
                .putBoolean("hadLines",p.contains("captionLines"));
        }
        boolean poet=index==0;
        edit.putString("miniFocus",poet?"buffer":"stats").putInt("holdSave",poet?2:1)
            .putInt("captionLines",poet?6:4).apply();
        refreshOptions();
    }
    static void restoreWritingSetup(Context c){
        SharedPreferences p=options(c);
        if(!p.contains("previousFocus"))return;
        SharedPreferences.Editor edit=p.edit();
        if(p.getBoolean("hadFocus",false))edit.putString("miniFocus",p.getString("previousFocus","auto"));else edit.remove("miniFocus");
        if(p.getBoolean("hadHold",false))edit.putInt("holdSave",p.getInt("previousHold",0));else edit.remove("holdSave");
        if(p.getBoolean("hadLines",false))edit.putInt("captionLines",p.getInt("previousLines",4));else edit.remove("captionLines");
        edit.remove("previousFocus").remove("previousHold").remove("previousLines")
            .remove("hadFocus").remove("hadHold").remove("hadLines").apply();
        refreshOptions();
    }
    static void refreshOptions(){
        MAIN.post(()->{if(active!=null && active.visible)active.rebuild();});
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
        TextView caption, status, detail, keep, missed, words,takeControl,latest,contextCue;
        ProgressBar meter;
        InputTrail trail;
        TextView sessionSummary,sessionToggle,sessionMark,saveAs;
        LinearLayout saveKinds,choiceRow,readout;
        String displayedChoice="",submittedChoice="",expandedForChoice="";
        boolean returnToUltra;
        boolean kindsOpen;
        long receiptTime=-1,hiddenClipId=-1,nextBackupCheck;
        String receiptStamp="",backupReadout="";
        String hiddenPartial="";
        ScrollView panelScroll;
        boolean toolsOpen;
        boolean ultra,expanded,registered,visible,wasWaiting;
        long firstVisibleFinal,requestUntil;
        int requestedAtCount;
        String requestLabel="",partialSeen="",tentativeCue="";
        int screenWidth,screenHeight;
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
            sessionSummary=null;sessionToggle=null;sessionMark=null;saveAs=null;saveKinds=null;choiceRow=null;readout=null;displayedChoice="";
            tentativeCue=partialSeen="";backupReadout="";nextBackupCheck=0;
            root=null;caption=null;status=null;detail=null;takeControl=null;latest=null;contextCue=null;panelScroll=null;meter=null;trail=null;keep=null;missed=null;words=null;visible=false;
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
            hiddenClipId=s.clipId;hiddenPartial=s.partial;firstVisibleFinal=s.finalSequence+(s.partial.isEmpty()?0:1);
            if(caption!=null)caption.setText("");
        }
        void rebuild(){
            if(!unlocked()){detach();return;}
            long boundary=firstVisibleFinal,clipBoundary=hiddenClipId;
            String hidden=hiddenPartial;detach();build();
            firstVisibleFinal=boundary;hiddenPartial=hidden;hiddenClipId=clipBoundary;
            if(visible && !preview)paint(LiveCapture.snapshot());
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
            SelectiveEngine.SaveChoice choice=ready?RecallService.saveChoice:SelectiveEngine.SaveChoice.NONE;
            if(!expandedForChoice.isEmpty() && !expandedForChoice.equals(choice.token)){
                expandedForChoice="";expanded=false;ultra=returnToUltra;toolsOpen=false;rebuild();return;
            }
            boolean asking=!choice.token.isEmpty();
            if(!displayedChoice.equals(choice.token))bindChoice(choice.token);
            if(keep!=null)keep.setEnabled(ready && !asking);
            if(saveAs!=null)saveAs.setEnabled(!asking && (ready || kindsOpen));
            if(saveKinds!=null)for(int i=0;i<saveKinds.getChildCount();i++)saveKinds.getChildAt(i).setEnabled(ready);
            String longId=LongRecording.active;
            boolean recording=!longId.isEmpty();
            String clock=LongRecording.time(LongRecording.duration);
            if(sessionToggle!=null){
                sessionToggle.setTag(longId);
                showText(sessionToggle,recording?"Finish session":"Start session");
                sessionToggle.setEnabled(ready);
                sessionToggle.setContentDescription(recording?"Finish and save this long session":"Start a long recording from this point");
            }
            if(sessionMark!=null){
                sessionMark.setTag(longId);
                sessionMark.setVisibility(recording?View.VISIBLE:View.GONE);
                sessionMark.setEnabled(ready);
            }
            if(sessionSummary!=null){
                sessionSummary.setVisibility(recording?View.VISIBLE:View.GONE);
                int marks=LongRecording.marks;
                showText(sessionSummary,"Session audio · "+clock+" · "+marks+(marks==1?" marker":" markers"));
            }
            if(missed!=null)missed.setEnabled(ready);
            SpokenTake.Status take=SpokenTake.status;
            if(takeControl!=null){showText(takeControl,take.recording?"Finish & edit":"Record my words");takeControl.setContentDescription(take.recording?"Finish recording and edit your take":"Record a new voice take and edit what you actually said");takeControl.setEnabled(ready || take.recording);}
            if(latest!=null){
                latest.setVisibility(s.clipId<0?View.GONE:View.VISIBLE);
                if(s.clipId>=0){
                    if(s.savedAt!=receiptTime){
                        receiptTime=s.savedAt;
                        receiptStamp=DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(receiptTime));
                    }
                    latest.setTag(s.clipId);
                    showText(latest,"Open last save · "+s.lastSave+"\n"+receiptStamp+" · Clip #"+s.clipId);
                    latest.setContentDescription("Open "+s.lastSave+", saved on this phone at "+receiptStamp+", clip "+s.clipId);
                }
            }
            int level=Math.round(s.level*100);
            if(trail!=null)trail.update(ready,level);
            if(meter!=null)meter.setProgress(s.waiting?0:level);
            if(s.savedCount>requestedAtCount)requestUntil=0;
            String elapsed=LongRecording.time(s.sessionSeconds*1000);
            String activeClock=recording?clock:elapsed;
            boolean savePending=SystemClock.elapsedRealtime()<requestUntil;
            boolean clipped=ready && LiveCapture.clipping();
            // Keep unavailable input and real service acknowledgments visible during long takes.
            String headline,compact;
            if(!ready){
                headline=s.waiting?"Waiting for microphone":"Waiting for input";
                compact=s.waiting?"Mic busy":"No input";
            }else if(asking){
                boolean sent=submittedChoice.equals(choice.token);
                headline=sent?"Answer sent":"File held audio · "+choice.seconds+"s";
                compact=sent?"Answer sent":"Save as · "+choice.seconds+"s";
            }else if(savePending){
                headline=requestLabel;
                compact=activeClock+" · Saving";
            }else if(!s.event.isEmpty()){
                headline=s.event;
                String cue=s.event;
                if(cue.endsWith("session marker failed"))cue="Mark failed";
                else if(cue.startsWith("Clip saved") || cue.startsWith(SelectiveEngine.FORGE+" saved") ||
                    cue.startsWith(SelectiveEngine.POETRY+" saved") || cue.startsWith(SelectiveEngine.DECISION+" saved"))cue="Saved";
                else if(cue.startsWith("Marker saved"))cue="Marked";
                else if(cue.startsWith("Flagged ·"))cue="Flagged";
                // Keep the clock moving for the entire acknowledgment window.
                compact=activeClock+" · "+cue;
            }else if(clipped){
                headline="Mic clipping";
                compact=activeClock+" · Clipping";
            }else if(take.recording){
                headline="Voice take · "+take.seconds+" / 60 s";
                compact="Take "+take.seconds+"s";
            }else if(recording){
                headline="Recording long session";
                compact="REC "+clock;
            }else{
                headline=s.state.startsWith("Keeping")?"Capturing clip":"Listening";
                compact=elapsed+" · ↶"+Math.min(60,s.bufferSeconds)+"s";
            }
            showText(status,expanded?headline:compact);
            status.setTextColor(!ready || clipped?0xffffd99a:0xffa6f5d5);
            if(contextCue!=null){
                String pending=s.waiting || s.finalSequence<firstVisibleFinal?"":s.partial;
                if(!pending.equals(partialSeen)){
                    partialSeen=pending;tentativeCue="";
                    if(!pending.isEmpty())try{
                        Situation provisional=new Situation(app);provisional.add(pending,"");tentativeCue=provisional.cue();
                    }catch(java.io.IOException ignored){}
                }
                String cues=s.situationLines(firstVisibleFinal).replace("Now · ","Situation · ");
                if(!tentativeCue.isEmpty())cues+="\nPossible · "+tentativeCue+" (live words)";
                showText(contextCue,cues);
            }
            if(detail!=null){
                String stats=s.words+" words · "+s.flags+" flagged · "+s.savedCount+" saved";
                String diagnostics=s.registration(firstVisibleFinal)+" · Session "+elapsed;
                if(ready)diagnostics+="\n"+s.bufferLabel()+" · "+stats;
                else diagnostics+="\n"+stats;
                if(toolsOpen || prefs.getBoolean("fullDetail",true)){
                    String route=ready?RecallService.activeInputRoute():"";
                    if(!route.isEmpty())diagnostics+="\nMic · "+route;
                    if(ready && !LiveCapture.micSignal.isEmpty())diagnostics+="\n"+LiveCapture.micSignal;
                    if(CuePlaybackGate.active())diagnostics+="\nVoice prompt playing · respond when it finishes";
                    if(clipped)diagnostics+=" · Clipping";
                    long now=SystemClock.elapsedRealtime();
                    if(now>=nextBackupCheck){
                        try{backupReadout=ArchiveSync.backupStatus(app);}
                        catch(RuntimeException unavailable){backupReadout="Archive status unavailable";}
                        nextBackupCheck=now+8000;
                    }
                    diagnostics+="\nText journal "+(JournalStore.enabled(app)?"on":"off")+" · Full audio "+(ArchiveStore.enabled(app)?"on":"off")
                        +"\nArchive destination · "+backupReadout;
                    if(toolsOpen && !s.lastFlag.isEmpty())diagnostics+="\nLast flag · "+s.lastFlag;
                    if(toolsOpen && prefs.getBoolean("captions",true) && s.clipId>=0 && s.clipId!=hiddenClipId && !s.reason.isEmpty())
                        diagnostics+="\nWhy clip #"+s.clipId+" was saved · "+s.reason;
                }
                showText(detail,diagnostics);
            }
            if(caption!=null){
                boolean showWords=prefs.getBoolean("captions",true);
                int maxFinals=expanded?caption.getMaxLines()-(s.partial.isEmpty()?0:1):1;
                String line=showWords?s.overlayWords(firstVisibleFinal,maxFinals):"";
                if(showWords && line.isEmpty() && !s.waiting)line=s.appendedPartial(firstVisibleFinal,hiddenPartial);
                if(!expanded && line.contains("\n"))line=line.substring(line.lastIndexOf('\n')+1);
                String fallback=s.waiting?"Waiting for mic":!ready?"Waiting for input":level<8?"Quiet · mic on":"Input "+level+"%";
                if(ultra && ready && SystemClock.elapsedRealtime()<requestUntil){
                    line="";fallback="Saving…";
                }else if(ultra && ready && (line.isEmpty() || (s.partial.isEmpty() && !s.freshLine(firstVisibleFinal)))){
                    line="";
                    String focus=prefs.getString("miniFocus","auto");
                    if(focus.equals("stats") || (focus.equals("auto") && s.sessionSeconds/4%3==2))
                        fallback=s.savedCount+" saved · "+s.flags+" flags";
                    else if(focus.equals("buffer") || (focus.equals("auto") && s.sessionSeconds/4%3==1))
                        fallback=s.bufferLabel();
                    else if(focus.equals("scene") && showWords && !s.currentSituation(firstVisibleFinal).isEmpty())
                        fallback=s.currentSituation(firstVisibleFinal);
                }
                if(expanded && showWords && line.isEmpty() && ready)fallback="Waiting for words…";
                if(!expanded && recording && (!ready || savePending || !s.event.isEmpty() || clipped) && line.isEmpty())
                    fallback="Audio "+clock+" · "+LongRecording.marks+(LongRecording.marks==1?" mark":" marks");
                if(expanded && !line.isEmpty()){
                    if(s.partial.isEmpty())line="Heard · "+line;
                    else if(s.finalSequence<firstVisibleFinal || line.equals(s.partial))line="Live · "+line;
                    else if(line.endsWith("\n"+s.partial)){
                        String complete=line.substring(0,line.length()-s.partial.length()-1);
                        line="Heard · "+complete+"\nLive · "+s.partial;
                    }
                }
                if(asking && !expanded){line="";fallback=activeClock+" · Tap to file";}
                showText(caption,line.isEmpty()?fallback:line);
                if(ultra && Build.VERSION.SDK_INT>=30){
                    View readout=(View)status.getParent();
                    String summary=(ready && !asking && !savePending && !s.event.isEmpty()?s.event:status.getText())+". Listening session "+elapsed+", "+s.bufferLabel()+". "
                        +s.words+" words, "+s.savedCount+" saved, "+s.flags+" flagged. "+caption.getText();
                    if(!TextUtils.equals(summary,readout.getStateDescription()))readout.setStateDescription(summary);
                }
            }
        }
        boolean resumePanel(){return preview && RecallService.pausedAt(app)>0;}
        void tick() {
            MAIN.removeCallbacks(pulse);
            if(active!=this)return;
            if(!enabled(app)||!Settings.canDrawOverlays(app)||(!preview && !RecallService.isListening())||
               (preview && ((!resumePanel() && SystemClock.elapsedRealtime()>=expires) || (resumePanel() && !org.continuity.recall.ListenPolicy.allowed(app))))) {stop();return;}
            if(!unlocked()){
                if(visible)detach();
                MAIN.postDelayed(pulse,1200);return;
            }
            if(!visible)build();
            else if(screenWidth!=windowContext.getResources().getDisplayMetrics().widthPixels ||
                screenHeight!=windowContext.getResources().getDisplayMetrics().heightPixels)rebuild();
            if(active!=this)return;
            if(!preview)paint(LiveCapture.snapshot());
            position(layout.x,layout.y);
            MAIN.postDelayed(pulse,preview?1500:300);
        }
        boolean unlocked(){
            PowerManager power=app.getSystemService(PowerManager.class);
            KeyguardManager keyguard=app.getSystemService(KeyguardManager.class);
            return power!=null && keyguard!=null && power.isInteractive() && !keyguard.isKeyguardLocked();
        }
        void build(){
            root=new DragSurface();root.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg=new GradientDrawable();bg.setColor(0xff123632);bg.setCornerRadius(dp(expanded?12:24));
            bg.setStroke(dp(1),0xff49746b);root.setBackground(bg);root.setPadding(dp(4),dp(1),dp(4),dp(1));
            root.setElevation(dp(4));root.setFilterTouchesWhenObscured(true);
            LinearLayout header=row(root);header.setGravity(Gravity.CENTER_VERTICAL);
            readout=new LinearLayout(windowContext);readout.setOrientation(LinearLayout.VERTICAL);readout.setGravity(Gravity.CENTER_VERTICAL);
            readout.setPadding(dp(5),0,dp(2),0);readout.setMinimumHeight(dp(48));header.addView(readout,new LinearLayout.LayoutParams(0,-2,1));
            readout.setContentDescription("Open the complete transcript across saved sessions");readout.setTooltipText("Open full journal");
            readout.setOnClickListener(v->openScreen(FullJournalActivity.class,false));
            if(ultra && !preview){
                String holdName=SAVE_NAMES[holdChoice(app)];
                readout.setTooltipText("Tap for journal · hold to save "+holdName.toLowerCase(java.util.Locale.ROOT));
                readout.setOnLongClickListener(v->{
                    if(!RecallService.saveChoice.token.isEmpty())return true;
                    if(!LiveCapture.snapshot().receiving){Toast.makeText(app,"Waiting for microphone input",Toast.LENGTH_SHORT).show();return true;}
                    request(SAVE_ACTIONS[holdChoice(app)],"Saving…");
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    return true;
                });
                readout.setAccessibilityDelegate(new View.AccessibilityDelegate(){
                    @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
                        super.onInitializeAccessibilityNodeInfo(host,info);
                        if(displayedChoice.isEmpty())info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,"Save "+holdName));
                    }
                });
            }
            status=label(readout,resumePanel()?"Paused":preview?"Mic off":"Starting",expanded?14:12);
            if(expanded)status.setMaxLines(2);else status.setSingleLine(true);
            status.setEllipsize(TextUtils.TruncateAt.END);status.setTextColor(0xffa6f5d5);status.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            LinearLayout body=root;
            if(expanded){
                if(!preview){
                    choiceRow=row(root);
                    GradientDrawable choiceBg=new GradientDrawable();choiceBg.setColor(0xff28664f);choiceBg.setCornerRadius(dp(8));choiceRow.setBackground(choiceBg);
                    choiceRow.setVisibility(View.GONE);
                    sessionSummary=label(root,"",13);sessionSummary.setTextColor(0xffa6f5d5);sessionSummary.setVisibility(View.GONE);
                }
                Point display=new Point();wm.getDefaultDisplay().getSize(display);
                panelScroll=new ScrollView(windowContext){
                    @Override protected void onMeasure(int w,int h){
                        int summaryHeight=sessionSummary!=null && sessionSummary.getVisibility()==View.VISIBLE?sessionSummary.getMeasuredHeight():0;
                        int choiceHeight=choiceRow!=null && choiceRow.getVisibility()==View.VISIBLE?choiceRow.getMeasuredHeight():0;
                        int reserve=Math.max(dp(106),header.getMeasuredHeight()+summaryHeight+choiceHeight+dp(24));
                        super.onMeasure(w,MeasureSpec.makeMeasureSpec(Math.max(dp(24),display.y-reserve),MeasureSpec.AT_MOST));
                    }
                };
                body=new LinearLayout(windowContext);body.setOrientation(LinearLayout.VERTICAL);
                panelScroll.addView(body);root.addView(panelScroll,new LinearLayout.LayoutParams(-1,-2));
            }
            boolean showWords=prefs.getBoolean("captions",true);
            if(!preview && !toolsOpen){
                if(ultra){
                    trail=new InputTrail();
                    readout.addView(trail,new LinearLayout.LayoutParams(-1,dp(6)));
                }else{
                    meter=new ProgressBar(windowContext,null,android.R.attr.progressBarStyleHorizontal);meter.setMax(100);
                    meter.setContentDescription("Live microphone level");meter.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff78e0c4));
                    readout.addView(meter,new LinearLayout.LayoutParams(-1,dp(4)));
                }
                if(showWords || !expanded){
                    caption=label(expanded?body:readout,"",expanded?14:11);
                    caption.setMaxLines(expanded?Math.max(2,Math.min(6,prefs.getInt("captionLines",4))):1);
                    caption.setEllipsize(expanded?null:TextUtils.TruncateAt.START);
                    if(!expanded)caption.setSingleLine(true);
                }
            }
            if(!expanded){
                // Keep one line plus its ascenders and descenders without a font's spare vertical padding.
                compactLine(status);
                if(caption!=null)compactLine(caption);
            }
            if(!expanded && !ultra && !preview){
                keep=key(header,"Save","Save up to the last minute of available audio as a clip",v->request(RecallService.KEEP,"Keep requested…"));
                key(header,"Pause","Pause recording and release the microphone",v->command(RecallService.USER_PAUSE));
            }
            if(preview && !expanded && !ultra)key(header,resumePanel()?"Resume":"Start","Open Total Recall and start microphone capture",v->openApp(true));
            if(expanded)key(header,toolsOpen?"Back":"More",toolsOpen?"Back to live controls":"More overlay actions",v->{toolsOpen=!toolsOpen;rebuild();});
            TextView size=key(header,expanded?"↙":"↗",expanded?"Shrink panel":ultra?"Open medium panel":"Expand panel",v->resize());size.setTextSize(22);
            TextView close=key(header,"×","Stop listening and close Total Recall",v->openScreen(EndListeningActivity.class,false));
            close.setTextSize(22);close.setTextColor(0xffffc4b4);
            if(expanded && !toolsOpen){
                LinearLayout actions=row(body);
                if(preview)control(actions,resumePanel()?"Resume":"Start","Open Total Recall and start microphone capture",v->openApp(true));
                else{
                    keep=control(actions,"Save 1 min","Save up to the last minute of available audio as a clip",v->request(RecallService.KEEP,"Keep requested…"));
                    takeControl=control(actions,"Record my words","Record a new voice take and edit what you actually said",v->{
                        Intent intent=new Intent(app,VoiceTakeActivity.class).putExtra("begin",true).putExtra("finishTake",SpokenTake.status.recording).putExtra("takeWindow",LiveCapture.snapshot().voiceSession).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try{app.startActivity(intent);}catch(RuntimeException e){Toast.makeText(app,"Could not open voice take",Toast.LENGTH_SHORT).show();}
                    });
                }
                if(!preview){
                    GradientDrawable actionBg=new GradientDrawable();actionBg.setColor(0xff24594e);actionBg.setCornerRadius(dp(8));actions.setBackground(actionBg);
                    detail=label(body,"",12);detail.setTextColor(0xffc0d5cd);
                    if(showWords){
                        contextCue=label(body,"",13);contextCue.setTextColor(0xffd5eae1);
                        contextCue.setOnClickListener(v->openScreen(CaptureReviewActivity.class,false));
                        contextCue.setTooltipText("Inspect the words behind these situation cues");
                    }
                }
                LinearLayout sessions=row(body);
                if(!preview){
                    sessionToggle=control(sessions,"Start session","Start a long recording from this point",v->{
                        if(!(v.getTag() instanceof String))return;
                        String expected=(String)v.getTag();
                        if(expected.isEmpty()){
                            if(LongRecording.active.isEmpty() && LiveCapture.snapshot().receiving)command(RecallService.BEGIN_LONG);
                        }else sessionAction(RecallService.FINISH_LONG,expected);
                    });
                    sessionMark=control(sessions,"Mark point","Bookmark the current audio position",v->sessionAction(RecallService.MARK_LONG,(String)v.getTag()));
                    sessionMark.setVisibility(View.GONE);
                }
                control(sessions,"Sessions","Open long recordings and their markers",v->openScreen(LongSessionsActivity.class,false));
                LinearLayout browse=preview?actions:row(body);
                if(!preview){
                    control(actions,"Pause","Pause recording and release the microphone",v->command(RecallService.USER_PAUSE));
                    saveAs=control(browse,"Save as…","Choose a category and save recent audio",v->{
                        kindsOpen=!kindsOpen;showKinds();
                    });
                    control(browse,"Journal","Read and correct individual journal lines",v->openScreen(FullJournalActivity.class,false));
                }
                control(browse,"Live & saved","Read live words, situation analysis, journals and recordings on this phone",v->openScreen(CaptureReviewActivity.class,false));
                if(!preview){
                    saveKinds=row(body);
                    for(int i=1;i<SAVE_NAMES.length;i++){
                        final int index=i;
                        control(saveKinds,SAVE_NAMES[i],"Save recent audio as "+SAVE_NAMES[i],v->{
                            if(!LiveCapture.snapshot().receiving)return;
                            request(SAVE_ACTIONS[index],"Saving…");
                            kindsOpen=false;showKinds();
                        });
                    }
                    showKinds();
                    latest=control(row(body),"Open last save","Open the most recently saved clip",v->{
                        if(!(v.getTag() instanceof Long))return;
                        try{app.startActivity(new Intent(app,RecallListenActivity.class).putExtra("moment_id",(long)(Long)v.getTag())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
                        catch(RuntimeException e){Toast.makeText(app,"Could not open the clip",Toast.LENGTH_SHORT).show();}
                    });latest.setVisibility(View.GONE);
                }
            }else if(expanded){
                if(!preview){detail=label(body,"",12);detail.setTextColor(0xffc0d5cd);}
                LinearLayout sizes=row(body);
                control(sizes,"Ultra mini","Use the smallest panel with live status and words",v->setSize(true,false));
                LinearLayout nav=row(body);
                control(nav,"Voices","Teach or manage remembered voices",v->openScreen(VoiceProfilesActivity.class,false));
                control(nav,"Journal","Open the complete transcript across saved sessions",v->openScreen(FullJournalActivity.class,false));
                control(nav,"Recorder","Open the main listening screen",v->openApp(false));
                if(!preview)control(sizes,"Mic check","Inspect the microphone and adjust quiet speech",v->{
                    try{app.startActivity(new Intent(app,CaptureReviewActivity.class).putExtra("micCheck",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
                    catch(RuntimeException e){Toast.makeText(app,"Could not open microphone check",Toast.LENGTH_SHORT).show();}
                });
                control(sizes,"Hide","Dismiss the overlay and keep the current microphone state",v->stop());
                if(!preview){
                    LinearLayout spoken=row(body);
                    control(spoken,"Hear status","Hear current recording status",v->command(RecallService.SPEAK_STATUS));
                    control(spoken,"Last result","Hear the most recent capture or recovery result",v->command(RecallService.REPEAT_RESULT));
                    control(spoken,"Spoken help","Hear how to use voice commands",v->command(RecallService.SPEAK_HELP));
                    LinearLayout extra=row(body);
                    missed=control(extra,"Missed clip","Save available recent audio as a missed moment for review",v->request(RecallService.MISSED,"Keep requested…"));
                    words=control(extra,showWords?"Words off":"Words on",showWords?"Hide live captions; transcription continues":"Show live captions as speech is recognized",v->changeCaptions(app,!showWords));
                    control(extra,"Commands","Show wake words and available commands",v->newGuide());
                }
            }
            screenWidth=windowContext.getResources().getDisplayMetrics().widthPixels;
            screenHeight=windowContext.getResources().getDisplayMetrics().heightPixels;
            int width=expanded?320:ultra?192:preview?210:276;
            float font=windowContext.getResources().getConfiguration().fontScale;
            width+=Math.round(Math.max(0,Math.min(font-1,1))*60);
            layout.width=Math.min(dp(width),Math.max(dp(100),screenWidth-dp(8)));
            preserveBoundary();
            try {wm.addView(root,layout);visible=true;}
            catch(RuntimeException e){stop();}
        }
        /** Six seconds of level samples from the visible overlay. Missing input leaves empty columns. */
        final class InputTrail extends View{
            final int[] peaks=new int[20];
            final android.graphics.Paint ink=new android.graphics.Paint();
            long last=-1;
            InputTrail(){super(windowContext);java.util.Arrays.fill(peaks,-1);setContentDescription("Recent microphone levels");}
            void update(boolean receiving,int level){
                long slot=SystemClock.elapsedRealtime()/300;
                if(!receiving){if(last>=0){java.util.Arrays.fill(peaks,-1);last=-1;invalidate();}return;}
                int advance=last<0?peaks.length:(int)Math.min(peaks.length,slot-last);
                if(advance>0){
                    System.arraycopy(peaks,advance,peaks,0,peaks.length-advance);
                    java.util.Arrays.fill(peaks,peaks.length-advance,peaks.length,-1);
                }
                last=slot;int end=peaks.length-1,peak=Math.max(peaks[end],level);
                if(advance>0 || peak!=peaks[end]){peaks[end]=peak;invalidate();}
            }
            @Override protected void onDraw(android.graphics.Canvas canvas){
                float step=getWidth()/(float)peaks.length;
                for(int i=0;i<peaks.length;i++){
                    if(peaks[i]<0)continue;
                    float h=Math.max(1,getHeight()*peaks[i]/100f);
                    ink.setColor(peaks[i]>=95?0xffffce8a:0xff78e0c4);
                    canvas.drawRect(i*step,getHeight()-h,(i+1)*step-1,getHeight(),ink);
                }
            }
        }
        void compactLine(TextView line){
            if(Build.VERSION.SDK_INT>=28)line.setFallbackLineSpacing(false);
            line.setGravity(Gravity.CENTER_VERTICAL);
            int height=(int)Math.ceil(line.getTextSize()*1.6f)+line.getPaddingTop()+line.getPaddingBottom();
            line.setHeight(height);
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
            expandedForChoice="";toolsOpen=false;ultra=mini;expanded=large;
            prefs.edit().putBoolean("ultra",mini).putBoolean("expanded",large).apply();
            rebuild();
        }
        void bindChoice(String token){
            displayedChoice=token;
            if(!submittedChoice.equals(token))submittedChoice="";
            if(!token.isEmpty() && saveKinds!=null){kindsOpen=false;showKinds();}
            if(choiceRow!=null){
                choiceRow.removeAllViews();
                choiceRow.setVisibility(token.isEmpty()?View.GONE:View.VISIBLE);
                if(!token.isEmpty()){
                    for(String answer:SelectiveEngine.CHOICE_WORDS){
                        String title=Character.toUpperCase(answer.charAt(0))+answer.substring(1);
                        String hint=answer.equals("cancel")?"Cancel this held rewind":"File held audio as "+
                            (answer.equals("word")?SelectiveEngine.FORGE:answer.equals("line")?SelectiveEngine.POETRY:SelectiveEngine.DECISION);
                        control(choiceRow,title,hint,v->{
                            if(visible && v.getParent()==choiceRow && v.isShown())submitChoice(token,answer);
                        }).setEnabled(!token.equals(submittedChoice));
                    }
                    choiceRow.announceForAccessibility("File held audio. Choose word, line, decision or cancel.");
                }
            }else if(!expanded && readout!=null){
                boolean pending=!token.isEmpty();
                readout.setLongClickable(ultra && !pending);
                readout.setContentDescription(pending?"Choose how to file the held audio":"Open the complete transcript across saved sessions");
                readout.setTooltipText(pending?"Choose word, line, decision or cancel":ultra?
                    "Tap for journal · hold to save "+SAVE_NAMES[holdChoice(app)].toLowerCase(java.util.Locale.ROOT):"Open full journal");
                readout.setOnClickListener(v->{
                    if(!pending){openScreen(FullJournalActivity.class,false);return;}
                    if(visible && v==readout && v.isShown() && token.equals(RecallService.saveChoice.token) &&
                        unlocked() && LiveCapture.snapshot().receiving){
                        returnToUltra=ultra;expandedForChoice=token;expanded=true;ultra=false;rebuild();
                    }
                });
            }
        }
        void submitChoice(String token,String answer){
            if(token.equals(submittedChoice) || !token.equals(RecallService.saveChoice.token) || !unlocked() ||
                !RecallService.isListening() || !LiveCapture.snapshot().receiving)return;
            try{
                app.startService(new Intent(app,RecallService.class).setAction(RecallService.ANSWER_CHOICE)
                    .putExtra("choiceToken",token).putExtra("choiceAnswer",answer));
                submittedChoice=token;
                if(choiceRow!=null)for(int i=0;i<choiceRow.getChildCount();i++)choiceRow.getChildAt(i).setEnabled(false);
            }catch(RuntimeException e){Toast.makeText(app,"Recorder could not accept this choice",Toast.LENGTH_SHORT).show();}
        }
        void showKinds(){
            saveKinds.setVisibility(kindsOpen?View.VISIBLE:View.GONE);
            showText(saveAs,kindsOpen?"Cancel":"Save as…");
            saveAs.setContentDescription(kindsOpen?"Close category choices":"Choose a category and save recent audio");
        }
        void sessionAction(String action,String expected){
            if(expected==null || expected.isEmpty() || !expected.equals(LongRecording.active) || !RecallService.isListening())return;
            try{app.startService(new Intent(app,RecallService.class).setAction(action).putExtra("longId",expected));}
            catch(RuntimeException e){Toast.makeText(app,"Recorder could not accept this action",Toast.LENGTH_SHORT).show();}
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
            t.setText(label);t.setTextSize(14);t.setGravity(Gravity.CENTER);
            t.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{-android.R.attr.state_enabled},{}},new int[]{0xffa1b5b0,Color.WHITE}));
            t.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x449ff1cc),null,new android.graphics.drawable.ColorDrawable(Color.WHITE)));
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
            boolean moving,blocked,bodyTouch,scrolling;
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
                    scrolling=false;bodyTouch=panelScroll!=null && e.getY()>=panelScroll.getTop()
                        && (panelScroll.canScrollVertically(1)||panelScroll.canScrollVertically(-1));
                    moving=false;blocked=false;downX=e.getRawX();downY=e.getRawY();oldX=layout.x;oldY=layout.y;
                }
                if(action==MotionEvent.ACTION_POINTER_DOWN){cancelChild(e);blocked=true;}
                if(blocked)return true;
                float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
                if(action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP){
                    if(bodyTouch && !moving && Math.abs(dy)>slop && Math.abs(dy)>Math.abs(dx))scrolling=true;
                    if(scrolling){super.dispatchTouchEvent(e);return true;}
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
