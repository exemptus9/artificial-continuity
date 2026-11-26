package org.totalrecall.listening;

/** Bounded gain for quiet speech. Only the dictation decoder receives the adjusted samples. */
final class QuietSpeech {
    private short[] output=new short[1600];
    private double envelope,gain=1;
    void reset(){envelope=0;gain=1;}
    short[] apply(short[] original,int count){
        if(count<=0)return original;
        int peak=0;
        for(int i=0;i<count;i++)peak=Math.max(peak,Math.abs((int)original[i]));
        envelope=Math.max(peak,envelope*Math.exp(-count/16000.0/.65));
        double target=envelope<16?1:Math.min(12,Math.max(1,3500/envelope));
        double next=target<gain?target:gain+(target-gain)*(1-Math.exp(-count/16000.0/.08));
        if(next==1 && gain==1)return original;
        if(output.length<count)output=new short[count];
        double start=Math.min(gain,peak>0?32767.0/peak:gain);
        for(int i=0;i<count;i++){
            double amp=start+(next-start)*(i+1)/count;
            output[i]=(short)Math.max(-32768,Math.min(32767,Math.round(original[i]*amp)));
        }
        gain=next;return output;
    }
}
