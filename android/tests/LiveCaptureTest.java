package org.totalrecall.listening;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class LiveCaptureTest{
    private static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        LiveCapture.start();long t=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        LiveCapture.wordsAt("unchanged partial",false,t);LiveCapture.wordsAt("first line",true,t);
        check(LiveCapture.at(t).partial.isEmpty()&&LiveCapture.at(t).finals.size()==1);
        short[] data=new short[1600];Arrays.fill(data,(short)10000);
        LiveCapture.meterAt(data,1600,t);check(LiveCapture.at(t).level>0.5);
        check(LiveCapture.at(t+800).level==0);
        for(int i=0;i<15;i++)LiveCapture.wordsAt("line "+i,true,t+i);
        check(LiveCapture.at(t+100).finals.size()==8);check(LiveCapture.at(t+90050).finals.isEmpty());
        LiveCapture.wordsAt("new words",false,t+90051);LiveCapture.saved("Poem line","near rhyme");
        check(!LiveCapture.at(t+90051).reason.isEmpty());
        LiveCapture.waitFor("Waiting for microphone · competing app");
        check(LiveCapture.snapshot().waiting && LiveCapture.snapshot().finals.isEmpty() && LiveCapture.snapshot().partial.isEmpty());
        LiveCapture.words("during gap",true);check(LiveCapture.snapshot().finals.isEmpty());
        LiveCapture.resume("Listening · phone microphone");LiveCapture.words("after",true);
        check(LiveCapture.snapshot().finals.size()==1&&!LiveCapture.snapshot().waiting);
        LiveCapture.stop("Paused");check(!LiveCapture.snapshot().active && LiveCapture.snapshot().finals.isEmpty());
        MicrophoneContinuity gaps=new MicrophoneContinuity();check(gaps.lose(100));check(!gaps.lose(300));
        check(gaps.resume(600)==500);check(!gaps.waiting());check(gaps.resume(800)==-1);
        check(gaps.lose(1000));check(gaps.stop(1080)==80);check(gaps.resume(1300)==-1);check(!gaps.lose(2000));
        System.out.println("PASS live final/partial, expiry, meter, pause, gap isolation, resume, paused-during-gap.");
    }
}
