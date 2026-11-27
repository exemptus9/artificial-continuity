package org.totalrecall.listening;

import java.util.Arrays;
import java.util.Random;

public final class MicSignalTest {
    private static final int N=1600;
    private static final class Fixture implements SelectiveEngine.Listener{
        final MicSignal signal=new MicSignal();
        final QuietSpeech boost=new QuietSpeech();
        final SelectiveEngine engine=new SelectiveEngine(this);
        final Random random=new Random(41);
        SelectiveEngine.Clip saved;
        long sample;
        double inEnergy,outEnergy;
        public void onClip(SelectiveEngine.Clip clip){if(saved!=null)throw new AssertionError("Extra clip");saved=clip;}
        public void onPauseRequested(){}
        void frame(boolean talk,boolean extended){
            short[] pcm=new short[N];
            for(int i=0;i<N;i++,sample++){
                double t=sample/16000.0;
                double room=12*Math.sin(2*Math.PI*60*t)+random.nextDouble()*5-2.5;
                double vowel=(.65+.3*Math.sin(2*Math.PI*4*t))*(85*Math.sin(2*Math.PI*420*t)+38*Math.sin(2*Math.PI*900*t));
                pcm[i]=(short)Math.round(room+(talk?vowel:0));
            }
            short[] original=pcm.clone();
            signal.observe(pcm,N);
            short[] decoded=boost.apply(pcm,N,signal.gainLimit(extended));
            engine.pcm(pcm,N,signal.audible());
            for(int i=0;i<N;i++){
                inEnergy+=(double)pcm[i]*pcm[i];outEnergy+=(double)decoded[i]*decoded[i];
            }
            if(!Arrays.equals(original,pcm))throw new AssertionError("Changed original PCM");
        }
        double ratio(){return Math.sqrt(outEnergy/inEnergy);}
        void clearMeasure(){inEnergy=outEnergy=0;}
    }
    public static void main(String[] args){
        Fixture f=new Fixture();f.signal.learn();
        for(int i=0;i<40;i++)f.frame(false,true);
        double noise=f.ratio();if(noise!=1 || f.signal.audible())throw new AssertionError("Stationary room gain "+noise);
        f.engine.setSensitivity(1);
        f.engine.text("i have a new word",true);f.clearMeasure();
        if(!f.engine.isSelecting())throw new AssertionError("Fixture never selected a clip");
        for(int i=0;i<80;i++)f.frame(true,true);
        if(f.saved!=null)throw new AssertionError("Cut off continuing faint speech");
        double speech=f.ratio();if(speech<5 || speech>32.01)throw new AssertionError("Quiet passage gain "+speech);
        for(int i=0;i<45;i++)f.frame(false,true);
        if(f.saved==null || f.saved.audio.length<12*16000)throw new AssertionError("Missing quiet tail");
        int peak=0;for(short v:f.saved.audio)peak=Math.max(peak,Math.abs((int)v));
        if(peak>160)throw new AssertionError("Boosted source audio saved");
        f.clearMeasure();for(int i=0;i<10;i++)f.frame(false,true);
        if(f.ratio()!=1)throw new AssertionError("Did not return to room gain");
        if(!f.signal.reading(1,true).contains("Learned room"))throw new AssertionError("Calibration missing");
        f.signal.reset();
        if(!f.signal.reading(1,true).contains("Measuring") || f.signal.gainLimit(true)!=1)throw new AssertionError("Stale source measurements");
        f.signal.learn();for(int i=0;i<10;i++)f.frame(false,true);for(int i=0;i<10;i++)f.frame(true,true);
        if(!f.signal.rejectedRoom())throw new AssertionError("Unsteady calibration accepted");
        System.out.printf(java.util.Locale.ROOT,"Room %.2f×; faint passage %.2f×; retained %.1f seconds of original PCM, peak %d. Route reset clears floor.%n",noise,speech,f.saved.audio.length/16000.0,peak);
    }
}
