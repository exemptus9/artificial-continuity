package org.totalrecall.listening;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.SystemClock;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Encodes available microphone input, separately from moment scoring and Vosk. Single capture worker. */
public final class ArchiveRecorder implements AutoCloseable {
    private final Context app;
    private final ArchiveStore store;
    private static final int MAX_SAMPLES=ArchiveStore.RATE*ArchiveStore.SECONDS;
    private Encoder encoder;
    private long gap=-1,epoch=-1,retryAt,nextSpaceCheck,nextStart;
    private boolean recovering=true;
    public ArchiveRecorder(Context c,ArchiveStore s){app=c.getApplicationContext();store=s;}
    private void recover(){
        if(!recovering)return;
        store.recover();recovering=false;
        // A completed segment can be left pending if Android stopped the app before scheduling sync.
        if(!store.pending(1).isEmpty())scheduleSync();
    }
    private void scheduleSync(){
        try{ArchiveSync.schedule(app);}
        catch(RuntimeException e){ArchiveStore.issue(app,"Audio saved; transfer scheduling pending");}
    }
    /** This boundary never diverts PCM or a failure from the selective recorder. */
    public void accept(short[] pcm,int count){
        if(count<=0)return;
        try{
            long current=ArchiveStore.epoch(app);
            if(current!=epoch){
                boundary("Archive preference changed");epoch=current;
            }
            if(!ArchiveStore.enabled(app))return;
            long now=SystemClock.elapsedRealtime();if(now<retryAt)return;
            recover();
            int rate=ArchiveStore.bitrate(app);
            if(encoder!=null&&encoder.stage.bitrate!=rate)finish();
            if(encoder!=null&&now>=nextSpaceCheck &&
                    app.getFilesDir().getUsableSpace()<ArchiveStore.reserveMiB(app)*1024L*1024){
                LimitAlerts.local(app,true);throw new IOException("Phone free space fell below the archive reserve");
            }
            if(now>=nextSpaceCheck)nextSpaceCheck=now+2000;
            int offset=0;
            while(offset<count){
                if(encoder==null){
                    if(nextStart==0)nextStart=System.currentTimeMillis()-(count-offset)*1000L/ArchiveStore.RATE;
                    ArchiveStore.Stage stage=store.stage(nextStart,rate);
                    try{encoder=new Encoder(stage);encoder.prune=ArchiveStore.prune(app);}
                    catch(IOException|RuntimeException failure){store.discardEmpty(stage);throw failure;}
                }
                int n=(int)Math.min(count-offset,MAX_SAMPLES-encoder.samples);
                encoder.write(pcm,offset,n);offset+=n;
                if(gap>=0){store.endGap(gap,encoder.stage.start);gap=-1;}
                if(encoder.samples>=MAX_SAMPLES || (encoder.prune && encoder.trailingQuiet>=5L*ArchiveStore.RATE))finish();
            }
        }catch(IOException|RuntimeException failure){
            long last=encoder==null?nextStart:encoder.stage.start;
            try{finish();if(nextStart>0)last=nextStart;}catch(IOException|RuntimeException ignore){}
            nextStart=0;
            problem(last>0?last:System.currentTimeMillis(),message(failure));
            retryAt=SystemClock.elapsedRealtime()+5000;
        }
    }
    private static String message(Exception e){
        String detail=e.getMessage();return "Continuous archive unavailable · "+(detail==null?e.getClass().getSimpleName():detail);
    }
    private void problem(long start,String why){
        try{
            if(gap<0)gap=store.gap(start,why);
        }catch(RuntimeException full){ArchiveStore.issue(app,why);}
    }
    private void finish()throws IOException{
        Encoder media=encoder;encoder=null;if(media==null)return;
        IOException incomplete=null;
        try{media.end();}
        catch(IOException|RuntimeException e){incomplete=new IOException("Audio encoder stopped before flushing",e);}
        finally{media.close();}
        if(!media.stage.partial.isFile()||media.stage.partial.length()==0){
            store.discardEmpty(media.stage);throw new IOException("Encoder produced no complete audio frames");
        }
        if(incomplete==null && media.prune && media.samples>=2L*ArchiveStore.RATE && media.samples==media.trailingQuiet){
            long end=media.stage.start+media.samples*1000L/ArchiveStore.RATE;
            store.omitQuiet(media.stage,end);nextStart=end;return;
        }
        ArchiveStore.Segment segment=store.seal(media.stage,incomplete==null?media.samples:-1);
        nextStart=segment.endMs;scheduleSync();
        if(incomplete!=null)throw incomplete;
    }
    /** Flush the prior coverage interval. No PCM time is bridged over lost microphone access. */
    public void interrupted(String why){
        if(!ArchiveStore.enabled(app)){boundary(why);return;}
        try{recover();finish();}
        catch(IOException|RuntimeException e){problem(nextStart>0?nextStart:System.currentTimeMillis(),message(e));}
        problem(nextStart>0?nextStart:System.currentTimeMillis(),why);
        nextStart=0;retryAt=0;
    }
    /** End capture (or an archive-setting epoch) without keeping an open gap. */
    public void boundary(String why){
        try{finish();}
        catch(IOException|RuntimeException e){problem(nextStart>0?nextStart:System.currentTimeMillis(),message(e));}
        finally{
            try{if(gap>=0)store.endGap(gap,System.currentTimeMillis());}
            catch(RuntimeException failure){ArchiveStore.issue(app,"Could not close coverage gap: "+why);}
            gap=-1;nextStart=0;retryAt=0;
        }
    }
    @Override public void close(){boundary("Stopped");}

