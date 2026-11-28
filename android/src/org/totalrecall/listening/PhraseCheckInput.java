package org.totalrecall.listening;

import android.content.Context;
import android.media.*;
import android.os.Build;
import android.os.SystemClock;
import org.continuity.recall.ListenPolicy;
import java.util.Arrays;

/** One explicitly started trial, bounded to 12 seconds. All sample ownership ends in RAM. */
final class PhraseCheckInput {
    static final int RATE=16000,SECONDS=12;
    private static volatile PhraseCheckInput borrowed;
    private final short[] pcm=new short[RATE*SECONDS];
    private int count;
    private boolean ready,closed;
    private String problem="";

    static synchronized void borrow(PhraseCheckInput trial){
        if(borrowed!=null)borrowed.fail("Another phrase check opened.");
        borrowed=trial;
    }
    static synchronized void release(PhraseCheckInput trial){if(borrowed==trial)borrowed=null;}
    // A nonblocking read must reach the end of the old hardware queue before this trial starts.
    static void drained(){PhraseCheckInput t=borrowed;if(t!=null)t.ready();}
    static void frame(short[] frame,int n,boolean cue){
        PhraseCheckInput t=borrowed;
        if(t!=null){
            if(cue && t.isReady())t.fail("A spoken phone cue overlapped this take. Try again after the cue.");
            else if(!cue)t.add(frame,n);
        }
    }
    static void gap(){PhraseCheckInput t=borrowed;if(t!=null)t.fail("Live microphone input changed. Start a fresh take.");}
    synchronized void ready(){if(!closed && problem.isEmpty())ready=true;}
    synchronized boolean isReady(){return ready;}
    synchronized int size(){return count;}
    synchronized String problem(){return problem;}
    synchronized boolean open(){return !closed && problem.isEmpty() && count<pcm.length;}
    synchronized void fail(String reason){if(open()){problem=reason;Arrays.fill(pcm,(short)0);count=0;}}
    synchronized void add(short[] frame,int n){
        if(!ready || !open())return;
        int take=Math.min(n,pcm.length-count);System.arraycopy(frame,0,pcm,count,take);count+=take;
    }
    synchronized short[] finish(){
        short[] result=closed || !problem.isEmpty()?new short[0]:Arrays.copyOf(pcm,count);
        discard();return result;
    }
    synchronized void discard(){closed=true;Arrays.fill(pcm,(short)0);count=0;}

    void recordPrivate(Context app){
        AudioRecord rec=null;
        try{
            int min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0 || !open())throw new IllegalStateException();
            rec=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,RATE,AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,Math.max(8192,min*2));
            if(rec.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException();
            AudioManager audio=app.getSystemService(AudioManager.class);
            if(!open() || !ListenPolicy.allowed(app) || RecallService.isListening()){
                fail("Recording state changed. Start another take.");return;
            }
            rec.startRecording();
            if(rec.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException();
            short[] frame=new short[1600];long last=SystemClock.elapsedRealtime();
            while(open()){
                if(CuePlaybackGate.active()){fail("A spoken phone cue overlapped this take. Try again after the cue.");break;}
                AudioRecordingConfiguration config=Build.VERSION.SDK_INT>=29?rec.getActiveRecordingConfiguration():null;
                if(!ListenPolicy.allowed(app) || RecallService.isListening() || audio.isMicrophoneMute() ||
                        (config!=null && config.isClientSilenced())){
                    fail("Microphone access changed during this take. Try again.");break;
                }
                int n=rec.read(frame,0,frame.length,AudioRecord.READ_NON_BLOCKING);
                if(n>0){ready();add(frame,n);last=SystemClock.elapsedRealtime();}
                else if(n<0 || SystemClock.elapsedRealtime()-last>3500){
                    fail("No microphone input arrived. Check the active input and try again.");break;
                }else SystemClock.sleep(12);
            }
            Arrays.fill(frame,(short)0);
        }catch(RuntimeException e){fail("Could not open the microphone. Check microphone access and try again.");}
        finally{if(rec!=null){try{rec.stop();}catch(IllegalStateException ignored){}rec.release();}}
    }
}
