package org.totalrecall.listening;

import java.util.Arrays;

public final class RoomNoiseTest {
    public static void main(String[] args){
        RoomNoise room=new RoomNoise();short[] hum=new short[1600];
        for(int i=0;i<hum.length;i++)hum[i]=(short)(900*Math.sin(2*Math.PI*60*i/16000));
        short[] original=hum.clone();
        if(room.apply(hum,800)!=hum)throw new AssertionError("Inactive filter changed signal");
        room.learn();
        for(int i=0;i<20;i++){
            if(room.ready())throw new AssertionError("Calibration ended early");
            room.apply(hum,hum.length);
        }
        if(!room.ready())throw new AssertionError("Calibration failed");
        for(int i=0;i<5;i++)room.apply(hum,hum.length);
        short[] reduced=room.apply(hum,hum.length);
        long raw=0,clean=0;
        for(int i=0;i<hum.length;i++){raw+=(long)hum[i]*hum[i];clean+=(long)reduced[i]*reduced[i];}
        if(clean*4>raw || !Arrays.equals(hum,original))throw new AssertionError("Hum attenuation or original failed");
        room.reset();if(room.running() || room.apply(hum,hum.length)!=hum)throw new AssertionError("Route reset failed");
        System.out.println("PASS room calibration, 60 Hz attenuation, untouched archive input and route reset.");
    }
}
