package org.totalrecall.listening;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Process-local live display. Sample PCM never enters this object. Captions expire even if input stalls. */
public final class LiveCapture {
    private LiveCapture(){}
    private static final long WINDOW=90000;
    private static final int LIMIT=8;
    private static final ArrayDeque<Line> recent=new ArrayDeque<>();
    private static String partial="",lastSave="",reason="",state="Paused";
    private static long partialAt=-WINDOW,lastLevel=-1000;
    private static float level;
    private static long finalSequence, sessionStart;
    private static int savedCount;
    private static boolean active,waiting;
    private static final class Line{
        final long at;final String words;
        Line(long time,String text){at=time;words=text;}
    }
    public static final class Snapshot{
        public final String partial,lastSave,reason,state;
        public final List<String> finals;
        public final float level;
        public final long finalSequence, sessionSeconds;
        public final int savedCount;
        public final boolean receiving;
        public final boolean active,waiting;
        Snapshot(String p,String save,String why,String status,List<String> lines,float meter,boolean on,boolean wait,long t){
            partial=p;lastSave=save;reason=why;state=status;finals=Collections.unmodifiableList(lines);level=meter;active=on;waiting=wait;
            finalSequence=LiveCapture.finalSequence; savedCount=LiveCapture.savedCount;
            sessionSeconds=on?Math.max(0,t-sessionStart)/1000:0;
            receiving=on&&!wait&&t-lastLevel<750;
        }
        /** Only show lines completed after the overlay became visible. */
        String overlayWords(long after){
            if(waiting || finalSequence<after)return "";
            int count=(int)Math.min(2,Math.min(finals.size(), finalSequence-after));
            List<String> lines=finals.subList(finals.size()-count,finals.size());
            String committed=String.join("\n",lines);
            if(partial.isEmpty())return committed;
            return committed.isEmpty()?partial:committed+"\n"+partial;
        }
    }
    private static long now(){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());}
    private static String bounded(String text,int max){return text==null?"":text.substring(0,Math.min(max,text.length())).trim();}
    private static void expire(long t){
        while(!recent.isEmpty()&&t-recent.peekFirst().at>=WINDOW)recent.removeFirst();
        if(t-partialAt>=WINDOW)partial="";
        if(t-lastLevel>=750)level=0;
    }
    public static synchronized Snapshot snapshot(){return at(now());}
    static synchronized Snapshot at(long t){
        expire(t);ArrayList<String> lines=new ArrayList<>(recent.size());
        for(Line line:recent)lines.add(line.words);
        return new Snapshot(partial,lastSave,reason,state,lines,level,active,waiting,t);
    }
    static synchronized void start(){clear();savedCount=0;sessionStart=now();active=true;waiting=false;state="Preparing microphone";}
    static synchronized void state(String label){state=bounded(label,200);}
    static synchronized void stop(String label){clear();active=false;waiting=false;state=bounded(label,200);}
    static synchronized void waitFor(String label){clear();waiting=true;state=bounded(label,200);}
    static synchronized void resume(String label){clear();waiting=false;state=bounded(label,200);}
    private static void clear(){recent.clear();partial="";lastSave="";reason="";level=0;lastLevel=now()-1000;partialAt=now()-WINDOW;}
    static synchronized void saved(String what,String why){if(active){savedCount++;lastSave=bounded(what,120);reason=bounded(why,250);}}
    static synchronized void words(String s,boolean complete){wordsAt(s,complete,now());}
    static synchronized void wordsAt(String text,boolean complete,long t){
        if(!active||waiting)return;
        expire(t);
        String value=bounded(text,3000);
        if(!complete){if(!value.equals(partial))partialAt=t;partial=value;return;}
        partial="";
        if(!value.isEmpty()){
            if(recent.size()==LIMIT)recent.removeFirst();
            finalSequence++;recent.addLast(new Line(t,value));
        }
    }
    static synchronized void samples(short[] pcm,int count){meterAt(pcm,count,now());}
    static synchronized void meterAt(short[] pcm,int count,long t){
        if(!active||waiting||count<=0||t-lastLevel<90)return;
        double energy=0;int samples=0;
        for(int i=0;i<count;i+=4){double x=pcm[i];energy+=x*x;samples++;}
        double rms=Math.sqrt(energy/samples)/32768.0;
        float relative=(float)Math.max(0,Math.min(1,(20*Math.log10(Math.max(0.0001,rms))+65)/60));
        level=relative>level?relative:0.65f*level+0.35f*relative;lastLevel=t;
    }
}
