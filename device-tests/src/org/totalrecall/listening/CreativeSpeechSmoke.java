package org.totalrecall.listening;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Anonymous performance replay. Both the test and release APK contain their own assets. */
public final class CreativeSpeechSmoke {
    private CreativeSpeechSmoke() {}

    public static String run(Context target, Context test) throws Exception {
        try (InputStream words=target.getAssets().open("listening/cmudict-prosody.tsv.gz")) {
            SelectiveEngine.loadPronunciations(words);
        }
        try (Store store=new Store(target); OfflineSpeech asr=new OfflineSpeech(target)) {
            String verse=replay(test, store, asr, "verse", SelectiveEngine.POETRY, "rhym");
            asr.resetStream();
            String coin=replay(test, store, asr, "coin", SelectiveEngine.FORGE, "invented");
            asr.resetStream();
            return verse+"\n"+coin+"\n"+replay(test, store, asr, "address", SelectiveEngine.POETRY, "you chose");
        }
    }

    private static String replay(Context test, Store store, OfflineSpeech asr, String name,
                                 String category, String reason) throws Exception {
        byte[] original;
        try (InputStream input=new GZIPInputStream(test.getAssets().open("creative-"+name+".pcm.gz"))) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            byte[] part=new byte[8192];
            for (int count; (count=input.read(part))!=-1;) bytes.write(part,0,count);
            original=bytes.toByteArray();
        }
        boolean addressed=name.equals("address");
        Method liveAccept=OfflineSpeech.class.getDeclaredMethod("accept",short[].class,int.class,boolean.class,boolean.class);
        Method rawFinal=OfflineSpeech.class.getDeclaredMethod("rawWords"),rawPartial=OfflineSpeech.class.getDeclaredMethod("rawPartial");
        liveAccept.setAccessible(true);rawFinal.setAccessible(true);rawPartial.setAccessible(true);
        List<Long> saved=new ArrayList<>();
        List<String> heard=new ArrayList<>();
        Method save=Store.class.getDeclaredMethod("save",SelectiveEngine.Clip.class);
        save.setAccessible(true);
        SelectiveEngine engine=new SelectiveEngine(new SelectiveEngine.Listener(){
            public void onClip(SelectiveEngine.Clip clip){
                try { saved.add((Long)save.invoke(store,clip)); }
                catch(Exception error){ throw new RuntimeException(error); }
            }
            public void onPauseRequested(){ throw new AssertionError("Creative fixture triggered pause"); }
        });
        try {
            byte[] stream=Arrays.copyOf(original,original.length+2*9*SelectiveEngine.RATE);
            short[] frame=new short[1600];
            int nextPartial=0;
            for(int offset=0; offset<stream.length;){
                int at=offset/2;
                int count=Math.min(frame.length,(stream.length-offset)/2);
                for(int i=0;i<count;i++)frame[i]=(short)((stream[offset++]&255)|((stream[offset++]&255)<<8));
                String words=addressed?(String)liveAccept.invoke(asr,frame,count,true,false):asr.accept(frame,count);
                String originalWords=words==null?null:(String)rawFinal.invoke(asr);
                String command=asr.pollCommand();
                if(!addressed && command!=null)throw new AssertionError("Creative fixture triggered a manual command");
                if(words!=null && !words.isEmpty()){
                    heard.add(words);
                    engine.text(originalWords,true);
                } else if(at>=nextPartial){
                    nextPartial=at+SelectiveEngine.RATE*14/100;
                    String partial=asr.partial();
                    if(command==null && partial!=null && !partial.isEmpty())engine.text((String)rawPartial.invoke(asr),false);
                }
                if(command!=null && !command.equals(originalWords))engine.text(command,true);
                engine.pcm(frame,count);
            }
            if(saved.size()!=1)throw new AssertionError(name+" saved "+saved.size()+" clips; heard "+heard);
            for(Store.Moment row:store.search("")){
                if(row.id!=saved.get(0))continue;
                if(!row.kind.equals(category)||!row.reason.toLowerCase(Locale.ROOT).contains(reason))
                    throw new AssertionError(name+" assigned "+row.kind+" because "+row.reason+"; heard "+heard);
                byte[] wav=Files.readAllBytes(row.audio.toPath());
                if(wav.length<44+original.length || wav[0]!='R' || wav[8]!='W')
                    throw new AssertionError("Missing one or more performance lines in WAV");
                for(int i=0;i<original.length;i++)if(wav[i+44]!=original[i])
                    throw new AssertionError("Original performance differs at byte "+i);
                StringBuilder hex=new StringBuilder();
                for(byte value:MessageDigest.getInstance("SHA-256").digest(wav))hex.append(String.format(Locale.ROOT,"%02x",value&255));
                if(!hex.toString().equals(row.sha256))throw new AssertionError("Catalog hash mismatch");
                if(row.transcript.isEmpty())throw new AssertionError("Missing stored transcript");
                return (addressed?"Addressed split-wake save · ":"Automatic ")+category+" saved and checked "+original.length+" PCM bytes against catalog WAV and SHA-256. "
                        +row.reason+" Heard: "+heard;
            }
            throw new AssertionError("Missing saved "+name+" row");
        } finally {
            engine.pause();
            for(Long id:saved)store.delete(id);
        }
    }
}
