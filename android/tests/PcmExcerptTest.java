package org.totalrecall.listening;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

/** Independent canonical WAV fixture; compare actual samples, not just duration. */
public final class PcmExcerptTest {
    private static void check(boolean condition){if(!condition)throw new AssertionError();}
    private interface Operation {void run()throws Exception;}
    private static void rejects(Operation f)throws Exception{try{f.run();}catch(IOException expected){return;}throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("pcm-cut-test");
        File source=dir.resolve("source.wav").toFile(),cut=dir.resolve("cut.wav").toFile(),nested=dir.resolve("nested.wav").toFile();
        try{
            int count=24001;
            ByteBuffer wav=ByteBuffer.allocate(44+count*2).order(ByteOrder.LITTLE_ENDIAN);
            wav.putInt(0x46464952).putInt(36+count*2).putInt(0x45564157).putInt(0x20746d66).putInt(16);
            wav.putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16);
            wav.putInt(0x61746164).putInt(count*2);
            for(int i=0;i<count;i++)wav.putShort((short)(i*359));
            byte[] original=wav.array();Files.write(source.toPath(),original);
            check(PcmExcerpt.count(source)==count);
            int from=137,to=21004;
            String claimed=PcmExcerpt.trim(source,cut,from,to);
            byte[] result=Files.readAllBytes(cut.toPath());
            check(PcmExcerpt.count(cut)==to-from);
            check(Arrays.equals(Arrays.copyOfRange(result,44,result.length),Arrays.copyOfRange(original,44+2*from,44+2*to)));
            check(Arrays.equals(Files.readAllBytes(source.toPath()),original));
            StringBuilder expected=new StringBuilder();
            for(byte v:MessageDigest.getInstance("SHA-256").digest(result))expected.append(String.format("%02x",v&255));
            check(claimed.equals(expected.toString()));
            PcmExcerpt.trim(cut,nested,42,43);check(PcmExcerpt.count(nested)==1);
            check(Files.readAllBytes(nested.toPath())[44]==original[44+2*(from+42)]);
            int[] peaks=PcmExcerpt.peaks(source,18),truth=new int[18];
            for(int i=0;i<count;i++){int bin=i*18/count;truth[bin]=Math.max(truth[bin],Math.abs((int)(short)(i*359)));}
            check(Arrays.equals(peaks,truth));
            rejects(()->PcmExcerpt.trim(source,nested,-1,1));rejects(()->PcmExcerpt.trim(source,nested,4,4));
            rejects(()->PcmExcerpt.trim(source,nested,0,count+1));rejects(()->PcmExcerpt.trim(source,source,0,1));
            check(Arrays.equals(Files.readAllBytes(source.toPath()),original));
            original[22]=2;Files.write(source.toPath(),original);rejects(()->PcmExcerpt.count(source));
            System.out.println("PASS · exact PCM boundaries, nested one-sample cut, source immutability, hash, waveform and invalid input");
        }finally{Files.deleteIfExists(nested.toPath());Files.deleteIfExists(cut.toPath());Files.deleteIfExists(source.toPath());Files.delete(dir);}
    }
}
