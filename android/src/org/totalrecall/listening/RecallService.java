package org.totalrecall.listening;

import android.app.*;
import org.continuity.recall.ListenPolicy;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.*;
import android.os.*;
import android.content.pm.ServiceInfo;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single user-started microphone owner. All speech and detection work runs off the activity thread. */
public final class RecallService extends Service {
    private static final String NS="org.totalrecall.listening.";
    public static final String BEGIN_TAKE=NS+"BEGIN_TAKE", END_TAKE=NS+"END_TAKE";
    static final String BEGIN_LONG=NS+"BEGIN_LONG",FINISH_LONG=NS+"FINISH_LONG",MARK_LONG=NS+"MARK_LONG";
    static final String KEEP_WORD=NS+"KEEP_WORD",KEEP_LINE=NS+"KEEP_LINE",KEEP_DECISION=NS+"KEEP_DECISION";
    private static String rewindCategory(String action){
        if(KEEP_WORD.equals(action))return SelectiveEngine.FORGE;
        if(KEEP_LINE.equals(action))return SelectiveEngine.POETRY;
        if(KEEP_DECISION.equals(action))return SelectiveEngine.DECISION;
        return null;
    }
    public static final String REFRESH_NOTICE=NS+"REFRESH_NOTICE";
    public static final String LEARN_ROOM=NS+"LEARN_ROOM";
    public static final String START=NS+"START",PAUSE=NS+"PAUSE",USER_PAUSE=NS+"USER_PAUSE",END=NS+"END",KEEP=NS+"KEEP",
        MISSED=NS+"MISSED",DISCARD_LAST=NS+"DISCARD_LAST",ACTION_CHANGED=NS+"CHANGED";
    static final String CHANNEL="total_recall_listening";
    private static final String PREFS="total_recall_session";
    private static final int NOTIFICATION=9021, RESUME_NOTIFICATION=9022;
    private volatile boolean userPaused,ending;
    private static volatile boolean running;
    static final String ANSWER_CHOICE=NS+"ANSWER_CHOICE";
    static volatile SelectiveEngine.SaveChoice saveChoice=SelectiveEngine.SaveChoice.NONE;
    private static volatile String current="";
    private final ConcurrentLinkedQueue<String> commands=new ConcurrentLinkedQueue<>();
    private final AtomicBoolean stopping=new AtomicBoolean(false);
    private volatile AudioRecord liveRecorder;
    private volatile boolean capturing;
    private volatile boolean destroyed;
    private Thread worker;
    private CaptureCues cues;
    static final String SPEAK_STATUS=NS+"SPEAK_STATUS",REPEAT_RESULT=NS+"REPEAT_RESULT",SPEAK_HELP=NS+"SPEAK_HELP";
    private volatile CaptureCues.Cue lastOutcome=CaptureCues.Cue.NO_EVENT;
    private volatile boolean gapOpen;
    private static final long SAMPLE_NANOS=1_000_000_000L/SelectiveEngine.RATE;
    private void speakWake(boolean words){
        noticeHandler.post(()->{
            if(destroyed || cues==null)return;
            try{if(words)cues.spokenReady();else cues.wakeChime();}catch(RuntimeException ignored){}
        });
    }
    static CaptureCues.Cue statusCue(LiveCapture.Snapshot s){
        if(!s.receiving)return s.waiting?CaptureCues.Cue.MIC_WAIT:CaptureCues.Cue.NO_AUDIO;
        if(!LongRecording.active.isEmpty())return CaptureCues.Cue.STATUS_LONG;
        if(SpokenTake.status.recording)return CaptureCues.Cue.STATUS_TAKE;
        return s.state.startsWith("Keeping")?CaptureCues.Cue.STATUS_CLIP:CaptureCues.Cue.STATUS_LISTEN;
    }
    private void answer(String request,boolean tapped){
        CaptureCues.Cue cue=request.equals("repeat")?lastOutcome:request.equals("help")?CaptureCues.Cue.HELP:statusCue(LiveCapture.snapshot());
        LiveCapture.feedback(cue.words);noticeSoon();
        noticeHandler.post(()->{
            if(destroyed || cues==null)return;
            try{cues.answer(cue,tapped);}catch(RuntimeException ignored){}
        });
    }
    private volatile String noticeSession="";
    private static final String RESUME_FROM_NOTICE=NS+"RESUME_FROM_NOTICE";
    private void sound(CaptureCues.Cue cue){sound(cue,false);}
    private void sound(CaptureCues.Cue cue,boolean automatic){
        if(cue!=CaptureCues.Cue.WAKE && cue!=CaptureCues.Cue.CHOICE)lastOutcome=cue;
        noticeHandler.post(()->{
            if(destroyed)return;
            try{if(cues==null)cues=new CaptureCues(this);cues.play(cue,automatic);}catch(RuntimeException ignored){}
        });
    }
    private int lastStart;
    private volatile boolean longOnly;
    private long startedElapsed;
    private static volatile String inputRoute="";
    static String activeInputRoute(){return running?inputRoute:"";}
    private final Handler noticeHandler=new Handler(Looper.getMainLooper());
    private boolean channelReady;
    private final Runnable refreshNotice=new Runnable(){public void run(){
        if(!running || destroyed || stopping.get())return;
        try{notifications().notify(NOTIFICATION,notification(current));}catch(SecurityException ignored){}
        PowerManager power=getSystemService(PowerManager.class);
        noticeHandler.postDelayed(this,power!=null&&power.isInteractive()?1200:4500);
    }};
    private final Runnable scheduleNotice=()->{
        noticeHandler.removeCallbacks(refreshNotice);
        noticeHandler.postDelayed(refreshNotice,150);
    };
    private void noticeSoon(){
        if(!running||destroyed||stopping.get())return;
        noticeHandler.removeCallbacks(scheduleNotice);noticeHandler.post(scheduleNotice);
    }
    private PendingIntent reviewIntent(){
        return PendingIntent.getActivity(this,204,new Intent(this,CaptureReviewActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private static String elapsed(long seconds){return String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
    private String captureSettings(){
        return "Full audio "+(ArchiveStore.enabled(this)?"on":"off")+" · Text journal "+(JournalStore.enabled(this)?"on":"off");
    }
    private String backupStatus(){
        long receipt=ArchiveSync.prefs(this).getLong("lastReceiptAt",0);
        return "Auto backup "+(ArchiveSync.auto(this)?"on":"off")+(receipt>0?" · Last receipt "+java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new Date(receipt)):"");
    }
    public static boolean isListening(){return running;}
    public static long pausedAt(Context c){return c.getSharedPreferences(PREFS,MODE_PRIVATE).getLong("pausedAt",0);}
    public static void clearResume(Context c){
        c.getSharedPreferences(PREFS,MODE_PRIVATE).edit().remove("pausedAt").commit();
        NotificationManager nm=c.getSystemService(NotificationManager.class);if(nm!=null)nm.cancel(RESUME_NOTIFICATION);
    }
    private void showResume(long since,boolean openApp){
        if(since<=0||!ListenPolicy.allowed(this))return;
        Intent open=new Intent(this,RecallListenActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("overlayStart",true).putExtra("resumeAt",since);
        PendingIntent tap=PendingIntent.getActivity(this,203,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Intent resume=new Intent(this,RecallService.class).setAction(RESUME_FROM_NOTICE)
            .setData(android.net.Uri.parse("recall:resume/"+since)).putExtra("pausedAt",since);
        PendingIntent resumeTap=PendingIntent.getService(this,205,resume,PendingIntent.FLAG_IMMUTABLE);
        SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        String paused="Microphone off since "+java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new Date(since));
        String summary=paused+"\nSession "+elapsed(prefs.getLong("pausedSeconds",0))+" · "+prefs.getInt("pausedClips",0)+" clips saved · "+prefs.getLong("pausedWords",0)+" words · "+prefs.getInt("pausedFlags",0)+" flagged\n"+captureSettings()+"\n"+backupStatus();
        Intent end=new Intent(this,EndListeningActivity.class).putExtra("endPausedAt",since)
            .setData(android.net.Uri.parse("recall:end-paused/"+since));
        PendingIntent endTap=PendingIntent.getActivity(this,206,end,PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_pause)
            .setStyle(new Notification.BigTextStyle().bigText(summary)).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentTitle("Total Recall paused").setContentText(openApp?"Open the recorder to restore microphone access":"Microphone off · tap to resume listening")
            .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_play,openApp?"Open recorder":"Resume",openApp?tap:resumeTap).addAction(android.R.drawable.ic_menu_agenda,"Live & saved",reviewIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"End & close",endTap).build();
        try{notifications().notify(RESUME_NOTIFICATION,n);}catch(SecurityException ignored){}
    }
    public static String status(Context c){
        if(running)return current;
        SharedPreferences p=c.getSharedPreferences(PREFS,MODE_PRIVATE);
        if(p.getBoolean("active",false))return "Session interrupted · resume listening";
        return pausedAt(c)>0?"Paused · microphone off":p.getString("last","Paused");
    }
    public static void command(Context c,String action){
        Intent i=new Intent(c,RecallService.class).setAction(action);
        if(FINISH_LONG.equals(action)||MARK_LONG.equals(action))i.putExtra("longId",LongRecording.active);
        if((START.equals(action)||BEGIN_LONG.equals(action)&&!running)&&Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);
    }
    @Override public IBinder onBind(Intent intent){return null;}
    private void changed(){
        if(!destroyed)sendBroadcast(new Intent(ACTION_CHANGED).setPackage(getPackageName()));
    }
    private NotificationManager notifications(){return (NotificationManager)getSystemService(NOTIFICATION_SERVICE);}
    private Notification notification(String label){
        if(!channelReady && Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel(CHANNEL,"Selective listening",NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Visible controls for the microphone session");notifications().createNotificationChannel(ch);channelReady=true;
        }
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        LiveCapture.Snapshot snapshot=LiveCapture.snapshot();
        SpokenTake.Status take=SpokenTake.status;
        boolean receiving=snapshot.receiving && capturing && !stopping.get();
        boolean previews=getSharedPreferences("recall-speech",0).getBoolean("noticeWords",false);
        String headline=snapshot.waiting?"Waiting for microphone":take.recording && receiving?"Recording take · "+take.seconds+" / 60 s":
            !snapshot.event.isEmpty()?snapshot.event:receiving?(label!=null && label.startsWith("Keeping")?"Capturing selected clip":"Listening"):label;
        if(headline==null || headline.isEmpty())headline="Preparing microphone";
        if(snapshot.active && !receiving && !snapshot.waiting && snapshot.event.isEmpty())headline="Waiting for microphone input";
        boolean longOpen=!LongRecording.active.isEmpty();
        if(longOpen && receiving && !snapshot.waiting && snapshot.event.isEmpty() && !take.recording)
            headline="Long session · "+LongRecording.time(LongRecording.duration);
        String counts=snapshot.words+" words · "+snapshot.savedCount+" saved · "+snapshot.flags+" flagged";
        StringBuilder details=new StringBuilder(snapshot.waiting?snapshot.state:snapshot.registration(0));
        if(receiving){
            details.append(" · ").append(snapshot.bufferLabel());
            if(!inputRoute.isEmpty())details.append(" · ").append(inputRoute);
        }
        if(snapshot.active && !snapshot.waiting && previews){
            String line=snapshot.partial.isEmpty()?(snapshot.finals.isEmpty()?"":snapshot.attributed(snapshot.finals.size()-1)):snapshot.partial;
            if(!line.isEmpty())details.append(snapshot.partial.isEmpty()?"\nHeard · ":"\nIn progress · ").append(line.substring(0,Math.min(line.length(),120)));
            details.append("\n").append(snapshot.situationLines(0));
        }
        if(longOpen)details.append("\nLong session · ").append(LongRecording.time(LongRecording.duration)).append(" · ").append(LongRecording.marks).append(" markers");
        details.append("\n").append(counts);
        if(!snapshot.lastSave.isEmpty())details.append(" · Latest: ").append(snapshot.lastSave).append(" at ").append(java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(new Date(snapshot.savedAt)));
        details.append("\n").append(captureSettings()).append("\n").append(backupStatus());
        int icon=getResources().getIdentifier("ic_listening","drawable",getPackageName());
        String brief=snapshot.waiting?"Mic busy":take.recording?"Take "+take.seconds+"/60s":
            !receiving?"No input":!snapshot.partial.isEmpty()?"Decoding":snapshot.level<.08f?"Quiet":"Input "+Math.round(snapshot.level*100)+"%";
        b.setContentTitle(headline).setContentText(snapshot.active?brief+" · "+counts:"Starting on-device speech recognition")
            .setSmallIcon(icon!=0?icon:android.R.drawable.ic_btn_speak_now)
            .setStyle(new Notification.BigTextStyle().bigText(details)).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).setContentIntent(reviewIntent());
        if(Build.VERSION.SDK_INT>=31)b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if(snapshot.active && startedElapsed>0)b.setWhen(System.currentTimeMillis()-(SystemClock.elapsedRealtime()-startedElapsed)).setUsesChronometer(true);
        if(take.recording && receiving)b.setProgress(60,Math.min(60,take.seconds),false);
        if(longOpen){
            noticeAction(b,MARK_LONG,"Mark point",android.R.drawable.ic_menu_add);
            noticeAction(b,FINISH_LONG,"Finish session",android.R.drawable.ic_menu_save);
        }else if(receiving){
            noticeAction(b,KEEP,"Save last min",android.R.drawable.ic_menu_save);
            if(!take.recording && getSharedPreferences("recall-speech",0).getBoolean("noticeMissed",false))
                noticeAction(b,MISSED,"Mark missed",android.R.drawable.ic_menu_recent_history);
            else noticeAction(b,take.recording?END_TAKE:BEGIN_TAKE,take.recording?"Finish take":"Start take",android.R.drawable.ic_btn_speak_now);
        }
        if(snapshot.active && !stopping.get())noticeAction(b,USER_PAUSE,"Pause",android.R.drawable.ic_media_pause);
        return b.build();
    }
    private void noticeAction(Notification.Builder b,String action,String title,int icon){
        boolean take=action.equals(BEGIN_TAKE)||action.equals(END_TAKE);
        long id=take?SpokenTake.generation:0;
        Intent i=new Intent(this,RecallService.class).setAction(action)
            .setData(android.net.Uri.parse("recall:control/"+noticeSession+"/"+id))
            .putExtra("noticeSession",noticeSession).putExtra("takeGeneration",id);
        if(FINISH_LONG.equals(action)||MARK_LONG.equals(action)){
            i.putExtra("longId",LongRecording.active);i.setData(android.net.Uri.parse("recall:long/"+noticeSession+"/"+LongRecording.active));
        }
        b.addAction(icon,title,PendingIntent.getService(this,0,i,PendingIntent.FLAG_IMMUTABLE));
    }
    private void progress(String message){
        current=message;LiveCapture.state(message); changed();
        noticeSoon();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        lastStart=startId;
        String action=intent==null?null:intent.getAction();
        if(RESUME_FROM_NOTICE.equals(action)){
            long expected=intent.getLongExtra("pausedAt",-1);
            if(worker!=null)return START_NOT_STICKY;
            if(expected<=0 || expected!=pausedAt(this) || !ListenPolicy.allowed(this)){stopSelf(startId);return START_NOT_STICKY;}
            action=START;
        }
        if(intent!=null && intent.hasExtra("noticeSession")){
            boolean take=BEGIN_TAKE.equals(action)||END_TAKE.equals(action);
            if(worker==null){stopSelf(startId);return START_NOT_STICKY;}
            if(!noticeSession.equals(intent.getStringExtra("noticeSession")) || stopping.get() ||
                (!USER_PAUSE.equals(action) && (!capturing||!LiveCapture.snapshot().receiving)) ||
                (MISSED.equals(action) && (SpokenTake.status.recording || !getSharedPreferences("recall-speech",0).getBoolean("noticeMissed",false))) ||
                (BEGIN_TAKE.equals(action) && getSharedPreferences("recall-speech",0).getBoolean("noticeMissed",false)) ||
                (take && (intent.getLongExtra("takeGeneration",-1)!=SpokenTake.generation ||
                    BEGIN_TAKE.equals(action)==SpokenTake.status.recording)))return START_NOT_STICKY;
        }
        boolean startLong=BEGIN_LONG.equals(action) && worker==null;
        if(startLong)action=START;
        if(START.equals(action)){
            if(worker==null)noticeSession=UUID.randomUUID().toString();
            Notification n=notification(worker==null?"Preparing on-device listening":current);
            try {
                if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);else startForeground(NOTIFICATION,n);
            } catch(SecurityException|IllegalStateException failure) {
                String detail="Microphone could not start from this session · open Total Recall and check microphone permission";
                getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("active",false).putString("last",detail).commit();
                stopping.set(true);
                if(intent!=null && RESUME_FROM_NOTICE.equals(intent.getAction()))showResume(pausedAt(this),true);
                changed();stopSelf(startId);return START_NOT_STICKY;
            }
            if(worker==null){
                longOnly=startLong;if(startLong)commands.offer(BEGIN_LONG);
                try{if(cues==null)cues=new CaptureCues(this);}catch(RuntimeException ignored){}
                notifications().cancel(RESUME_NOTIFICATION);userPaused=false;ending=false;stopping.set(false);destroyed=false;current="Preparing on-device listening";running=true;LiveCapture.start();RecallOverlay.sessionStarted(this);changed();
                startedElapsed=SystemClock.elapsedRealtime();inputRoute="";saveChoice=SelectiveEngine.SaveChoice.NONE;lastOutcome=CaptureCues.Cue.NO_EVENT;gapOpen=false;worker=new Thread(this::listen,"Recall-Mic");worker.start();
                noticeHandler.removeCallbacks(refreshNotice);noticeHandler.postDelayed(refreshNotice,1000);
            }
        }else if(worker!=null){
            if(REFRESH_NOTICE.equals(action)){noticeSoon();}
            else if(SPEAK_STATUS.equals(action)||REPEAT_RESULT.equals(action)||SPEAK_HELP.equals(action))
                answer(SPEAK_STATUS.equals(action)?"status":REPEAT_RESULT.equals(action)?"repeat":"help",true);
            else if(PAUSE.equals(action)||USER_PAUSE.equals(action)||END.equals(action)){
                ending=END.equals(action);
                userPaused=USER_PAUSE.equals(action);
                stopping.set(true);LiveCapture.stop("Pausing");RecallOverlay.sessionEnded(this);progress("Pausing");
                // stop() also unblocks a native read if the microphone route went away.
                AudioRecord rec=liveRecorder;
                if(rec!=null){try{rec.stop();}catch(IllegalStateException ignored){}}
            }else if(BEGIN_LONG.equals(action)){if(capturing && !stopping.get())commands.offer(action);else LongRecording.message="Wait for microphone input and tap Start again";}
            else if(FINISH_LONG.equals(action)||MARK_LONG.equals(action)){
                String id=intent.getStringExtra("longId");
                if(id!=null && !id.isEmpty() && id.equals(LongRecording.active))commands.offer(action+id);
            }else if(ANSWER_CHOICE.equals(action)){
                String token=intent.getStringExtra("choiceToken"),answer=intent.getStringExtra("choiceAnswer");
                if(capturing && !stopping.get() && LiveCapture.snapshot().receiving &&
                    token!=null && !token.isEmpty() && token.equals(saveChoice.token) && SelectiveEngine.choiceWord(answer))
                    commands.offer(ANSWER_CHOICE+token+"/"+answer);
            }else if(rewindCategory(action)!=null){
                if(capturing && !stopping.get() && LiveCapture.snapshot().receiving)commands.offer(action);
                else {LiveCapture.feedback("Waiting for microphone input · no clip saved");sound(CaptureCues.Cue.NO_AUDIO);noticeSoon();}
            }else if(KEEP.equals(action)||MISSED.equals(action)||DISCARD_LAST.equals(action)||LEARN_ROOM.equals(action)||BEGIN_TAKE.equals(action)||END_TAKE.equals(action))commands.offer(action);
        }else stopSelf(startId);
        return START_NOT_STICKY;
    }
    // Only the microphone worker can consume the original held rewind. A replacement prompt has its own token.
    static boolean applyChoice(SelectiveEngine engine,String command){
        int slash=command.indexOf('/',ANSWER_CHOICE.length());
        return command.startsWith(ANSWER_CHOICE) && slash>ANSWER_CHOICE.length() &&
            engine.answerChoice(command.substring(ANSWER_CHOICE.length(),slash),command.substring(slash+1));
    }
    private static final class SessionIssue extends RuntimeException {
        SessionIssue(String message){super(message);}
        SessionIssue(String message,Throwable cause){super(message,cause);}
    }
    private boolean stillArmed(){
        if(stopping.get())return false;
        if(ListenPolicy.allowed(this))return true;
        stopping.set(true);return false;
    }
    private void waitArmed(long delay){
        long end=SystemClock.elapsedRealtime()+delay;
        while(stillArmed()&&SystemClock.elapsedRealtime()<end){
            SystemClock.sleep(Math.min(150,Math.max(1,end-SystemClock.elapsedRealtime())));
        }
    }
    private static void release(AudioRecord rec){
        if(rec==null)return;
        try{rec.stop();}catch(IllegalStateException ignored){}
        rec.release();
    }
    private AudioRecord openMic(int buffer){
        AudioRecord rec=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,
                                       AudioFormat.ENCODING_PCM_16BIT,buffer);
        try{
            if(rec.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("initialization unavailable");
            rec.startRecording();
            if(rec.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("start unavailable");
            return rec;
        }catch(RuntimeException ex){release(rec);throw ex;}
    }
    /** Finalization of an active selection is explicit on interruption; unselected PCM is always dropped. */
    private final class Window {
        final Store store;
        final JournalStore journal;
        final OfflineSpeech speech;
        final SelectiveEngine.Listener listener;
        final SpokenTake take=new SpokenTake();
        SelectiveEngine engine;
        void flushWords(){flushWords(true);}
        void flushWords(boolean score){
            String words=speech.finish();
            if(words!=null){
                remember();take.words(speech.rawWords());LiveCapture.attributed(words,speech.lastVoice());
                Situation.updateLive(RecallService.this,words,speech.isMedia());
                journal.append(session,epoch,words,false,speech.lastVoice(),speech.rawWords(),speech.checkWords());
                if(score)engine.text(speech.rawWords(),true,!(skipMedia && speech.isMedia()),words);
            }
            speech.newUtterance();LiveCapture.words("",false);
        }
        void saveTake(){
            SelectiveEngine.Clip clip=take.finish();
            if(clip==null){SpokenTake.failed("No audio arrived. Try recording again.");sound(CaptureCues.Cue.NO_AUDIO);return;}
            try{
                long id=store.save(clip);SpokenTake.saved(id);LiveCapture.saved(id,"Voice take",clip.reason,stopping.get() && userPaused);sound(CaptureCues.Cue.TAKE);noticeSoon();
            }catch(java.io.IOException|RuntimeException e){SpokenTake.failed("Could not save the take · "+message(e));sound(CaptureCues.Cue.SAVE_FAILED);return;}
            try{MintSync.schedule(RecallService.this);ArchiveSync.schedule(RecallService.this);}
            catch(RuntimeException e){LiveCapture.feedback("Voice take saved · transfer pending");}
        }
        void takeAction(boolean start){
            if(start==take.active())return;
            flushWords();
            if(start){take.begin();if(take.active())sound(CaptureCues.Cue.TAKE_STARTED);}else saveTake();
            noticeSoon();
        }
        boolean reduceRoom,liftQuiet,skipMedia,deferUntilSpeaker,learnFromSaves;
        long epoch=-1,session=-1,nextPartial,feedbackRevision=-1,nextPrune,nextSensitivity;
        Window(Store s,JournalStore j,OfflineSpeech asr,SelectiveEngine.Listener l){
            store=s;journal=j;speech=asr;listener=l;
        }
        void begin(String route,String resumeNote){
            engine=new SelectiveEngine(listener);feedbackRevision=-1;
            epoch=JournalStore.epoch(RecallService.this);
            session=JournalStore.enabled(RecallService.this)?journal.begin(epoch):-1;
            if(session>=0&&!resumeNote.isEmpty())journal.markResumed(session,resumeNote);
            nextPartial=0;nextSensitivity=0;
            nextPrune=SystemClock.elapsedRealtime()+3600000;
            boolean recovered=gapOpen;gapOpen=false;
            capturing=true;LiveCapture.resume("Listening · "+route);LiveCapture.voiceSession(session,speech.groupsReady());progress("Listening · "+route);
            if(recovered)sound(CaptureCues.Cue.MIC_BACK);
        }
        void remember(){
            VoiceProfiles.Profile p=speech.known();if(p==null)return;
            int id=speech.lastVoice();LiveCapture.remembered(id,JournalStore.voiceLabel(id,p.name,p.source));
            if(session>=0 && JournalStore.enabled(RecallService.this) && epoch==JournalStore.epoch(RecallService.this))
                journal.remember(session,id,p.name,p.source);
        }
        boolean hearingPrompt,atPhoneLimit;
        void tick(short[] data,int count){tick(data,count,false);}
        void tick(short[] data,int count,boolean cueAudio){
            long clock=SystemClock.elapsedRealtime();
            long latest=JournalStore.epoch(RecallService.this);
            if(latest!=epoch){
                engine.closeChoice(null);
                // A decoder can span an opt-in boundary. Finish and reset it before a fresh
                // journal epoch, so previously overheard speech never enters the new journal.
                String before=speech.finish();
                if(before!=null){take.words(speech.rawWords());LiveCapture.attributed(before,speech.lastVoice());engine.text(speech.rawWords(),true,!(skipMedia && speech.isMedia()),before);}
                speech.resetStream();
                journal.finish(session,"Journal setting changed");epoch=latest;
                session=JournalStore.enabled(RecallService.this)?journal.begin(epoch):-1;
                LiveCapture.resume(current);LiveCapture.voiceSession(session,speech.groupsReady());
                if(stopping.get())return;
            }
            if(clock>=nextSensitivity){
                long revision=Store.feedbackRevision();
                boolean learn=getSharedPreferences("recall-detector",MODE_PRIVATE).getBoolean("learnFromSaves",true);
                if(revision!=feedbackRevision || learn!=learnFromSaves){
                    applyFeedback(engine,store,learn);feedbackRevision=revision;learnFromSaves=learn;
                }
                boolean limited=LimitAlerts.message(RecallService.this).startsWith("Phone storage limit");
                if(limited && !atPhoneLimit)sound(CaptureCues.Cue.STORAGE_LIMIT);
                atPhoneLimit=limited;
                reduceRoom=getSharedPreferences("recall-speech",MODE_PRIVATE).getBoolean("reduceRoom",false);
                liftQuiet=getSharedPreferences("recall-speech",MODE_PRIVATE).getBoolean("liftQuiet",true);
                speech.filterCommands=getSharedPreferences("recall-speech",MODE_PRIVATE).getBoolean("commandNoise",false);
                speech.extendedReach=getSharedPreferences("recall-speech",MODE_PRIVATE).getBoolean("extendedReach",false);
                skipMedia=getSharedPreferences("recall-speech",MODE_PRIVATE).getBoolean("skipMedia",true);
                deferUntilSpeaker=skipMedia && speech.groupsReady() && speech.hasMediaProfiles();
                engine.setSensitivity(getSharedPreferences("recall-detector",MODE_PRIVATE).getInt("sensitivity",0));
                nextSensitivity=clock+500;
            }
            if(clock>=nextPrune && session>=0){journal.prune();nextPrune=clock+3600000;}
            LiveCapture.samples(data,count);
            if(cueAudio){
                // Keep source PCM. During spoken playback, no app voice enters ASR, examples or command scoring.
                if(!hearingPrompt){flushWords(false);hearingPrompt=true;}
                engine.cueAudio(count);
                if(take.add(data,count))takeAction(false);
                if(!stopping.get()){engine.pcm(data,count,false);LiveCapture.buffered(engine.bufferedSeconds());}
                return;
            }
            hearingPrompt=false;
            String utterance=speech.accept(data,count,liftQuiet,reduceRoom),command=speech.pollCommand(engine.choicePending());
            if(utterance!=null){
                remember();speech.offerExample();take.words(speech.rawWords());
                LiveCapture.attributed(utterance,speech.lastVoice());
                Situation.updateLive(RecallService.this,utterance,speech.isMedia());
                journal.append(session,epoch,utterance,false,speech.lastVoice(),speech.rawWords(),speech.checkWords());
            }else if(clock>=nextPartial){
                nextPartial=clock+140;String partial=speech.partial();
                LiveCapture.words(partial,false);
                if(command==null && partial!=null)engine.text(speech.rawPartial(),false,!deferUntilSpeaker);
            }
            if(utterance!=null)engine.text(speech.rawWords(),true,!(skipMedia && speech.isMedia()),utterance);
            if(command!=null && (utterance==null || !command.equals(speech.rawWords())) && !stopping.get())engine.text(command,true);
            if(take.add(data,count))takeAction(false);
            if(!stopping.get()){
                engine.pcm(data,count,liftQuiet && speech.faintActivity());LiveCapture.buffered(engine.bufferedSeconds());
            }
        }
        void end(boolean gap,String reason){
            PhraseCheckInput.gap();
            capturing=false;
            if(engine==null)return;
            engine.closeChoice(null);hearingPrompt=false;
            try{
                // Consume the recognizer tail once. Never persist an utterance from before opt-in,
                // after opt-out, or while app-wide capture policy is disabled.
                if(ListenPolicy.allowed(RecallService.this)){
                    String partial=speech.partial(),rawPartial=speech.rawPartial();
                    String finalWords=speech.finish();
                    if(finalWords!=null){
                        remember();take.words(speech.rawWords());journal.append(session,epoch,finalWords,false,speech.lastVoice(),speech.rawWords(),speech.checkWords());
                        if(gap && !stopping.get())engine.text(speech.rawWords(),true,!(skipMedia && speech.isMedia()),finalWords);
                    }else if(partial!=null){take.words(rawPartial);journal.append(session,epoch,partial,true,0,rawPartial);}
                    if(take.active())saveTake();
                    if((gap && !stopping.get()) || (userPaused && engine.passageOpen()) || (ending && engine.isSelecting()))engine.finish();
                }
            }finally{
                if(take.active())take.release();
                engine.pause();engine=null;speech.resetStream();
                journal.finish(session,reason);session=-1;
            }
        }
    }
    private void listen(){
        final Context app=getApplicationContext();
        SharedPreferences prefs=app.getSharedPreferences(PREFS,MODE_PRIVATE);
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        PowerManager.WakeLock lock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,getPackageName()+":selective-listening");
        lock.setReferenceCounted(false);
        String end="Paused";
        AudioRecord rec=null;
        try(Store store=new Store(app);JournalStore journal=new JournalStore(app);
                ArchiveStore archiveStore=new ArchiveStore(app);ArchiveRecorder archive=new ArchiveRecorder(app,archiveStore);LongRecording sessionAudio=new LongRecording(app,archiveStore)){
            try{
                LongRecording.recover(app,archiveStore);
                if(!ListenPolicy.allowed(app))throw new SessionIssue("Total Recall is paused; enable capture before starting.");
                if(prefs.getBoolean("active",false))store.gap("Prior listening session was interrupted; coverage after its last update is unknown.");
                prefs.edit().putBoolean("active",true).commit();store.recover();journal.recover();lock.acquire();
                final long[] lastSaved={-1L};
                SelectiveEngine.Listener listener=new SelectiveEngine.Listener(){
                    String choiceSession="";
                    long choicePosition;
                    @Override public void onChoiceState(SelectiveEngine.SaveChoice choice){saveChoice=choice;}
                    @Override public void onSpokenRequest(String action){
                        switch(action){
                            case "choose":choiceSession=LongRecording.active;choicePosition=LongRecording.duration;break;
                            case "choiceClosed":choiceSession="";choicePosition=0;break;
                            case "ready":speakWake(true);break;
                            case "status":case "repeat":case "help":answer(action,false);break;
                        }
                    }
                    @Override public void onClip(SelectiveEngine.Clip clip){
                        if(!ListenPolicy.allowed(RecallService.this) || (stopping.get() && !userPaused))return;
                        LiveCapture.feedback("Writing clip to phone…");
                        try{lastSaved[0]=store.save(clip);LiveCapture.saved(lastSaved[0],clip.category,clip.reason,stopping.get() && userPaused);progress("Saved · "+clip.category);}
                        catch(IOException|RuntimeException e){sound(CaptureCues.Cue.SAVE_FAILED);throw new SessionIssue("Clip could not be saved: "+message(e),e);}
                        boolean markFailed=false;
                        if(clip.labeledRewind){
                            String target=clip.fromSpokenChoice?choiceSession:LongRecording.active;
                            if(!target.isEmpty()){
                                long previous=clip.fromSpokenChoice?choicePosition:LongRecording.duration;
                                long leadIn=Math.min(8000,clip.audio.length*1000L/SelectiveEngine.RATE);
                                long at=Math.max(0,previous-leadIn);
                                boolean marked=sessionAudio.markAt(target,at,clip.category);
                                markFailed=!marked;
                                LiveCapture.feedback(clip.category+" saved · "+(marked?"session marker at "+LongRecording.time(at):"session marker failed"));
                            }
                        }
                        boolean requested=clip.labeledRewind || clip.finishedByVoice || clip.category.contains(SelectiveEngine.MANUAL)||clip.category.contains(SelectiveEngine.MISSED);
                        if(!requested)sound(CaptureCues.category(clip.category),true);
                        if(requested){
                            sound(markFailed?CaptureCues.Cue.MARKER_PART:CaptureCues.category(clip.category));
                            try {Vibrator h=(Vibrator)getSystemService(VIBRATOR_SERVICE);if(h!=null&&h.hasVibrator())h.vibrate(45);}
                            catch(SecurityException ignored){}
                        }
                        try{MintSync.schedule(app);}catch(RuntimeException ignored){progress("Saved · Mint sync pending");}
                    }
                    @Override public void onVoice(String message,boolean wake){
                        LiveCapture.feedback(message);
                        if(wake)speakWake(false);
                        else if(message.startsWith("Command unclear"))sound(CaptureCues.Cue.UNCLEAR);
                        else if(message.startsWith("Wake window ended"))sound(CaptureCues.Cue.WAKE_EXPIRED);
                        else if(message.startsWith("No audio buffered"))sound(CaptureCues.Cue.NO_AUDIO);
                        else if(message.equals("Choose a save category"))sound(CaptureCues.Cue.CHOICE);
                        else if(message.equals("Choice canceled"))sound(CaptureCues.Cue.CANCELED);
                        else if(message.equals("Save choice expired"))sound(CaptureCues.Cue.EXPIRED);
                        else if(message.equals("No passage is open"))sound(CaptureCues.Cue.NO_PASSAGE);
                        noticeSoon();
                    }
                    @Override public void onSession(int action){
                        if(action==5)commands.offer(BEGIN_LONG);
                        else if(!LongRecording.active.isEmpty())commands.offer((action==6?FINISH_LONG:MARK_LONG)+LongRecording.active);
                        else {LiveCapture.feedback("No long session is recording");sound(CaptureCues.Cue.NO_SESSION);}
                    }
                    @Override public void onFlag(String category){LiveCapture.flag(category);noticeSoon();}
                    @Override public void onPauseRequested(){userPaused=true;stopping.set(true);}
                };
                try(OfflineSpeech speech=new OfflineSpeech(app)){
                    try(java.io.InputStream dictionary=getAssets().open("listening/cmudict-prosody.tsv.gz")){SelectiveEngine.loadPronunciations(dictionary);}
                    int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                    if(min<=0)throw new SessionIssue("Microphone cannot open at 16 kHz");
                    AudioManager manager=(AudioManager)getSystemService(AUDIO_SERVICE);
                    Window window=new Window(store,journal,speech,listener);
                    short[] frame=new short[1600];MicrophoneContinuity continuity=new MicrophoneContinuity();
                    long consumed=0,recorderEpoch=0;
                    AudioTimestamp timestamp=new AudioTimestamp();
                    long priorPause=pausedAt(app);
                    long retry=500,zeroSince=0;boolean selecting=false;String route="",resumeNote="";int routedId=-1;
                    try {
                        while(stillArmed()){
                            if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                                throw new SessionIssue("Microphone permission was revoked. Grant it in Settings, then start a new session.");
                            String unavailable=null;int count=0;boolean broken=false;
                            if(manager.isMicrophoneMute())unavailable="microphone muted or privacy switch active";
                            if(rec==null && unavailable==null){
                                try{
                                    rec=openMic(Math.max(min*3,256000));liveRecorder=rec;consumed=0;recorderEpoch=System.nanoTime();
                                    if(!stillArmed())break;
                                }catch(IllegalStateException e){unavailable="microphone unavailable";}
                            }
                            if(rec!=null && unavailable==null && Build.VERSION.SDK_INT>=29){
                                AudioRecordingConfiguration config=rec.getActiveRecordingConfiguration();
                                if(config!=null && config.isClientSilenced())unavailable="another app or call has microphone access";
                            }
                            if(unavailable==null){
                                // Non-blocking I/O lets pause and policy changes interrupt a stalled device route.
                                count=rec.read(frame,0,frame.length,AudioRecord.READ_NON_BLOCKING);
                                consumed+=Math.max(0,count);
                                if(count<0){unavailable="input route interrupted ("+count+")";broken=true;}
                                else if(count==0){
                                    PhraseCheckInput.drained();
                                    recorderEpoch=System.nanoTime()-consumed*SAMPLE_NANOS;
                                    long now=SystemClock.elapsedRealtime();
                                    if(zeroSince==0)zeroSince=now;
                                    if(now-zeroSince<5000){waitArmed(25);continue;}
                                    unavailable="recorder produced no audio";broken=true;
                                }else if(Build.VERSION.SDK_INT>=29){
                                    AudioRecordingConfiguration config=rec.getActiveRecordingConfiguration();
                                    if(config!=null && config.isClientSilenced())unavailable="another app or call has microphone access";
                                }
                            }
                            if(count>0 || unavailable!=null)zeroSince=0;
                            if(unavailable!=null){
                                WearableMicCheck.unavailable();inputRoute="";
                                boolean sealedSession=sessionAudio.finish("Microphone interrupted");
                                if(sealedSession){LiveCapture.feedback(LongRecording.message);sound(CaptureCues.Cue.SESSION_INTERRUPTED);noticeSoon();}
                                if(commands.remove(BEGIN_LONG))LongRecording.message="Microphone unavailable · tap Start when ready";
                                if(longOnly)command(this,USER_PAUSE);
                                archive.interrupted("Microphone unavailable · "+unavailable);
                                if(continuity.lose(SystemClock.elapsedRealtime())){
                                    String waiting="Waiting for microphone · "+unavailable;
                                    window.end(true,"Microphone gap: "+unavailable);
                                    gapOpen=true;
                                    LiveCapture.waitFor(waiting);progress(waiting);commands.clear();
                                    if(!sealedSession)sound(unavailable.contains("muted")?CaptureCues.Cue.MIC_MUTED:CaptureCues.Cue.MIC_WAIT);
                                    store.gap("Microphone gap began · "+unavailable);
                                }
                                if(broken){liveRecorder=null;release(rec);rec=null;}
                                if(rec!=null){
                                    // Keep our original (silenced) recorder. Starting a new recorder can
                                    // compete with voice mode in the other app. Drain & discard its backlog.
                                    for(int passes=0;passes<24;passes++){
                                        int dropped=rec.read(frame,0,frame.length,AudioRecord.READ_NON_BLOCKING);
                                        if(dropped<=0)break;consumed+=dropped;
                                    }
                                    Arrays.fill(frame,(short)0);
                                    waitArmed(650);
                                }else{
                                    waitArmed(retry);retry=Math.min(8000,retry*2);
                                }
                                continue;
                            }
                            if(!stillArmed())break;
                            if(continuity.waiting()){
                                long lost=continuity.resume(SystemClock.elapsedRealtime());
                                resumeNote="Resumed after a microphone gap of "+lost/1000+" s";
                                store.gap("Microphone gap ended · resumed after "+lost/1000+" s");
                                // The current block may predate recovery. Drain older queued audio too.
                                for(int passes=0;passes<96;passes++){
                                    int dropped=rec.read(frame,0,frame.length,AudioRecord.READ_NON_BLOCKING);
                                    if(dropped<=0)break;consumed+=dropped;
                                }
                                Arrays.fill(frame,(short)0);commands.clear();continue;
                            }
                            retry=500;
                            if(priorPause>0){
                                String pauseNote="Resumed after a manual pause of "+Math.max(0,System.currentTimeMillis()-priorPause)/1000+" s";
                                store.gap(pauseNote);resumeNote=resumeNote.isEmpty()?pauseNote:pauseNote+" · "+resumeNote;
                                prefs.edit().remove("pausedAt").commit();priorPause=0;
                            }
                            boolean wasLong=!LongRecording.active.isEmpty();
                            sessionAudio.accept(frame,count);
                            if(wasLong && LongRecording.active.isEmpty()){if(longOnly)command(this,USER_PAUSE);LiveCapture.feedback(LongRecording.message);
                                sound(LongRecording.message.contains("Storage limit")?CaptureCues.Cue.STORAGE_LIMIT:CaptureCues.Cue.SESSION_CHECK);noticeSoon();}
                            archive.accept(frame,count);
                            AudioDeviceInfo device=rec.getRoutedDevice();
                            String next=route(device);int nextId=device==null?-1:device.getId();inputRoute=next;
                            if(window.engine==null){window.begin(next,resumeNote);resumeNote="";selecting=false;route=next;}
                            else if(nextId!=routedId || !next.equals(route)){
                                PhraseCheckInput.gap();
                                speech.clearRoom();
                                store.gap(next.equals(route)?"Input device changed · "+next:"Input changed from "+route+" to "+next);
                                route=next;progress("Listening · "+route);
                            }
                            routedId=nextId;
                            WearableMicCheck.onFrame(rec,device,next,frame,count);
                            boolean cueAudio=promptInFrame(rec,timestamp,consumed,count,recorderEpoch);
                            PhraseCheckInput.frame(frame,count,cueAudio);
                            window.tick(frame,count,cueAudio);
                            if(stopping.get())break;
                            String action;
                            while((action=commands.poll())!=null){
                                if(BEGIN_LONG.equals(action)){
                                    if(LongRecording.active.isEmpty()){sessionAudio.begin();LiveCapture.feedback(LongRecording.message);if(!LongRecording.active.isEmpty())sound(CaptureCues.Cue.STARTED);else {sound(CaptureCues.Cue.SESSION_FAILED);if(longOnly)command(this,USER_PAUSE);}noticeSoon();}
                                }else if(action.startsWith(FINISH_LONG)||action.startsWith(MARK_LONG)){
                                    String id=LongRecording.active;if(id.isEmpty())continue;
                                    if(action.equals(FINISH_LONG+id)){sound(sessionAudio.finish("Finished")?CaptureCues.Cue.ENDED:CaptureCues.Cue.SESSION_CHECK);if(longOnly)command(this,USER_PAUSE);}
                                    else if(action.equals(MARK_LONG+id)){sound(sessionAudio.mark("",0)?CaptureCues.Cue.MARKED:CaptureCues.Cue.MARK_FAILED);}
                                    LiveCapture.feedback(LongRecording.message);noticeSoon();
                                }else if(BEGIN_TAKE.equals(action)||END_TAKE.equals(action))window.takeAction(BEGIN_TAKE.equals(action));
                                else if(LEARN_ROOM.equals(action)){
                                    if(getSharedPreferences("recall-speech",MODE_PRIVATE).getBoolean("reduceRoom",false)){
                                        window.reduceRoom=true;speech.learnRoom();
                                    }
                                }else if(action.startsWith(ANSWER_CHOICE)){
                                    if(stillArmed())applyChoice(window.engine,action);
                                }else if(rewindCategory(action)!=null)window.engine.keepAs(rewindCategory(action));
                                else if(KEEP.equals(action))window.engine.keep();
                                else if(MISSED.equals(action))window.engine.missed();
                                else if(DISCARD_LAST.equals(action)&&lastSaved[0]>=0){
                                    store.delete(lastSaved[0]);lastSaved[0]=-1;progress("Latest saved clip removed");
                                }
                            }
                            if(selecting!=window.engine.isSelecting()){
                                selecting=window.engine.isSelecting();progress(selecting?"Keeping a moment · "+route:"Listening · "+route);
                            }
                        }
                    }finally{
                        try{archive.boundary("Listening ended");window.end(false,stopping.get()?"Paused":"Session ended");}
                        finally{
                            long lost=continuity.stop(SystemClock.elapsedRealtime());
                            if(lost>=0)store.gap("Microphone gap ended · session stopped after "+lost/1000+" s");
                            Arrays.fill(frame,(short)0);
                        }
                    }
                }
            }catch(Exception|LinkageError failure){
                if(!stopping.get()){
                    end="Listening stopped · "+message(failure);
                    try{store.gap(end);}catch(RuntimeException ignored){}
                }
            }
        }catch(RuntimeException failure){end="Listening stopped · "+message(failure);}
        finally{
            WearableMicCheck.unavailable();saveChoice=SelectiveEngine.SaveChoice.NONE;inputRoute="";liveRecorder=null;release(rec);if(lock.isHeld())lock.release();
            if(end.startsWith("Listening stopped") && lastOutcome!=CaptureCues.Cue.SAVE_FAILED)sound(CaptureCues.Cue.STOPPED);
            if(userPaused)sound(CaptureCues.Cue.PAUSED);
            String result=end;LiveCapture.stop(result);RecallOverlay.sessionEnded(this);
            new Handler(Looper.getMainLooper()).post(()->{
                worker=null;commands.clear();prefs.edit().putBoolean("active",false).putString("last",result).commit();
                if(userPaused && ListenPolicy.allowed(this))prefs.edit().putLong("pausedAt",System.currentTimeMillis())
                    .putLong("pausedSeconds",Math.max(0,SystemClock.elapsedRealtime()-startedElapsed)/1000).putInt("pausedClips",LiveCapture.snapshot().savedCount).putLong("pausedWords",LiveCapture.snapshot().words).putInt("pausedFlags",LiveCapture.snapshot().flags).commit();
                current=result;running=false;
                if(ending)android.widget.Toast.makeText(this,"Recording stopped · Total Recall closed",android.widget.Toast.LENGTH_SHORT).show();
                if(!destroyed){stopForeground(true);if(pausedAt(this)>0)showResume(pausedAt(this),false);changed();stopSelf(lastStart);}
            });
        }
    }
    private static boolean promptInFrame(AudioRecord recorder,AudioTimestamp stamp,long consumed,int count,long recorderEpoch){
        if(!CuePlaybackGate.recent())return false;
        // A buffered recorder may deliver the app's voice after playback has already finished.
        int result;
        try{result=recorder.getTimestamp(stamp,AudioTimestamp.TIMEBASE_MONOTONIC);}
        catch(RuntimeException missing){result=AudioRecord.ERROR;}
        if(result==AudioRecord.SUCCESS){
            long end=stamp.nanoTime+(consumed-stamp.framePosition)*SAMPLE_NANOS;
            long now=System.nanoTime();
            if(end<=now+100_000_000L && now-end<10_000_000_000L){
                long start=end-count*SAMPLE_NANOS;
                return CuePlaybackGate.overlaps(start,end);
            }
        }
        return promptWithoutTimestamp(recorderEpoch+consumed*SAMPLE_NANOS,count,System.nanoTime());
    }
    static boolean promptWithoutTimestamp(long estimatedEnd,int count,long delivered){
        // Between nonblocking reads that find the input empty, the sample clock tracks queued PCM.
        // Include the interval to delivery when a vendor cannot timestamp its recorder.
        long end=Math.min(delivered,Math.max(delivered-8_000_000_000L,estimatedEnd));
        return CuePlaybackGate.overlaps(end-count*SAMPLE_NANOS,delivered);
    }
    private static String message(Throwable e){
        String text=e.getMessage();
        if(text==null||text.trim().isEmpty())return e.getClass().getSimpleName();
        return text.length()>180?text.substring(0,180):text;
    }
    private static void applyFeedback(SelectiveEngine engine,Store store,boolean learn){
        Map<String,Integer> weights=learn?store.weights():java.util.Collections.emptyMap();
        for(String key:new String[]{SelectiveEngine.FORGE,SelectiveEngine.POETRY,SelectiveEngine.DECISION,SelectiveEngine.CONNECTION}){
            int net=0;
            for(Map.Entry<String,Integer> vote:weights.entrySet())if(vote.getKey().contains(key))net+=vote.getValue();
            engine.setFeedback(key,net);
        }
        engine.setExamples(learn?store.examples():java.util.Collections.emptyList());
    }
    private static String route(AudioDeviceInfo device){
        if(device==null)return "input route unreported";
        switch(device.getType()){
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:return "phone microphone";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:case AudioDeviceInfo.TYPE_BLE_HEADSET:return "Bluetooth microphone";
            case AudioDeviceInfo.TYPE_USB_DEVICE:case AudioDeviceInfo.TYPE_USB_ACCESSORY:case AudioDeviceInfo.TYPE_USB_HEADSET:return "USB microphone";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:return "headset microphone";
            default:return "audio input "+device.getType();
        }
    }
    @Override public void onDestroy(){
        noticeHandler.removeCallbacksAndMessages(null);destroyed=true;if(cues!=null){cues.finishPending();cues=null;}stopping.set(true);LiveCapture.stop("Paused");RecallOverlay.afterService(this);
        AudioRecord rec=liveRecorder;
        if(rec!=null){try{rec.stop();}catch(IllegalStateException ignored){}}
        super.onDestroy();
    }
}
