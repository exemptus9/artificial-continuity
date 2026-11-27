package org.totalrecall.listening;

import android.media.AudioDeviceInfo;
import android.media.AudioRecord;
import java.util.Arrays;
import java.util.Locale;

/** Measures levels on the live capture path. Keeps only sums and peaks while its dialog is open. */
final class WearableMicCheck {
    static final int ROOM=0, A=1, B=2;
    static final String[] NAMES={"Quiet room","Wrist A","Wrist B"};
    private static volatile WearableMicCheck visible;
    private final Level[] readings=new Level[3];
    private Object recorder;
    private int routeId,rate,channels,stage=-1,samples,peak,settle;
    private String input="",message="Start listening to measure the active microphone.";
    private double squares;

    static WearableMicCheck open(){WearableMicCheck check=new WearableMicCheck();visible=check;return check;}
    void close(){if(visible==this)visible=null;}
    static void onFrame(AudioRecord recorder,AudioDeviceInfo device,String route,short[] pcm,int count){
        WearableMicCheck check=visible;
        if(check==null)return;
        int id=device==null?-1:device.getId();
        synchronized(check){
            if(recorder!=check.recorder||id!=check.routeId){
                try{
                    String name=device==null?"Android has not identified the input":route;
                    CharSequence product=device==null?null:device.getProductName();
                    if(product!=null&&!product.toString().isEmpty())name+=" · "+product;
                    check.route(recorder,id,name,recorder.getSampleRate(),recorder.getChannelCount());
                }catch(RuntimeException stale){check.pause();return;}
            }
            check.accept(pcm,count);
        }
    }
    static void unavailable(){WearableMicCheck check=visible;if(check!=null)check.pause();}
    synchronized void pause(){
        if(recorder==null)return;
        clear();recorder=null;input="";
        message="Audio stopped. Measure the quiet room again after listening resumes.";
    }
    synchronized void route(Object source,int id,String name,int sampleRate,int channelCount){
        boolean changed=recorder!=null&&(recorder!=source||routeId!=id);
        clear();recorder=source;routeId=id;rate=sampleRate;channels=channelCount;input=name;
        message=changed?"Input changed. Repeat the quiet-room reading on this route.":"Measure two seconds of room sound first.";
    }
    private void clear(){Arrays.fill(readings,null);stage=-1;samples=peak=settle=0;squares=0;}
    synchronized boolean available(int target){
        return target>=0&&target<NAMES.length&&stage<0&&recorder!=null&&rate>0&&channels>0&&(target==ROOM||readings[ROOM]!=null);
    }
    synchronized void start(int target){
        if(!available(target))return;
        if(target==ROOM)Arrays.fill(readings,null);else readings[target]=null;
        stage=target;samples=peak=0;settle=rate*channels/2;squares=0;message="";
    }
    synchronized void accept(short[] pcm,int count){
        if(stage<0||count<=0)return;
        int skip=Math.min(settle,count);
        settle-=skip;
        int target=2*rate*channels,part=Math.min(count-skip,target-samples);
        for(int i=skip;i<skip+part;i++){
            int n=pcm[i];squares+=(double)n*n;peak=Math.max(peak,Math.abs(n));
        }
        samples+=part;
        if(samples==target){
            readings[stage]=new Level(squares,samples,peak);
            message=stage==ROOM?"Unmute wrist A only. Speak your test line during its two-second reading.":
                stage==A?"For a second transmitter, mute A and repeat the same line into B.":"Both wrist readings are available.";
            stage=-1;
        }
    }
    String routeLabel(){return input.isEmpty()?"No live frames":input+"\n"+rate+" Hz · "+(channels==1?"mono":channels+" channels")+" captured by Recall";}
    synchronized String reading(){
        StringBuilder text=new StringBuilder(routeLabel()).append("\n\n");
        if(stage>=0&&settle>0)text.append(NAMES[stage]).append(" · getting ready…");
        else if(stage>=0)text.append(NAMES[stage]).append(" · measuring ").append(Math.min(99,100L*samples/(2L*rate*channels))).append("%");
        else text.append(message);
        for(int i=0;i<readings.length;i++)if(readings[i]!=null){
            Level value=readings[i];text.append("\n").append(NAMES[i]).append(" · ").append(value.describe());
            Level room=readings[ROOM];
            if(i!=ROOM&&value.peak>0&&room!=null&&room.peak>0)
                text.append(String.format(Locale.ROOT," · %+.0f dB vs room",value.db-room.db));
        }
        return text.toString();
    }
    private static final class Level{
        final double db;
        final int peak;
        Level(double squares,int samples,int peak){this.peak=peak;db=peak==0?0:10*Math.log10(squares/samples/(32768.0*32768.0));}
        String describe(){
            if(peak==0)return "digital silence";
            String s=String.format(Locale.ROOT,"%.0f dBFS average · %.0f dBFS peak",db,20*Math.log10(peak/32768.0));
            return peak>=32760?s+" · near full scale":s;
        }
    }
}
