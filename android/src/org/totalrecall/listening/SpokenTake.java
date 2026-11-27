package org.totalrecall.listening;

import java.util.Arrays;

/** A deliberate microphone take, bounded to a minute. Only the recorder thread owns its audio. */
final class SpokenTake {
    static final int RATE=16000,LIMIT=60*RATE;
    static final class Status {
        final boolean recording;
        final int seconds;
        final long clip;
        final String message;
        Status(boolean recording,int seconds,long clip,String message){
            this.recording=recording;this.seconds=seconds;this.clip=clip;this.message=message;
        }
    }
    static volatile long firstLine;
    static volatile Status status=new Status(false,0,-1,"");
    private short[] pcm;
    private int count;
    private long start;
    private final StringBuilder heard=new StringBuilder();
    boolean active(){return pcm!=null;}
    void begin(){
        if(active())return;
        firstLine=LiveCapture.snapshot().finalSequence;pcm=new short[LIMIT];count=0;heard.setLength(0);start=System.currentTimeMillis();
        status=new Status(true,0,-1,"Recording your take");
    }
    boolean add(short[] frame,int n){
        if(!active())return false;
        int size=Math.min(pcm.length-count,n);System.arraycopy(frame,0,pcm,count,size);count+=size;
        if(count/RATE!=status.seconds)status=new Status(true,count/RATE,-1,"Recording your take");
        return count==LIMIT;
    }
    void words(String text){
        if(!active()||text==null||text.trim().isEmpty())return;
        if(heard.length()>0)heard.append(' ');heard.append(text.trim());
    }
    SelectiveEngine.Clip finish(){
        if(!active())return null;
        SelectiveEngine.Clip clip=count==0?null:new SelectiveEngine.Clip(Arrays.copyOf(pcm,count),heard.toString(),"Voice take","You chose Record my words.",start);
        pcm=null;heard.setLength(0);status=new Status(false,count/RATE,-1,"Saving your take…");return clip;
    }
    void release(){pcm=null;heard.setLength(0);status=new Status(false,0,-1,"Recording stopped");}
    static void saved(long id){status=new Status(false,status.seconds,id,"Audio saved on this phone");}
    static void failed(String message){status=new Status(false,0,-1,message);}
}
