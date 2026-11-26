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
    public static final String START=NS+"START",PAUSE=NS+"PAUSE",USER_PAUSE=NS+"USER_PAUSE",KEEP=NS+"KEEP",
        MISSED=NS+"MISSED",DISCARD_LAST=NS+"DISCARD_LAST",ACTION_CHANGED=NS+"CHANGED";
    private static final String CHANNEL="total_recall_listening";
    private static final String PREFS="total_recall_session";
    private static final int NOTIFICATION=9021, RESUME_NOTIFICATION=9022;
    private volatile boolean userPaused;
    private static volatile boolean running;
    private static volatile String current="";
    private final ConcurrentLinkedQueue<String> commands=new ConcurrentLinkedQueue<>();
    private final AtomicBoolean stopping=new AtomicBoolean(false);
    private volatile AudioRecord liveRecorder;
    private volatile boolean capturing;
    private volatile boolean destroyed;
    private Thread worker;
    private int lastStart;
    private long startedElapsed;
    private volatile String inputRoute="";
    private final Handler noticeHandler=new Handler(Looper.getMainLooper());
    private final Runnable refreshNotice=new Runnable(){public void run(){
        if(!running || destroyed || stopping.get())return;
        try{notifications().notify(NOTIFICATION,notification(current));}catch(SecurityException ignored){}
        noticeHandler.postDelayed(this,5000);
    }};
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
    private void showResume(long since){
        if(since<=0||!ListenPolicy.allowed(this))return;
        Intent open=new Intent(this,RecallListenActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("overlayStart",true).putExtra("resumeAt",since);
        PendingIntent tap=PendingIntent.getActivity(this,203,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        String paused="Microphone off since "+java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new Date(since));
        String summary=paused+"\nSession "+elapsed(prefs.getLong("pausedSeconds",0))+" · "+prefs.getInt("pausedClips",0)+" clips saved\n"+captureSettings()+"\n"+backupStatus();
        Notification n=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_pause)
            .setStyle(new Notification.BigTextStyle().bigText(summary)).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentTitle("Total Recall paused").setContentText("Microphone off · tap to resume listening")
            .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_play,"Resume",tap).addAction(android.R.drawable.ic_menu_agenda,"Live & saved",reviewIntent()).build();
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
        if(START.equals(action)&&Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);
    }
    @Override public IBinder onBind(Intent intent){return null;}
    private void changed(){
        if(!destroyed)sendBroadcast(new Intent(ACTION_CHANGED).setPackage(getPackageName()));
    }
    private NotificationManager notifications(){return (NotificationManager)getSystemService(NOTIFICATION_SERVICE);}
    private Notification notification(String label){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel(CHANNEL,"Selective listening",NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Visible controls for the microphone session");notifications().createNotificationChannel(ch);
        }
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        int flags=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE;
        LiveCapture.Snapshot snapshot=LiveCapture.snapshot();
        boolean receiving=snapshot.receiving && capturing && !stopping.get();
        String headline=snapshot.waiting?"Waiting for microphone":receiving?"Listening · "+inputRoute:label;
        if(headline==null||headline.isEmpty())headline="Preparing microphone";
        StringBuilder details=new StringBuilder(headline);
        if(snapshot.active)details.append("\n").append(elapsed(snapshot.sessionSeconds)).append(" elapsed · ").append(snapshot.savedCount).append(" clips saved");
        if(receiving)details.append(" · Mic ").append(Math.round(snapshot.level*100)).append('%');
        if(snapshot.waiting && !snapshot.state.isEmpty())details.append("\n").append(snapshot.state);
        if(!snapshot.lastSave.isEmpty())details.append("\nLatest clip · ").append(snapshot.lastSave);
        details.append("\n").append(captureSettings()).append("\n").append(backupStatus());
        if(snapshot.active && !snapshot.waiting && getSharedPreferences("recall-speech",0).getBoolean("noticeWords",false)){
            String line=snapshot.partial.isEmpty()?(snapshot.finals.isEmpty()?"":snapshot.attributed(snapshot.finals.size()-1)):snapshot.partial;
            if(!line.isEmpty()){
                try{
                    Situation situation=new Situation(this);for(String finalLine:snapshot.finals)situation.add(finalLine,"");
                    if(!situation.cue().isEmpty())details.append("\n").append(situation.interpretation());
                }catch(IOException ignored){}
                details.append(snapshot.partial.isEmpty()?"\nHeard · ":"\nIn progress · ").append(line.substring(0,Math.min(line.length(),180)));
            }
        }
        int icon=getResources().getIdentifier("ic_listening","drawable",getPackageName());
        String shortStatus=headline+(snapshot.active?" · "+snapshot.savedCount+" clips":"");
        b.setContentTitle("Total Recall").setContentText(shortStatus).setSmallIcon(icon!=0?icon:android.R.drawable.ic_btn_speak_now)
            .setStyle(new Notification.BigTextStyle().bigText(details)).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).setContentIntent(reviewIntent());
        if(snapshot.active)b.setWhen(System.currentTimeMillis()-snapshot.sessionSeconds*1000L).setUsesChronometer(true);
        String[] actions=capturing&&!stopping.get()?new String[]{KEEP,USER_PAUSE}:new String[]{USER_PAUSE};
        for(String act:actions){
            boolean keep=KEEP.equals(act);Intent i=new Intent(this,RecallService.class).setAction(act);
            b.addAction(keep?android.R.drawable.ic_menu_save:android.R.drawable.ic_media_pause,
                keep?"Keep last minute":"Pause",PendingIntent.getService(this,keep?201:202,i,flags));
        }b.addAction(android.R.drawable.ic_menu_agenda,"Live & saved",reviewIntent());return b.build();
    }
    private void progress(String message){
        current=message;LiveCapture.state(message); changed();
        if(!destroyed && running){
            try{notifications().notify(NOTIFICATION,notification(message));}catch(SecurityException ignored){}
        }
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        lastStart=startId;
        String action=intent==null?null:intent.getAction();
        if(START.equals(action)){
            Notification n=notification(worker==null?"Preparing on-device listening":current);
            try {
                if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);else startForeground(NOTIFICATION,n);
            } catch(SecurityException|IllegalStateException failure) {
                String detail="Microphone could not start from this session · open Total Recall and check microphone permission";
                getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("active",false).putString("last",detail).commit();
                stopping.set(true);
                changed();stopSelf(startId);return START_NOT_STICKY;
            }
            if(worker==null){
                notifications().cancel(RESUME_NOTIFICATION);userPaused=false;stopping.set(false);destroyed=false;current="Preparing on-device listening";running=true;LiveCapture.start();RecallOverlay.sessionStarted(this);changed();
                startedElapsed=SystemClock.elapsedRealtime();inputRoute="";worker=new Thread(this::listen,"Recall-Mic");worker.start();
                noticeHandler.removeCallbacks(refreshNotice);noticeHandler.postDelayed(refreshNotice,5000);
            }
        }else if(worker!=null){
            if(PAUSE.equals(action)||USER_PAUSE.equals(action)){
                userPaused=USER_PAUSE.equals(action);
                stopping.set(true);LiveCapture.stop("Pausing");RecallOverlay.sessionEnded(this);progress("Pausing");
                // stop() also unblocks a native read if the microphone route went away.
                AudioRecord rec=liveRecorder;
                if(rec!=null){try{rec.stop();}catch(IllegalStateException ignored){}}
            }else if(KEEP.equals(action)||MISSED.equals(action)||DISCARD_LAST.equals(action))commands.offer(action);
        }else stopSelf(startId);
        return START_NOT_STICKY;
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
        SelectiveEngine engine;
        boolean liftQuiet;
        long epoch=-1,session=-1,nextPartial,nextFeedback,nextPrune,nextSensitivity;
        Window(Store s,JournalStore j,OfflineSpeech asr,SelectiveEngine.Listener l){
            store=s;journal=j;speech=asr;listener=l;
        }
        void begin(String route,String resumeNote){
            engine=new SelectiveEngine(listener);applyFeedback(engine,store);
            epoch=JournalStore.epoch(RecallService.this);
            session=JournalStore.enabled(RecallService.this)?journal.begin(epoch):-1;
            if(session>=0&&!resumeNote.isEmpty())journal.markResumed(session,resumeNote);
            nextPartial=0;nextSensitivity=0;nextFeedback=SystemClock.elapsedRealtime()+15000;
            nextPrune=SystemClock.elapsedRealtime()+3600000;
            capturing=true;LiveCapture.resume("Listening · "+route);LiveCapture.voiceSession(session,speech.groupsReady());progress("Listening · "+route);
        }
        void tick(short[] data,int count){
            long clock=SystemClock.elapsedRealtime();
            long latest=JournalStore.epoch(RecallService.this);
            if(latest!=epoch){
                // A decoder can span an opt-in boundary. Finish and reset it before a fresh
                // journal epoch, so previously overheard speech never enters the new journal.
                String before=speech.finish();
                if(before!=null){LiveCapture.attributed(before,speech.lastVoice());engine.text(before,true);}
                speech.resetStream();
                journal.finish(session,"Journal setting changed");epoch=latest;
                session=JournalStore.enabled(RecallService.this)?journal.begin(epoch):-1;
                LiveCapture.resume(current);LiveCapture.voiceSession(session,speech.groupsReady());
                if(stopping.get())return;
            }
            if(clock>=nextFeedback){applyFeedback(engine,store);nextFeedback=clock+15000;}
            if(clock>=nextSensitivity){
                liftQuiet=getSharedPreferences("recall-speech",MODE_PRIVATE).getBoolean("liftQuiet",true);
                engine.setSensitivity(getSharedPreferences("recall-detector",MODE_PRIVATE).getInt("sensitivity",0));
                nextSensitivity=clock+500;
            }
            if(clock>=nextPrune && session>=0){journal.prune();nextPrune=clock+3600000;}
            LiveCapture.samples(data,count);
            String utterance=speech.accept(data,count,liftQuiet),command=speech.pollCommand();
            if(utterance!=null){
                LiveCapture.attributed(utterance,speech.lastVoice());
                journal.append(session,epoch,utterance,false,speech.lastVoice());
            }else if(clock>=nextPartial){
                nextPartial=clock+140;String partial=speech.partial();
                LiveCapture.words(partial,false);
                if(command==null && partial!=null)engine.text(partial,false);
            }
            if(command!=null)engine.text(command,true);
            else if(utterance!=null)engine.text(utterance,true);
            if(!stopping.get())engine.pcm(data,count);
        }
        void end(boolean gap,String reason){
            capturing=false;
            if(engine==null)return;
            try{
                // Consume the recognizer tail once. Never persist an utterance from before opt-in,
                // after opt-out, or while app-wide capture policy is disabled.
                if(ListenPolicy.allowed(RecallService.this)){
                    String partial=speech.partial();
                    String finalWords=speech.finish();
                    if(finalWords!=null){
                        journal.append(session,epoch,finalWords,false,speech.lastVoice());
                        if(gap && !stopping.get())engine.text(finalWords,true);
                    }else if(partial!=null){journal.append(session,epoch,partial,true);}
                    if(gap && !stopping.get())engine.finish();
                }
            }finally{
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
                ArchiveStore archiveStore=new ArchiveStore(app);ArchiveRecorder archive=new ArchiveRecorder(app,archiveStore)){
            try{
                if(!ListenPolicy.allowed(app))throw new SessionIssue("Total Recall is paused; enable capture before starting.");
                if(prefs.getBoolean("active",false))store.gap("Prior listening session was interrupted; coverage after its last update is unknown.");
                prefs.edit().putBoolean("active",true).commit();store.recover();journal.recover();lock.acquire();
                final long[] lastSaved={-1L};
                SelectiveEngine.Listener listener=new SelectiveEngine.Listener(){
                    @Override public void onClip(SelectiveEngine.Clip clip){
                        if(!stillArmed())return;
                        try{lastSaved[0]=store.save(clip);LiveCapture.saved(clip.category,clip.reason);progress("Saved · "+clip.category);}
                        catch(IOException|RuntimeException e){throw new SessionIssue("Clip could not be saved: "+message(e),e);}
                        if(clip.category.contains(SelectiveEngine.MANUAL)||clip.category.contains(SelectiveEngine.MISSED)){
                            try {Vibrator h=(Vibrator)getSystemService(VIBRATOR_SERVICE);if(h!=null&&h.hasVibrator())h.vibrate(45);}
                            catch(SecurityException ignored){}
                        }
                        try{MintSync.schedule(app);}catch(RuntimeException ignored){progress("Saved · Mint sync pending");}
                    }
                    @Override public void onPauseRequested(){userPaused=true;stopping.set(true);}
                };
                try(OfflineSpeech speech=new OfflineSpeech(app)){
                    try(java.io.InputStream dictionary=getAssets().open("listening/cmudict-prosody.tsv.gz")){SelectiveEngine.loadPronunciations(dictionary);}
                    int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                    if(min<=0)throw new SessionIssue("Microphone cannot open at 16 kHz");
                    AudioManager manager=(AudioManager)getSystemService(AUDIO_SERVICE);
                    Window window=new Window(store,journal,speech,listener);
                    short[] frame=new short[1600];MicrophoneContinuity continuity=new MicrophoneContinuity();
                    long priorPause=pausedAt(app);
                    long retry=500,zeroSince=0;boolean selecting=false;String route="",resumeNote="";
                    try {
                        while(stillArmed()){
                            if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                                throw new SessionIssue("Microphone permission was revoked. Grant it in Settings, then start a new session.");
                            String unavailable=null;int count=0;boolean broken=false;
                            if(manager.isMicrophoneMute())unavailable="microphone muted or privacy switch active";
                            if(rec==null && unavailable==null){
                                try{
                                    rec=openMic(Math.max(min*3,256000));liveRecorder=rec;
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
                                if(count<0){unavailable="input route interrupted ("+count+")";broken=true;}
                                else if(count==0){
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
                                archive.interrupted("Microphone unavailable · "+unavailable);
                                if(continuity.lose(SystemClock.elapsedRealtime())){
                                    String waiting="Waiting for microphone · "+unavailable;
                                    window.end(true,"Microphone gap: "+unavailable);
                                    LiveCapture.waitFor(waiting);progress(waiting);commands.clear();
                                    store.gap("Microphone gap began · "+unavailable);
                                }
                                if(broken){liveRecorder=null;release(rec);rec=null;}
                                if(rec!=null){
                                    // Keep our original (silenced) recorder. Starting a new recorder can
                                    // compete with voice mode in the other app. Drain & discard its backlog.
                                    for(int passes=0;passes<24;passes++){
                                        if(rec.read(frame,0,frame.length,AudioRecord.READ_NON_BLOCKING)<=0)break;
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
                                for(int passes=0;passes<96;passes++)if(rec.read(frame,0,frame.length,AudioRecord.READ_NON_BLOCKING)<=0)break;
                                Arrays.fill(frame,(short)0);commands.clear();continue;
                            }
                            retry=500;
                            if(priorPause>0){
                                String pauseNote="Resumed after a manual pause of "+Math.max(0,System.currentTimeMillis()-priorPause)/1000+" s";
                                store.gap(pauseNote);resumeNote=resumeNote.isEmpty()?pauseNote:pauseNote+" · "+resumeNote;
                                prefs.edit().remove("pausedAt").commit();priorPause=0;
                            }
                            archive.accept(frame,count);
                            String next=route(rec);inputRoute=next;
                            if(window.engine==null){window.begin(next,resumeNote);resumeNote="";selecting=false;route=next;}
                            else if(!next.equals(route)){store.gap("Input changed from "+route+" to "+next);route=next;progress("Listening · "+route);}
                            window.tick(frame,count);
                            if(stopping.get())break;
                            String action;
                            while((action=commands.poll())!=null){
                                if(KEEP.equals(action))window.engine.keep();
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
            liveRecorder=null;release(rec);if(lock.isHeld())lock.release();
            String result=end;LiveCapture.stop(result);RecallOverlay.sessionEnded(this);
            new Handler(Looper.getMainLooper()).post(()->{
                worker=null;commands.clear();prefs.edit().putBoolean("active",false).putString("last",result).commit();
                if(userPaused && ListenPolicy.allowed(this))prefs.edit().putLong("pausedAt",System.currentTimeMillis())
                    .putLong("pausedSeconds",Math.max(0,SystemClock.elapsedRealtime()-startedElapsed)/1000).putInt("pausedClips",LiveCapture.snapshot().savedCount).commit();
                current=result;running=false;
                if(!destroyed){stopForeground(true);if(pausedAt(this)>0)showResume(pausedAt(this));changed();stopSelf(lastStart);}
            });
        }
    }
    private static String message(Throwable e){
        String text=e.getMessage();
        if(text==null||text.trim().isEmpty())return e.getClass().getSimpleName();
        return text.length()>180?text.substring(0,180):text;
    }
    private static void applyFeedback(SelectiveEngine engine,Store store){
        Map<String,Integer> weights=store.weights();
        for(String key:new String[]{SelectiveEngine.FORGE,SelectiveEngine.POETRY,SelectiveEngine.DECISION,SelectiveEngine.CONNECTION}){
            int net=0;
            for(Map.Entry<String,Integer> vote:weights.entrySet())if(vote.getKey().contains(key))net+=vote.getValue();
            engine.setFeedback(key,net);
        }
        engine.setExamples(store.examples());
    }
    private static String route(AudioRecord recorder){
        if(Build.VERSION.SDK_INT>=24){
            AudioDeviceInfo device=recorder.getRoutedDevice();
            if(device!=null){
                switch(device.getType()){
                    case AudioDeviceInfo.TYPE_BUILTIN_MIC:return "phone microphone";
                    case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:return "Bluetooth microphone";
                    case AudioDeviceInfo.TYPE_USB_DEVICE:case AudioDeviceInfo.TYPE_USB_HEADSET:return "USB microphone";
                    case AudioDeviceInfo.TYPE_WIRED_HEADSET:return "headset microphone";
                    default:return "audio input "+device.getType();
                }
            }
        }return "input route unreported";
    }
    @Override public void onDestroy(){
        noticeHandler.removeCallbacks(refreshNotice);destroyed=true;stopping.set(true);LiveCapture.stop("Paused");RecallOverlay.afterService(this);
        AudioRecord rec=liveRecorder;
        if(rec!=null){try{rec.stop();}catch(IllegalStateException ignored){}}
        super.onDestroy();
    }
}
