package org.totalrecall.listening;

import java.util.Arrays;
import java.util.Locale;

/** Short speech-band level windows. Stores scalar levels only, never microphone samples. */
final class MicSignal {
    private static final int BLOCK=320, WINDOWS=600, MIN_DB=-90;
    private final int[] history=new int[WINDOWS],histogram=new int[1-MIN_DB];
    private int slot,windows,samples,learning,learned=-1,level=MIN_DB,floor=MIN_DB,consecutive,hold;
    private double previous,filtered,power;
    private int blockPeak;
    private boolean rejected,raisedFloor;

    void reset(){
        slot=windows=samples=learning=consecutive=hold=blockPeak=0;
        learned=-1;rejected=raisedFloor=false;level=floor=MIN_DB;previous=filtered=power=0;
        Arrays.fill(histogram,0);
    }
    void learn(){reset();learning=100;}
    private int percentile(int percent){
        int rank=Math.max(1,(windows*percent+99)/100);
        for(int i=0;i<histogram.length;i++){rank-=histogram[i];if(rank<=0)return i;}
        return 0;
    }
    void observe(short[] input,int n){
        blockPeak=0;
        for(int i=0;i<n;i++){
            double x=input[i];blockPeak=Math.max(blockPeak,Math.abs((int)x));
            // A single high-pass near 170 Hz keeps DC and low-frequency rumble out of the estimate.
            filtered=.935*(filtered+x-previous);previous=x;power+=filtered*filtered;
            if(++samples==BLOCK){
                double rms=Math.sqrt(power/BLOCK);
                level=Math.min(0,Math.max(MIN_DB,(int)Math.round(20*Math.log10(Math.max(1,rms)/32768))));
                int bin=level-MIN_DB;
                if(windows==WINDOWS)histogram[history[slot]]--;else windows++;
                history[slot]=bin;histogram[bin]++;slot=(slot+1)%WINDOWS;
                if(learning>0 && --learning==0){
                    rejected=percentile(90)-percentile(20)>10;
                    if(!rejected)learned=percentile(60);
                }
                int reference=learned>=0?learned:percentile(20);
                // A new steady floor can arrive when a fan starts between calibrations.
                if(windows>=35){
                    int low=90,high=0;
                    for(int j=1;j<=35;j++){
                        int recent=history[(slot+WINDOWS-j)%WINDOWS];low=Math.min(low,recent);high=Math.max(high,recent);
                    }
                    if(high-low<=3)reference=Math.max(reference,low);
                }
                raisedFloor=learned>=0 && reference>learned;
                floor=MIN_DB+reference;
                // Detect a rise over background without cutting off low-energy syllables between vowels.
                boolean raised=learning==0 && windows>=3 && level>=-84 && level-floor>=(floor<=-76?3:6);
                consecutive=raised?Math.min(2,consecutive+1):0;
                if(raised && (consecutive>=2 || level-floor>=10))hold=30;else if(hold>0)hold--;
                power=0;samples=0;
            }
        }
    }
    boolean audible(){return hold>0 && learning==0;}
    boolean rejectedRoom(){return rejected;}
    double gainLimit(boolean extra){
        if(!audible())return 1;
        // The extra headroom tapers away as the input peak approaches -45 dBFS.
        if(!extra)return 12;
        double faint=Math.max(0,Math.min(1,(185-blockPeak)/130.0));
        return 12+20*faint;
    }
    String reading(double gain,boolean boosted){
        if(windows==0)return "Measuring input…";
        String floorText=learning>0?"Sampling room · "+(100-learning)+"%":
            windows<12 && learned<0?"Estimating room":
            (learned>=0 && !raisedFloor?"Learned room ":"Estimated room ")+floor+" dBFS";
        return String.format(Locale.ROOT,"Input %d dBFS · %s · %s",level,floorText,
            boosted?String.format(Locale.ROOT,"Decoder %.1f×",gain):"Decoder boost off");
    }
}
