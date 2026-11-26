package org.totalrecall.listening;

import android.app.*;
import android.media.*;
import android.os.SystemClock;
import android.widget.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Review existing media privately. Decoding never opens a microphone. */
public final class AudioSituation {
    private AudioSituation(){}
    public interface Input {void open(MediaExtractor extractor)throws Exception;}
    public static void show(Activity activity,Input input){
        AtomicBoolean closed=new AtomicBoolean();
        ScrollView scroll=new ScrollView(activity);
        TextView text=new TextView(activity);int pad=(int)(18*activity.getResources().getDisplayMetrics().density);
        text.setPadding(pad,pad,pad,pad);text.setTextSize(16);text.setTextIsSelectable(true);
        text.setText("Opening recording and offline speech model…");scroll.addView(text);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Recording analysis").setView(scroll).setPositiveButton("Close",null).create();
        dialog.setOnDismissListener(d->closed.set(true));dialog.show();
        new Thread(()->{
            try{
                String report=analyze(activity,input,closed,(message)->activity.runOnUiThread(()->{
                    if(!closed.get()&&!activity.isDestroyed())text.setText(message);
                }));
                activity.runOnUiThread(()->{if(!closed.get()&&!activity.isDestroyed())text.setText(report);});
            }catch(Exception|LinkageError ex){activity.runOnUiThread(()->{
                if(!closed.get()&&!activity.isDestroyed())text.setText("Could not analyze this recording. "+(ex.getMessage()==null?"Try opening the original audio.":ex.getMessage()));
            });}
        },"recall-recording-review").start();
    }
    interface Progress {void update(String message);}
    static String time(long seconds){return String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
    static String analyze(Activity a,Input src,AtomicBoolean stop,Progress progress)throws Exception{
        MediaExtractor ex=new MediaExtractor();MediaCodec codec=null;
        try{
            src.open(ex);int track=0;
            while(track<ex.getTrackCount()&&!ex.getTrackFormat(track).getString(MediaFormat.KEY_MIME).startsWith("audio/"))track++;
            if(track==ex.getTrackCount())throw new IOException("No readable audio track.");
            ex.selectTrack(track);MediaFormat format=ex.getTrackFormat(track);
            String mime=format.getString(MediaFormat.KEY_MIME);
            int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            boolean raw="audio/raw".equals(mime);
            if(!raw){codec=MediaCodec.createDecoderByType(mime);codec.configure(format,null,null,0);codec.start();}
            if(raw && format.containsKey(MediaFormat.KEY_PCM_ENCODING) && format.getInteger(MediaFormat.KEY_PCM_ENCODING)!=AudioFormat.ENCODING_PCM_16BIT)
                throw new IOException("This PCM format is unsupported.");
            try(OfflineSpeech speech=new OfflineSpeech(a,false)){
                Scan scan=new Scan(a,speech);
                MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();boolean eof=false;long tick=0,lastWork=SystemClock.elapsedRealtime();
                ByteBuffer packet=raw?ByteBuffer.allocateDirect(256*1024):null;
                while(!stop.get()&&!a.isDestroyed()&&!a.isFinishing()){
                    if(raw){
                        packet.clear();int n=ex.readSampleData(packet,0);if(n<0)break;
                        packet.position(0);packet.limit(n);scan.consume(packet,rate,channels);ex.advance();
                    }else{
                        if(!eof){
                            int i=codec.dequeueInputBuffer(10000);
                            if(i>=0){ByteBuffer b=codec.getInputBuffer(i);b.clear();int n=ex.readSampleData(b,0);
                                eof=n<0;codec.queueInputBuffer(i,0,Math.max(n,0),eof?0:Math.max(0,ex.getSampleTime()),eof?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);
                                if(!eof)ex.advance();lastWork=SystemClock.elapsedRealtime();}
                        }
                        int i=codec.dequeueOutputBuffer(info,10000);
                        if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                            MediaFormat out=codec.getOutputFormat();rate=out.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            if(out.containsKey(MediaFormat.KEY_PCM_ENCODING)&&out.getInteger(MediaFormat.KEY_PCM_ENCODING)!=AudioFormat.ENCODING_PCM_16BIT)throw new IOException("Decoder returned unsupported sample format.");
                        }else if(i>=0){
                            try{if(info.size>0){ByteBuffer b=codec.getOutputBuffer(i);b.position(info.offset);b.limit(info.offset+info.size);scan.consume(b,rate,channels);}}
                            finally{codec.releaseOutputBuffer(i,false);}
                            lastWork=SystemClock.elapsedRealtime();if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)break;
                        }
                        if(SystemClock.elapsedRealtime()-lastWork>15000)throw new IOException("Audio decoder stopped responding.");
                    }
                    if(SystemClock.elapsedRealtime()-tick>1500){tick=SystemClock.elapsedRealtime();progress.update("Analyzed through "+time(scan.samples/16000)+" · working on this device…");}
                }
                if(stop.get()||a.isDestroyed()||a.isFinishing())return "";
                return scan.finish();
            }
        }finally{try{if(codec!=null)codec.release();}finally{ex.release();}}
    }
    static final class Scan{
        final OfflineSpeech speech;final Situation context;final short[] window=new short[1600];
        int used,phase;long samples,active,quiet,longest,start;double energy,peak;
        Scan(Activity a,OfflineSpeech speech)throws IOException{this.speech=speech;context=new Situation(a);}
        void consume(ByteBuffer b,int rate,int channels)throws IOException{
            if(channels<1||channels>32||rate<4000||rate>384000)throw new IOException("Invalid audio format");
            b.order(ByteOrder.LITTLE_ENDIAN);
            while(b.remaining()>=2*channels){int sum=0;for(int i=0;i<channels;i++)sum+=b.getShort();
                short value=(short)(sum/channels);phase+=16000;
                while(phase>=rate){phase-=rate;window[used++]=value;samples++;energy+=(double)value*value;peak=Math.max(peak,Math.abs((int)value));
                    if(used==window.length)flush();}
            }
        }
        void flush(){
            if(used==0)return;
            if(energy/used>=10738){active+=used;quiet=0;}else{quiet+=used;longest=Math.max(longest,quiet);}
            String line=speech.accept(window,used);
            if(line!=null){context.add(line,time(start/16000)+"–"+time(samples/16000));start=samples;}
            used=0;energy=0;
        }
        String finish(){
            flush();context.add(speech.finish(),time(start/16000)+"–"+time(samples/16000));
            if(samples==0)return "No audio samples were decoded.";
            return "Audio decoded  "+time(samples/16000)+"\nAbove −50 dBFS  "+Math.round(active*100d/samples)+"% of decoded time"+
                "\nLongest quiet stretch  "+String.format(Locale.ROOT,"%.1f s",longest/16000d)+"\nPeak level  "+
                (peak==0?"silence":String.format(Locale.ROOT,"%.1f dBFS",20*Math.log10(peak/32768d)))+
                "\n\nFrom offline speech recognition\n"+context.result()+"\n\nTimes are approximate. The level threshold measures sound, including background noise.";
        }
    }
}
