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
    enum Cue { WAKE, CLIP, TAKE }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final SoundPool pool;
    private final int wake,saved,clip,take;
    private final HashSet<Integer> loaded=new HashSet<>();
    private boolean closed;
    private long busyUntil;
    CaptureCues(Context c){
        prefs=c.getSharedPreferences("recall-speech",0);
        pool=new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((p,id,status)->{if(!closed && status==0)loaded.add(id);});
        wake=load(c,"wake");saved=load(c,"saved");clip=load(c,"clip");take=load(c,"take");
    }
    private int load(Context c,String name){
        int resource=c.getResources().getIdentifier("recall_"+name,"raw",c.getPackageName());
        return resource==0?0:pool.load(c,resource,1);
    }
    void play(Cue cue){
        long delay=Math.max(0,busyUntil-SystemClock.elapsedRealtime());
        // A wake and keep can arrive together. Keep the acknowledgement distinct from the save.
        boolean tone=prefs.getBoolean("chime",true);
        boolean voice=cue!=Cue.WAKE && prefs.getBoolean("spokenReceipts",false);
        if(!tone&&!voice)return;
        if(delay>1500)return;
        if(tone)enqueue(cue==Cue.WAKE?wake:saved,delay,false);
        if(voice)enqueue(cue==Cue.TAKE?take:clip,delay+(tone?375:0),true);
        busyUntil=SystemClock.elapsedRealtime()+delay+(tone?350:0)+(voice?950:0);
    }
    private void enqueue(int id,long delay,boolean voice){
        long expires=SystemClock.elapsedRealtime()+delay+600;
        main.postDelayed(new Runnable(){public void run(){
            if(closed || !prefs.getBoolean(voice?"spokenReceipts":"chime",!voice) || id==0)return;
            if(!loaded.contains(id)){if(SystemClock.elapsedRealtime()<expires)main.postDelayed(this,30);return;}
            int level=prefs.getInt("cueLevel",2);
            float gain=level==0?.30f:level==1?.62f:1f;
            pool.play(id,gain,gain,1,0,1);
        }},delay);
    }
    public void close(){
        closed=true;main.removeCallbacksAndMessages(null);pool.release();
    }
}
