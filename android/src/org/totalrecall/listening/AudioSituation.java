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
        show(activity,null,"",input);
    }
    static void show(Activity activity,StoryNotes.Kind kind,String id,Input input){
        show(activity,kind,id,input,false);
    }
    private static void show(Activity activity,StoryNotes.Kind kind,String id,Input input,boolean focused){
        AtomicBoolean closed=new AtomicBoolean();
        ScrollView scroll=new ScrollView(activity);
        TextView text=new TextView(activity);int pad=(int)(18*activity.getResources().getDisplayMetrics().density);
        text.setPadding(pad,pad,pad,pad);text.setTextSize(16);text.setTextIsSelectable(true);
        text.setText("Opening recording and offline speech model…");LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.addView(text);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Recording analysis").setView(scroll).setNeutralButton(focused?"Try original":"Try voice focus",(d,w)->show(activity,kind,id,input,!focused)).setPositiveButton("Close",null).create();
        dialog.setOnDismissListener(d->closed.set(true));dialog.show();
        new Thread(()->{
            try{
                String report=analyze(activity,input,closed,(message)->activity.runOnUiThread(()->{
                    if(!closed.get()&&!activity.isDestroyed())text.setText(message);
                }),focused);
                activity.runOnUiThread(()->{if(!closed.get()&&!activity.isDestroyed()){
                    text.setText(report);
                    if(kind!=null && report.contains("Suggested account"))body.addView(StoryNotes.button(activity,kind,id,report.substring(report.indexOf("Suggested account")+17).trim()));
                }});
            }catch(Exception|LinkageError ex){activity.runOnUiThread(()->{
                if(!closed.get()&&!activity.isDestroyed())text.setText("Could not analyze this recording. "+(ex.getMessage()==null?"Try opening the original audio.":ex.getMessage()));
            });}
        },"recall-recording-review").start();
    }
    interface Progress {void update(String message);}
    static String time(long seconds){return String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
    static String analyze(Activity a,Input src,AtomicBoolean stop,Progress progress)throws Exception{
        return analyze(a,src,stop,progress,false);
    }
    static String analyze(Activity a,Input src,AtomicBoolean stop,Progress progress,boolean focused)throws Exception{
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
            try(OfflineSpeech speech=new OfflineSpeech(a,false,a.getSharedPreferences("recall-speech",0).getBoolean("voices",true))){
                Scan scan=new Scan(a,speech);scan.focused=focused;
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
            boolean useLeft=false;
            if(channels==2){
                // Opposite-polarity stereo microphones can cancel during mono downmix.
                long mixed=0,left=0,right=0;
                for(int i=b.position();i+3<b.limit();i+=16){
                    int l=b.getShort(i),r=b.getShort(i+2);
                    mixed+=(long)(l+r)*(l+r);left+=(long)l*l;right+=(long)r*r;
                }
                useLeft=left>0 && right>0 && mixed<(left+right)/8;
            }
            while(b.remaining()>=2*channels){int sum=0;
                for(int i=0;i<channels;i++){int v=b.getShort();if(useLeft){if(i==0)sum=v*channels;}else sum+=v;}
                short value=(short)(sum/channels);phase+=16000;
                while(phase>=rate){phase-=rate;window[used++]=value;samples++;energy+=(double)value*value;peak=Math.max(peak,Math.abs((int)value));
                    if(used==window.length)flush();}
            }
        }
        boolean focused;double last,filtered,gain=1;
        void focus(){
            double[] frame=new double[used];double max=0;
            for(int i=0;i<used;i++){
                filtered=.965*(filtered+window[i]-last);last=window[i];frame[i]=filtered;max=Math.max(max,Math.abs(filtered));
            }
            double target=max>12?Math.min(10,Math.max(1,7000/max)):1;
            gain=Math.min(target,.65*gain+.35*target);
            for(int i=0;i<used;i++)window[i]=(short)Math.max(-32768,Math.min(32767,Math.round(frame[i]*gain)));
        }
        final java.util.ArrayList<String> turns=new java.util.ArrayList<>();
        void addTurn(String text){
            if(text==null||text.trim().isEmpty())return;
            String who=speech.attributedVoice();
            String at=time(start/16000)+"–"+time(samples/16000)+(who.isEmpty()?"":" · "+who);
            context.add(text,at);start=samples;
            if(turns.size()<40)turns.add(at+"  "+text.substring(0,Math.min(250,text.length())));
        }
        void flush(){
            if(used==0)return;
            if(energy/used>=10738){active+=used;quiet=0;}else{quiet+=used;longest=Math.max(longest,quiet);}
            if(focused)focus();
            String line=speech.accept(window,used);
            addTurn(line);
            used=0;energy=0;
        }
        String finish(){
            flush();addTurn(speech.finish());
            if(samples==0)return "No audio samples were decoded.";
            return "Audio decoded  "+time(samples/16000)+"\nAbove −50 dBFS  "+Math.round(active*100d/samples)+"% of decoded time"+
                "\nLongest quiet stretch  "+String.format(Locale.ROOT,"%.1f s",longest/16000d)+"\nPeak level  "+
                (peak==0?"silence":String.format(Locale.ROOT,"%.1f dBFS",20*Math.log10(peak/32768d)))+
                "\nDifficult passages checked across three decodes  "+speech.compared+" · matching enhanced readings "+speech.agreed+" · unresolved "+speech.uncertain+"\n\nFrom offline speech recognition\n"+context.result()+(turns.isEmpty()?"":"\n\nRecognized passages (first 40)\n"+String.join("\n",turns))+"\n\nTimes are approximate. The level threshold measures sound, including background noise.\n\nSuggested account\n"+context.draft();
        }
    }
}
