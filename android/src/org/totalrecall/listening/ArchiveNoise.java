package org.totalrecall.listening;

import java.util.Arrays;

/** Offline-only. The quietest full windows of a completed passage provide its own room sample. */
final class ArchiveNoise {
    private static final int SIZE=512,HOP=128,BINS=SIZE/2+1;
    private final CommandNoise.Spectrum spectrum=new CommandNoise.Spectrum(SIZE);
    private final double[] window=new double[SIZE],real=new double[SIZE],imag=new double[SIZE];
    private final double[] background=new double[BINS],smooth=new double[BINS];
    private double ceiling;
    private boolean room;
    ArchiveNoise(){
        for(int i=0;i<SIZE;i++)window[i]=Math.sqrt(.5-.5*Math.cos(2*Math.PI*i/SIZE));
    }
    boolean hasRoom(){return room;}
    boolean prepare(short[] source,int count){
        room=false;ceiling=1;
        if(count<SIZE*2)return false;
        int windows=(count-SIZE)/HOP+1;
        double[] levels=new double[windows];
        for(int k=0;k<windows;k++){
            double energy=0;
            for(int j=0;j<SIZE;j++){
                double x=source[k*HOP+j]*window[j];energy+=x*x;
            }
            levels[k]=Math.sqrt(energy/SIZE);
        }
        double[] sorted=levels.clone();Arrays.sort(sorted);
        int fifth=Math.max(1,windows/5);
        double low=0;
        for(int k=0;k<fifth;k++)low+=sorted[k]/fifth;
        double high=sorted[(int)((windows-1)*.90)];
        ceiling=Math.min(32,Math.max(1,3000/Math.max(1,high)));
        // A relatively steady floor and measurable speech above it are both necessary.
        room=low>10 && high>low*1.45 && high<low*20;
        if(room){
            Arrays.fill(background,0);int selected=0;
            for(int k=0;k<windows;k++)if(levels[k]<=sorted[fifth-1]){
                transform(source,count,k*HOP);
                for(int j=0;j<BINS;j++)background[j]+=real[j]*real[j]+imag[j]*imag[j];
                selected++;
            }
            for(int j=0;j<BINS;j++)background[j]/=Math.max(1,selected);
        }
        return room || (high>=8 && ceiling>2);
    }
    private void transform(short[] source,int count,int start){
        for(int j=0;j<SIZE;j++){
            int i=start+j;
            real[j]=(i>=0 && i<count)?source[i]*window[j]:0;imag[j]=0;
        }
        spectrum.transform(real,imag,false);
    }
    short[] render(short[] source,int count,boolean decisionDirected){
        short[] result=new short[count];
        if(!room){
            for(int i=0;i<count;i++)result[i]=sample(source[i]*ceiling);
            return result;
        }
        double[] sum=new double[count];Arrays.fill(smooth,decisionDirected?1:0);
        for(int start=HOP-SIZE;start<count;start+=HOP){
            transform(source,count,start);
            for(int j=0;j<BINS;j++){
                double p=real[j]*real[j]+imag[j]*imag[j],mask;
                if(decisionDirected){
                    double posterior=p/Math.max(1,background[j]);
                    double prior=.96*smooth[j]+.04*Math.max(0,posterior-1);
                    mask=Math.max(.10,prior/(1+prior));smooth[j]=mask*mask*posterior;
                }else{
                    smooth[j]=.6*smooth[j]+.4*p;
                    mask=Math.max(.08,1-1.35*background[j]/Math.max(smooth[j],1));
                }
                real[j]*=mask;imag[j]*=mask;
                if(j>0 && j<SIZE/2){real[SIZE-j]*=mask;imag[SIZE-j]*=mask;}
            }
            spectrum.transform(real,imag,true);
            for(int j=Math.max(0,-start);j<Math.min(SIZE,count-start);j++)sum[start+j]+=real[j]*window[j]/2;
        }
        for(int i=0;i<count;i++)result[i]=sample(sum[i]*ceiling);
        return result;
    }
    private static short sample(double x){return (short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,Math.round(x)));}
}
