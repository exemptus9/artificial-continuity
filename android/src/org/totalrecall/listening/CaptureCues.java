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
    enum Cue { WAKE, CLIP, TAKE, CREATIVE, WORD, DECISION, STARTED, ENDED, MARKED, UNCLEAR }
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
    private final HashSet<Integer> loaded=new HashSet<>();
    private boolean closed;
    private long busyUntil;
    CaptureCues(Context c){
        prefs=c.getSharedPreferences("recall-speech",0);
        pool=new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((p,id,status)->{if(!closed && status==0)loaded.add(id);});
        wake=load(c,"wake");saved=load(c,"saved");String[] names={"ready","moment","take","creative","word","decision","started","ended","marked","unclear"};
        for(Cue cue:Cue.values())voices.put(cue,load(c,names[cue.ordinal()]));
    }
    private int load(Context c,String name){
        int resource=c.getResources().getIdentifier("recall_"+name,"raw",c.getPackageName());
        return resource==0?0:pool.load(c,resource,1);
    }
    void play(Cue cue){play(cue,false);}
    void play(Cue cue,boolean automatic){
        long delay=Math.max(0,busyUntil-SystemClock.elapsedRealtime());
        // A wake and keep can arrive together. Keep the acknowledgement distinct from the save.
        boolean tone=prefs.getBoolean("chime",true);
        boolean voice=cue!=Cue.WAKE && prefs.getBoolean("spokenReceipts",false);
        if(automatic){tone=false;voice=prefs.getBoolean("spokenAuto",false);}
        if(cue==Cue.WAKE){
            voice=prefs.getBoolean("spokenReady",false);
            if(!tone && !voice)return;
            main.removeCallbacksAndMessages(null);if(lastStream!=0)pool.stop(lastStream);
            delay=0;
        }else if(cue==Cue.UNCLEAR){
            tone=false;voice=prefs.getBoolean("spokenRetry",false);
        }else if(cue==Cue.STARTED || cue==Cue.MARKED)tone=false;
        if(!tone&&!voice)return;
        if(delay>1700 && cue!=Cue.ENDED)return;
        if(tone)enqueue(cue==Cue.WAKE?wake:saved,delay,"chime",true);
        if(voice){
            String pref=automatic?"spokenAuto":cue==Cue.WAKE?"spokenReady":cue==Cue.UNCLEAR?"spokenRetry":"spokenReceipts";
            enqueue(voices.get(cue),delay+(tone?375:0),pref,false);
        }
        busyUntil=SystemClock.elapsedRealtime()+delay+(tone?375:0)+(voice?1200:0);
    }
    private void enqueue(int id,long delay,String pref,boolean defaultOn){
        long expires=SystemClock.elapsedRealtime()+delay+650;
        main.postDelayed(new Runnable(){public void run(){
            if(closed || (pref!=null && !prefs.getBoolean(pref,defaultOn)) || id==0)return;
            if(!loaded.contains(id)){if(SystemClock.elapsedRealtime()<expires)main.postDelayed(this,25);return;}
            int level=prefs.getInt("cueLevel",2);float gain=level==0?.30f:level==1?.62f:1f;
            lastStream=pool.play(id,gain,gain,1,0,1);
        }},delay);
    }
    void previewVoice(Cue cue){
        main.removeCallbacksAndMessages(null);if(lastStream!=0)pool.stop(lastStream);
        enqueue(voices.get(cue),0,null,false);
    }
    void finishPending(){
        main.postDelayed(this::close,Math.max(0,busyUntil-SystemClock.elapsedRealtime())+300);
    }
    public void close(){
        closed=true;main.removeCallbacksAndMessages(null);pool.release();
    }
}
