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
    private static int savedCount, flags;
    private static long wordCount,eventAt;
    private static String event="";
    static synchronized void feedback(String message){event=bounded(message,160);eventAt=now();}
    static synchronized void flag(String category){flags++;feedback("Flagged · "+category);}
    private static long journalId=-1,voiceSession,aliasVersion;
    private static boolean groupsReady;
    private static final java.util.Map<Integer,String> names=new java.util.TreeMap<>();
    static synchronized void voiceSession(long journal,boolean available){voiceSession++;journalId=journal;groupsReady=available;names.clear();aliasVersion++;}
    static synchronized boolean rename(long generation,int id,String value){
        if(generation!=voiceSession || !active || !names.containsKey(id))return false;
        names.put(id,value);aliasVersion++;return true;
    }
    static String defaultVoice(int id){return id>0?"Voice "+id:id<0?"Uncertain voice":"";}
    private static boolean active,waiting;
    private static final class Line{
        final long at;final String words;final int voice;
        Line(long time,String text,int id){at=time;words=text;voice=id;}
    }
    public static final class Snapshot{
        public final String partial,lastSave,reason,state;
        public final List<String> finals;
        public final List<Long> recognizedAt;
        final List<Integer> voices;final java.util.Map<Integer,String> names;
        final long voiceSession,journalId,aliasVersion;
        final boolean groupsReady;
        String who(int i){int id=voices.get(i);return names.getOrDefault(id,defaultVoice(id));}
        String attributed(int i){String source=who(i);return (source.isEmpty()?"":source+" · ")+finals.get(i);}
        public final float level;
        public final long finalSequence, sessionSeconds;
        public final int savedCount,flags; public final long words; public final String event;
        public final boolean receiving;
        public final boolean active,waiting;
        Snapshot(String p,String save,String why,String status,List<String> lines,List<Long> times,List<Integer> ids,float meter,boolean on,boolean wait,long t){
            partial=p;lastSave=save;reason=why;state=status;finals=Collections.unmodifiableList(lines);recognizedAt=Collections.unmodifiableList(times);level=meter;active=on;waiting=wait;
            voices=Collections.unmodifiableList(ids);names=new java.util.TreeMap<>(LiveCapture.names);
            voiceSession=LiveCapture.voiceSession;journalId=LiveCapture.journalId;aliasVersion=LiveCapture.aliasVersion;groupsReady=LiveCapture.groupsReady;
            finalSequence=LiveCapture.finalSequence; savedCount=LiveCapture.savedCount;flags=LiveCapture.flags;words=wordCount;event=t-eventAt<=8000?LiveCapture.event:"";
            sessionSeconds=on?Math.max(0,t-sessionStart)/1000:0;
            receiving=on&&!wait&&t-lastLevel<750;
        }
        String scene(long after){
            int first=(int)Math.max(0,finals.size()-Math.max(0,finalSequence-after));
            int prior=0,switches=0;boolean me=false,media=false;
            for(int i=first;i<voices.size();i++){
                int v=voices.get(i);if(v<=0)continue;
                if(prior!=0 && prior!=v)switches++;prior=v;
                String label=who(i);me|=label.endsWith(" · Me");media|=label.endsWith(" · Media");
            }
            if(media && me)return "Your voice and a source you marked as media were both heard.";
            if(media)return "A voice you marked as media was heard.";
            if(switches>=2)return "Possible exchange · distinct voice groups took turns.";
            return "";
        }
        /** Only show lines completed after the overlay became visible. */
        String overlayWords(long after){
            if(waiting || finalSequence<after)return "";
            int count=(int)Math.min(2,Math.min(finals.size(), finalSequence-after));
            List<String> lines=new ArrayList<>();for(int i=finals.size()-count;i<finals.size();i++)lines.add(attributed(i));
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
        ArrayList<Long> times=new ArrayList<>(recent.size());
        ArrayList<Integer> ids=new ArrayList<>();for(Line line:recent){lines.add(line.words);times.add(line.at);ids.add(line.voice); }
        return new Snapshot(partial,lastSave,reason,state,lines,times,ids,level,active,waiting,t);
    }
    static synchronized void start(){clear();savedCount=0;flags=0;wordCount=0;sessionStart=now();active=true;waiting=false;state="Preparing microphone";}
    static synchronized void state(String label){state=bounded(label,200);}
    static synchronized void stop(String label){clear();active=false;waiting=false;state=bounded(label,200);}
    static synchronized void waitFor(String label){clear();waiting=true;state=bounded(label,200);}
    static synchronized void resume(String label){clear();waiting=false;state=bounded(label,200);}
    private static void clear(){voiceSession(-1,false);event="";recent.clear();partial="";lastSave="";reason="";level=0;lastLevel=now()-1000;partialAt=now()-WINDOW;}
    static synchronized void saved(String what,String why){if(active){savedCount++;feedback("Clip saved · "+what);lastSave=bounded(what,120);reason=bounded(why,250);}}
    static synchronized void words(String s,boolean complete){wordsAt(s,complete,now());}
    static synchronized void wordsAt(String text,boolean complete,long t){
        wordsAt(text,complete,t,0);
    }
    static synchronized void attributed(String words,int voice){wordsAt(words,true,now(),voice);}
    private static void wordsAt(String text,boolean complete,long t,int voice){
        if(!active||waiting)return;
        expire(t);
        String value=bounded(text,3000);
        if(!complete){if(!value.equals(partial))partialAt=t;partial=value;return;}
        partial="";
        if(!value.isEmpty()){
            if(recent.size()==LIMIT)recent.removeFirst();
            if(voice>0)names.putIfAbsent(voice,defaultVoice(voice));
            wordCount+=value.split("\\s+").length;finalSequence++;recent.addLast(new Line(t,value,voice));
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
