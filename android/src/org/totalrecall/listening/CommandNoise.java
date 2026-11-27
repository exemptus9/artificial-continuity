package org.totalrecall.listening;

import java.util.Arrays;

/** Streaming spectral subtraction for the restricted recognizer, with 16 ms latency.
 * Only engages in steady room noise close to the speech level. Original mic audio and
 * general dictation follow their existing independent paths. 256 samples at 16 kHz. */
final class CommandNoise {
    private static final int SIZE=256,HOP=SIZE/2,BINS=HOP+1;
    private final double[] window=new double[SIZE],cos=new double[HOP],sin=new double[HOP];
    private final int[] reversed=new int[SIZE];
    private final double[] raw=new double[SIZE],speech=new double[SIZE],re=new double[SIZE],im=new double[SIZE];
    private final double[] power=new double[BINS],smooth=new double[BINS],minimum=new double[BINS],mask=new double[BINS];
    private final double[][] floors=new double[8][BINS];
    private final double[] overlap=new double[HOP],levels=new double[175],shortLevels=new double[20],percentiles=new double[175];
    private final short[] ready=new short[HOP];
    private short[] output=new short[1600];
    private int fill,floorSlot,floorCount,levelSlot,levelCount;
    private long frames;
    private boolean active;
    private double mix;

    CommandNoise(){
        for(int i=0;i<SIZE;i++){
            window[i]=Math.sqrt(.5-.5*Math.cos(2*Math.PI*i/SIZE));
            reversed[i]=Integer.reverse(i)>>>24;
            if(i<HOP){cos[i]=Math.cos(2*Math.PI*i/SIZE);sin[i]=Math.sin(2*Math.PI*i/SIZE);}
        }
    }
    void reset(){
        fill=floorSlot=floorCount=levelSlot=levelCount=0;frames=0;active=false;mix=0;
        Arrays.fill(raw,0);Arrays.fill(speech,0);Arrays.fill(overlap,0);Arrays.fill(ready,(short)0);
    }
    short[] apply(short[] original,short[] recognized,int count){
        if(output.length<count)output=new short[count];
        for(int i=0;i<count;i++){
            output[i]=ready[fill];
            raw[HOP+fill]=original[i];speech[HOP+fill]=recognized[i];
            if(++fill==HOP){frame();fill=0;}
        }
        return output;
    }

    private void assessRoom(double rms){
        shortLevels[(int)(frames%shortLevels.length)]=rms;
        levels[levelSlot]=rms;levelSlot=(levelSlot+1)%levels.length;
        levelCount=Math.min(levels.length,levelCount+1);
        if(frames>=shortLevels.length-1){
            double low=Double.MAX_VALUE,high=0;
            for(double value:shortLevels){low=Math.min(low,value);high=Math.max(high,value);}
            if(low>32 && high<2*low)active=true;
            else if(high<8)active=false;
        }
        if(frames%10!=0 || levelCount<22)return;
        System.arraycopy(levels,0,percentiles,0,levelCount);
        Arrays.sort(percentiles,0,levelCount);
        double low=percentile(.20),high=percentile(.85);
        double range=20*Math.log10(Math.max(high,1)/Math.max(low,1));
        // Strong speech already separates from the room. Keep the original decoder input.
        mix=Math.max(0,Math.min(1,(12-range)/4));
    }
    private double percentile(double fraction){
        double position=fraction*(levelCount-1);int index=(int)position;
        return percentiles[index]+(position-index)*(percentiles[Math.min(index+1,levelCount-1)]-percentiles[index]);
    }
    private void frame(){
        double energy=0;
        for(int i=0;i<SIZE;i++){energy+=raw[i]*raw[i];re[i]=raw[i]*window[i];im[i]=0;}
        assessRoom(Math.sqrt(energy/SIZE));
        fft(false);
        if(frames>0 && frames%20==0){
            System.arraycopy(minimum,0,floors[floorSlot],0,BINS);
            floorSlot=(floorSlot+1)%floors.length;floorCount=Math.min(floorCount+1,floors.length);
        }
        for(int i=0;i<BINS;i++){
            power[i]=re[i]*re[i]+im[i]*im[i];
            smooth[i]=frames==0?power[i]:.8*smooth[i]+.2*power[i];
            minimum[i]=frames%20==0?smooth[i]:Math.min(minimum[i],smooth[i]);
            if(active && mix>0){
                double floor=floorCount==0?minimum[i]:floors[0][i];
                for(int j=1;j<floorCount;j++)floor=Math.min(floor,floors[j][i]);
                mask[i]=1-mix*(1-Math.max(.08,1-1.5*floor/Math.max(power[i],1)));
            }
        }
        if(active && mix>0){
            for(int i=0;i<SIZE;i++){re[i]=speech[i]*window[i];im[i]=0;}
            fft(false);
            for(int i=0;i<SIZE;i++){double gain=mask[Math.min(i,SIZE-i)];re[i]*=gain;im[i]*=gain;}
            fft(true);
        }else{
            for(int i=0;i<SIZE;i++)re[i]=speech[i]*window[i];
        }
        for(int i=0;i<HOP;i++){
            ready[i]=(short)Math.max(-32768,Math.min(32767,Math.round(overlap[i]+re[i]*window[i])));
            overlap[i]=re[i+HOP]*window[i+HOP];
        }
        System.arraycopy(raw,HOP,raw,0,HOP);System.arraycopy(speech,HOP,speech,0,HOP);frames++;
    }
    private void fft(boolean inverse){
        for(int i=0;i<SIZE;i++){
            int j=reversed[i];if(j<=i)continue;
            double x=re[i];re[i]=re[j];re[j]=x;x=im[i];im[i]=im[j];im[j]=x;
        }
        for(int width=2;width<=SIZE;width*=2){
            int half=width/2,stride=SIZE/width;
            for(int i=0;i<SIZE;i+=width){
                for(int j=0;j<half;j++){
                    int left=i+j,right=left+half,k=j*stride;
                    double c=cos[k],s=inverse?sin[k]:-sin[k];
                    double x=c*re[right]-s*im[right],y=s*re[right]+c*im[right];
                    re[right]=re[left]-x;im[right]=im[left]-y;re[left]+=x;im[left]+=y;
                }
            }
        }
        if(inverse)for(int i=0;i<SIZE;i++){re[i]/=SIZE;im[i]/=SIZE;}
    }
}
