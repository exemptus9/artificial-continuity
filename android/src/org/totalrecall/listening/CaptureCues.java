package org.totalrecall.listening;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.HashSet;

/** Short local feedback, mixed on the media channel. All player calls run on the main thread. */
final class CaptureCues implements AutoCloseable {
    enum Cue {
        WAKE("ready",548,"Ready"),
        CLIP("moment",913,"Moment saved"),
        TAKE("take",809,"Take stored"),
        CREATIVE("creative",1634,"Creative passage saved"),
        WORD("word",1544,"Word candidate saved"),
        DECISION("decision",1115,"Decision saved"),
        STARTED("started",1278,"Session started"),
        ENDED("ended",998,"Session saved"),
        MARKED("marked",1014,"Point marked"),
        UNCLEAR("unclear",2604,"Say the wake word, then help or save that."),
        MIC_WAIT("mic_wait",2565,"Microphone unavailable. Check the other app."),
        MIC_BACK("mic_back",1704,"Microphone back. Listening."),
        MIC_MUTED("mic_muted",2710,"Microphone muted. Check microphone access."),
        NO_AUDIO("no_audio",1810,"No audio yet. Wait for input."),
        SAVE_FAILED("save_failed",2459,"Save failed. Check storage and try again."),
        MARK_FAILED("mark_failed",2584,"Marker failed. The session is still recording."),
        NO_SESSION("no_session",1998,"No long session. Start one first."),
        SESSION_FAILED("session_failed",2723,"Session could not start. Open Long sessions."),
        STORAGE_LIMIT("storage_limit",2487,"Storage limit reached. Check Archive and sync."),
        STOPPED("stopped",2819,"Listening stopped. Open the recorder for details."),
        STATUS_LISTEN("status_listen",1822,"Microphone active. Listening."),
        STATUS_LONG("status_long",3119,"Long session recording. You can mark a point or finish."),
        STATUS_TAKE("status_take",2925,"Voice take recording. Finish and edit in the recorder."),
        STATUS_CLIP("status_clip",1462,"Capturing a passage."),
        HELP("help",3673,"After the wake word, ask for status, help, or save as."),
        CHOICE("choice",1779,"Word, line, or decision?"),
        CANCELED("canceled",1058,"Choice canceled."),
        EXPIRED("expired",1807,"Choice timed out. Ask again."),
        WAKE_EXPIRED("wake_expired",2461,"Wake window closed. Say the wake word again."),
        NO_EVENT("no_event",2019,"No recent result in this session."),
        PAUSED("paused",2548,"Listening paused. Use Resume to begin again."),
        SESSION_INTERRUPTED("session_interrupted",2754,"Long session saved. Microphone interrupted."),
        TAKE_STARTED("take_started",1248,"Voice take started."),
        MARKER_PART("marker_part",2231,"Clip saved. Its session marker failed."),
        SESSION_CHECK("session_check",2866,"Session save unconfirmed. Check Long sessions."),
        NO_PASSAGE("no_passage",2192,"No passage is open. Start one first.");
        final String asset,words;
        final int ms;
        Cue(String asset,int ms,String words){this.asset=asset;this.ms=ms;this.words=words;}
    }
    static Cue category(String kind){
        if(kind.contains(SelectiveEngine.FORGE))return Cue.WORD;
        if(kind.contains(SelectiveEngine.POETRY))return Cue.CREATIVE;
        if(kind.contains(SelectiveEngine.DECISION))return Cue.DECISION;
        return Cue.CLIP;
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final SoundPool pool;
    private final int wake,saved;
    private final java.util.EnumMap<Cue,Integer> voices=new java.util.EnumMap<>(Cue.class);
    private int lastStream;
    private long lastVoiceToken;
    private final HashSet<Integer> loaded=new HashSet<>();
    private final java.util.EnumMap<Cue,Long> recent=new java.util.EnumMap<>(Cue.class);
    private boolean closed;
    private long busyUntil,playingUntil;
    CaptureCues(Context c){
        prefs=c.getSharedPreferences("recall-speech",0);
        pool=new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((p,id,status)->{if(!closed && status==0)loaded.add(id);});
        wake=load(c,"wake");saved=load(c,"saved");
        for(Cue cue:Cue.values())voices.put(cue,load(c,cue.asset));
    }
    private int load(Context c,String name){
        int resource=c.getResources().getIdentifier("recall_"+name,"raw",c.getPackageName());
        return resource==0?0:pool.load(c,resource,1);
    }
    static boolean recovery(Cue cue){
        switch(cue){
            case MIC_WAIT:case MIC_MUTED:case MIC_BACK:case NO_AUDIO:case SAVE_FAILED:case MARK_FAILED:case NO_SESSION:
            case SESSION_FAILED:case SESSION_CHECK:case STORAGE_LIMIT:case STOPPED:case WAKE_EXPIRED:case NO_PASSAGE:case MARKER_PART:
            case SESSION_INTERRUPTED:return true;
            default:return false;
        }
    }
    static String voiceSetting(Cue cue,boolean automatic){
        if(automatic)return "spokenAuto";
        if(cue==Cue.WAKE)return "spokenReady";
        if(cue==Cue.UNCLEAR)return "spokenRetry";
        if(recovery(cue))return "spokenRecovery";
        switch(cue){
            case STATUS_LISTEN:case STATUS_LONG:case STATUS_TAKE:case STATUS_CLIP:case CHOICE:
            case HELP:case NO_EVENT:case CANCELED:case EXPIRED:return "spokenAnswers";
            default:return "spokenReceipts";
        }
    }
    static boolean enabled(SharedPreferences prefs,String key){
        // An explicit spoken question can receive an answer on first use. A previously silenced app stays quiet.
        boolean fallback=key.equals("spokenAnswers")?prefs.getBoolean("chime",true) || prefs.getBoolean("spokenReceipts",false):
            key.equals("spokenRecovery") && prefs.getBoolean("spokenReceipts",false);
        return prefs.getBoolean(key,fallback);
    }
    static boolean confirmationTone(Cue cue){
        switch(cue){
            case CLIP:case TAKE:case CREATIVE:case WORD:case DECISION:case ENDED:case SESSION_INTERRUPTED:case MARKER_PART:return true;
            default:return false;
        }
    }
    static long minimumInterval(Cue cue,boolean automatic){
        if(automatic)return 8000;
        if(cue==Cue.MIC_WAIT || cue==Cue.MIC_BACK || cue==Cue.MIC_MUTED || cue==Cue.STORAGE_LIMIT)return 20_000;
        if(cue==Cue.WAKE_EXPIRED || cue==Cue.UNCLEAR || cue==Cue.NO_AUDIO)return 3000;
        return 0;
    }
    void play(Cue cue){play(cue,false);}
    void play(Cue cue,boolean automatic){
        boolean tone=!automatic && (cue==Cue.WAKE || confirmationTone(cue)) && prefs.getBoolean("chime",true);
        String key=voiceSetting(cue,automatic);
        queue(cue,tone,enabled(prefs,key)?key:"",automatic,false);
    }
    // The light chime can respond to a partial wake. Speech waits for a standalone final wake.
    void wakeChime(){queue(Cue.WAKE,prefs.getBoolean("chime",true),"",false,false);}
    void spokenReady(){if(enabled(prefs,"spokenReady"))queue(Cue.WAKE,false,"spokenReady",false,false);}
    void answer(Cue cue,boolean explicitTap){
        if(explicitTap || enabled(prefs,"spokenAnswers"))queue(cue,false,explicitTap?null:"spokenAnswers",false,true);
    }
    private void queue(Cue cue,boolean tone,String voiceKey,boolean automatic,boolean immediate){
        boolean speak=voiceKey==null || !voiceKey.isEmpty();
        if(closed || (!tone && !speak))return;
        long now=SystemClock.elapsedRealtime();
        Long last=recent.get(cue);
        if(!immediate && last!=null && now-last<minimumInterval(cue,automatic))return;
        if(cue==Cue.CHOICE || immediate)interrupt();
        long delay=Math.max(0,busyUntil-now);
        if(delay>2200){
            if(automatic || cue==Cue.WAKE || cue==Cue.WAKE_EXPIRED)return;
            interrupt();delay=0;
        }
        recent.put(cue,now);
        if(tone)enqueue(cue==Cue.WAKE?wake:saved,delay,"chime",0);
        int toneTime=tone?380:0;
        if(speak)enqueue(voices.get(cue),delay+toneTime,voiceKey,cue.ms);
        busyUntil=now+delay+toneTime+(speak?cue.ms+140:0);
    }
    private void enqueue(int id,long delay,String pref,int voiceMs){
        long expires=SystemClock.elapsedRealtime()+delay+900;
        main.postDelayed(new Runnable(){public void run(){
            if(closed || id==0 || (pref!=null && !(pref.equals("chime")?prefs.getBoolean("chime",true):enabled(prefs,pref))))return;
            if(!loaded.contains(id)){if(SystemClock.elapsedRealtime()<expires)main.postDelayed(this,25);return;}
            long now=SystemClock.elapsedRealtime();
            if(now<playingUntil){main.postDelayed(this,playingUntil-now);return;}
            int level=prefs.getInt("cueLevel",2);float gain=level==0?.30f:level==1?.62f:1f;
            long token=voiceMs==0?0:CuePlaybackGate.begin(voiceMs);
            int stream=0;
            try{stream=pool.play(id,gain,gain,1,0,1);}catch(RuntimeException unavailable){}
            if(stream==0){if(token!=0)CuePlaybackGate.cancel(token);return;}
            playingUntil=now+(voiceMs==0?370:voiceMs+100);
            busyUntil=Math.max(busyUntil,playingUntil);
            lastStream=stream;if(token!=0)lastVoiceToken=token;
        }},delay);
    }
    private void interrupt(){
        main.removeCallbacksAndMessages(null);
        if(lastStream!=0){pool.stop(lastStream);lastStream=0;}
        if(lastVoiceToken!=0){CuePlaybackGate.end(lastVoiceToken);lastVoiceToken=0;}
        busyUntil=0;playingUntil=0;
    }
    void previewVoice(Cue cue){queue(cue,false,null,false,true);}
    void finishPending(){main.postDelayed(this::close,Math.max(0,busyUntil-SystemClock.elapsedRealtime())+900);}
    public void close(){
        if(closed)return;
        closed=true;interrupt();pool.release();
    }
}
