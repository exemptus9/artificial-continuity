package org.totalrecall.listening;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Test adapter for raw 16 kHz mono, signed 16-bit little-endian PCM. */
public final class MicTransform {
    public static void main(String[] args) throws Exception {
        ByteBuffer input=ByteBuffer.wrap(System.in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer output=ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        MicSignal signal=new MicSignal();
        QuietSpeech boost=new QuietSpeech();
        short[] frame=new short[1600];
        boolean legacy="legacy".equals(args[0]);
        boolean extended="extended".equals(args[0]);
        while(input.hasRemaining()){
            int count=Math.min(input.remaining()/2,frame.length);
            for(int i=0;i<count;i++)frame[i]=input.getShort();
            signal.observe(frame,count);
            short[] samples=boost.apply(frame,count,legacy?12:signal.gainLimit(extended));
            for(int i=0;i<count;i++)output.putShort(samples[i]);
        }
        System.out.write(output.array());
    }
}
