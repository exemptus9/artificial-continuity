package org.totalrecall.listening;

import java.util.Arrays;

/** Two seconds of room tone at 16 kHz. Four complementary bands preserve the input at unity gain. */
final class RoomNoise {
    private final double[] smooth=new double[3], bands=new double[4], power=new double[4],
        noise=new double[4], gain={1,1,1,1}, target={1,1,1,1};
    private final double[][] blocks=new double[4][20];
    private static final double[] ALPHA={.046,.21,.65};
    private final Tone[] tones={new Tone(50),new Tone(60),new Tone(100),new Tone(120)};
    private double totalPower;
    /** A narrow notch is enabled only if the calibrated room contains a strong continuous mains tone. */
    private static final class Tone {
        final double coefficient,cos,sin;
        double re,im,c=1,s,x1,x2,y1,y2;boolean active;
        Tone(double hz){
            double angle=2*Math.PI*hz/16000;cos=Math.cos(angle);sin=Math.sin(angle);coefficient=2*cos;
        }
        void reset(){active=false;c=1;s=re=im=x1=x2=y1=y2=0;}
        void measure(double x){
            re+=x*c;im+=x*s;
            double next=c*cos-s*sin;s=s*cos+c*sin;c=next;
        }
        void finish(double power){active=power>0 && 2*(re*re+im*im)>.14*32000*power;}
        double filter(double x){
            if(!active)return x;
            double r=.9988,y=r*(x-coefficient*x1+x2)+r*coefficient*y1-r*r*y2;
            x2=x1;x1=x;y2=y1;y1=y;return y;
        }
    }
    boolean running(){return ready||measured>=0;}
    private int measured=-1,phase;
    private boolean ready;
    private short[] output=new short[0];
    void reset(){
        measured=-1;ready=false;phase=0;totalPower=0;for(Tone t:tones)t.reset();
        Arrays.fill(smooth,0);Arrays.fill(power,0);Arrays.fill(gain,1);Arrays.fill(target,1);
    }
    void learn(){
        reset();for(double[] row:blocks)Arrays.fill(row,0);
        measured=0;
    }
    boolean ready(){return ready;}
    short[] apply(short[] in,int n){
        if(measured<0 && !ready)return in;
        if(output.length<n)output=new short[n];
        for(int i=0;i<n;i++){
            double x=in[i],prior=0,sum=0;
            if(measured>=0){totalPower+=x*x;for(Tone t:tones)t.measure(x);}
            else for(Tone t:tones)x=t.filter(x);
            for(int b=0;b<3;b++){
                smooth[b]+=ALPHA[b]*(x-smooth[b]);
                bands[b]=smooth[b]-prior;prior=smooth[b];
            }
            bands[3]=x-prior;
            for(int b=0;b<4;b++){
                double square=bands[b]*bands[b];
                power[b]+=(square>power[b]?.015:.0015)*(square-power[b]);
                if(measured>=0)blocks[b][measured/1600]+=square/1600;
                else if(phase==0){
                    // Keep broadband attenuation mild to preserve unfamiliar consonants.
                    target[b]=Math.sqrt(Math.max(.74,1-.6*noise[b]/Math.max(1,power[b])));
                }
                gain[b]+=(target[b]>gain[b]?.025:.004)*(target[b]-gain[b]);
                sum+=gain[b]*bands[b];
            }
            output[i]=(short)Math.max(-32768,Math.min(32767,Math.round(sum)));
            phase=(phase+1)&31;
            if(measured>=0 && ++measured==32000){
                for(int b=0;b<4;b++){Arrays.sort(blocks[b]);noise[b]=blocks[b][8];}
                for(Tone t:tones)t.finish(totalPower);
                measured=-1;ready=true;
            }
        }
        return output;
    }
}