    private static final class Encoder implements Closeable{
        final ArchiveStore.Stage stage;
        final MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        final byte[] header=new byte[7],payload=new byte[8192];
        MediaCodec codec;
        FileOutputStream file;
        BufferedOutputStream out;
        boolean started,prune;
        long trailingQuiet;
        long samples,lastSync;
        Encoder(ArchiveStore.Stage s)throws IOException{
            stage=s;
            try{
                codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                MediaFormat format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,ArchiveStore.RATE,1);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                format.setInteger(MediaFormat.KEY_BIT_RATE,s.bitrate);
                codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();started=true;
                file=new FileOutputStream(stage.partial);out=new BufferedOutputStream(file,8192);
                lastSync=SystemClock.elapsedRealtime();
            }catch(IOException|RuntimeException e){close();throw e;}
        }
        int input()throws IOException{
            long end=SystemClock.elapsedRealtime()+1500;
            do{
                int ix=codec.dequeueInputBuffer(10000);
                if(ix>=0)return ix;
                drain(false);
            }while(SystemClock.elapsedRealtime()<end);
            throw new IOException("AAC encoder input stalled");
        }
        void write(short[] pcm,int off,int length)throws IOException{
            int left=length;
            while(left>0){
                int ix=input();ByteBuffer block=codec.getInputBuffer(ix);
                if(block==null)throw new IOException("AAC input buffer unavailable");
                block.clear();block.order(ByteOrder.LITTLE_ENDIAN);
                int n=Math.min(left,block.remaining()/2);if(n==0)throw new IOException("AAC input buffer is empty");
                block.asShortBuffer().put(pcm,off,n);
                codec.queueInputBuffer(ix,0,n*2,samples*1000000L/ArchiveStore.RATE,0);
                if(prune)for(int i=off;i<off+n;i++)trailingQuiet=Math.abs((int)pcm[i])<=4?trailingQuiet+1:0;
                samples+=n;off+=n;left-=n;drain(false);
            }
            long now=SystemClock.elapsedRealtime();
            if(now-lastSync>=1000){out.flush();file.getFD().sync();lastSync=now;}
        }
        void end()throws IOException{
            int ix=input();codec.queueInputBuffer(ix,0,0,samples*1000000L/ArchiveStore.RATE,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(true);out.flush();file.getFD().sync();
        }
        void drain(boolean untilEos)throws IOException{
            long deadline=SystemClock.elapsedRealtime()+2500;
            while(true){
                int ix=codec.dequeueOutputBuffer(info,untilEos?10000:0);
                if(ix==MediaCodec.INFO_TRY_AGAIN_LATER){
                    if(!untilEos)return;
                    if(SystemClock.elapsedRealtime()>=deadline)throw new IOException("AAC output stalled");
                    continue;
                }
                if(ix<0)continue;
                try{
                    if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                        int size=info.size+7;if(size>8191)throw new IOException("AAC frame too large");
                        ByteBuffer data=codec.getOutputBuffer(ix);
                        if(data==null)throw new IOException("AAC output buffer unavailable");
                        data.position(info.offset);data.limit(info.offset+info.size);data.get(payload,0,info.size);
                        header[0]=(byte)0xff;header[1]=(byte)0xf1;header[2]=(byte)(0x40|(8<<2));
                        header[3]=(byte)(0x40|(size>>11));header[4]=(byte)(size>>3);
                        header[5]=(byte)((size<<5)|0x1f);header[6]=(byte)0xfc;
                        out.write(header);out.write(payload,0,info.size);
                    }
                    if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)return;
                }finally{codec.releaseOutputBuffer(ix,false);}
            }
        }
        @Override public void close(){
            if(out!=null)try{out.close();}catch(IOException ignored){}
            else if(file!=null)try{file.close();}catch(IOException ignored){}
            if(codec!=null){
                if(started)try{codec.stop();}catch(RuntimeException ignored){}
                try{codec.release();}catch(RuntimeException ignored){}
            }
        }
    }
}
