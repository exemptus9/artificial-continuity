package org.totalrecall.listening;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Real foreground-service window, independent dictation/command decoders, catalog and WAV writer. */
public final class SpokenChoiceSmoke {
    private static Method method(Class<?> type,String name,Class<?>... params)throws Exception {
        Method result=type.getDeclaredMethod(name,params);result.setAccessible(true);return result;
    }
    private final ByteArrayOutputStream source=new ByteArrayOutputStream();
    private final List<String> requests=new ArrayList<>(),receipts=new ArrayList<>();
    private final List<Long> saved=new ArrayList<>();
    private byte[] frozen;
    private Object window;
    private Method tick;

    private void feed(byte[] data,boolean prompt)throws Exception {
        for(int pos=0;pos<data.length;){
            int count=Math.min(1600,(data.length-pos)/2);
            short[] samples=new short[count];
            for(int i=0;i<count;i++)samples[i]=(short)((data[pos+2*i]&255)|(data[pos+2*i+1]<<8));
            tick.invoke(window,samples,count,prompt);
            source.write(data,pos,count*2);pos+=count*2;
        }
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}

    public String run(Context target,Context test,Store store,OfflineSpeech speech)throws Exception {
        byte[][] turns=new byte[7][];
        try(DataInputStream in=new DataInputStream(new GZIPInputStream(test.getAssets().open("creative-choice.pcm.gz")))){
            for(int i=0;i<turns.length;i++){turns[i]=new byte[in.readInt()];in.readFully(turns[i]);}
        }
        RecallService service=new RecallService();
        method(android.content.ContextWrapper.class,"attachBaseContext",Context.class).invoke(service,target);
        Class<?> type=Class.forName(RecallService.class.getName()+"$Window",true,RecallService.class.getClassLoader());
        Constructor<?> construct=type.getDeclaredConstructor(RecallService.class,Store.class,JournalStore.class,
            OfflineSpeech.class,SelectiveEngine.Listener.class);construct.setAccessible(true);
        tick=method(type,"tick",short[].class,int.class,boolean.class);
        Method begin=method(type,"begin",String.class,String.class),end=method(type,"end",boolean.class,String.class);
        Method save=method(Store.class,"save",SelectiveEngine.Clip.class);
        SelectiveEngine.Listener events=new SelectiveEngine.Listener(){
            public void onSpokenRequest(String request){
                requests.add(request);
                if(request.equals("choose"))frozen=source.toByteArray();
            }
            public void onVoice(String text,boolean wake){receipts.add(text);}
            public void onPauseRequested(){throw new AssertionError("Choice replay paused the microphone");}
            public void onClip(SelectiveEngine.Clip clip){
                try{saved.add((Long)save.invoke(store,clip));}catch(Exception e){throw new RuntimeException(e);}
            }
        };
        int initial=store.search("").size();
        try(JournalStore journal=new JournalStore(target)){
            window=construct.newInstance(service,store,journal,speech,events);
            for(String answer:new String[]{"cancel","timeout","split cancel","line"}){
                frozen=null;source.reset();requests.clear();receipts.clear();
                begin.invoke(window,"choice playback","");
                int before=saved.size();
                try{
                    if(answer.equals("line")){
                        Field engine=type.getDeclaredField("engine");engine.setAccessible(true);
                        ((SelectiveEngine)engine.get(window)).text("hey recall move mountains",true);
                        require(receipts.stream().anyMatch(v->v.startsWith("Command unclear")),"Missing unclear receipt");
                        receipts.clear();
                        // Replay the actual spoken unclear/help hint into the microphone like a device speaker.
                        feed(turns[6],true);feed(new byte[32000],false);
                        require(requests.isEmpty() && receipts.isEmpty() && saved.size()==before,
                            "The spoken retry hint issued a command "+requests+" "+receipts);
                    }
                    feed(turns[0],false);feed(new byte[32000],false);
                    if(answer.equals("split cancel")){
                        feed(new byte[2*32000],false);
                        feed(turns[4],false);feed(new byte[48000],false);
                        require(requests.equals(Arrays.asList("ready")),"Standalone wake did not announce Ready "+requests+" "+receipts);
                        requests.clear();
                        // The response follows the spoken-ready interval, independently of the wake utterance.
                        feed(new byte[16000],true);feed(turns[5],false);
                    }else feed(turns[1],false);
                    feed(new byte[48000],false);
                    require(frozen!=null && frozen.length>=turns[0].length && saved.size()==before
                            && receipts.contains("Choose a save category"),"Missing uncommitted voice choice "+requests+" "+receipts);
                    feed(turns[2],true);feed(new byte[32000],true); // A spoken 'line' from the app must never answer itself.
                    require(saved.size()==before && !requests.contains("choiceClosed"),"Prompt answered its own question");
                    if(answer.equals("line"))feed(turns[2],false);
                    if(answer.endsWith("cancel"))feed(turns[3],false);
                    feed(new byte[(answer.equals("timeout")?12:2)*32000],false);
                    require(requests.equals(Arrays.asList("choose","choiceClosed")),"Choice lifecycle was "+requests+" "+receipts);
                    if(!answer.equals("line")){
                        require(saved.size()==before && receipts.contains(answer.endsWith("cancel")?"Choice canceled":"Save choice expired"),
                            "Discarded choice wrote audio or missed its receipt "+receipts);
                        continue;
                    }
                    require(saved.size()==before+1,"Spoken label failed to save one clip");
                    Store.Moment row=null;
                    for(Store.Moment candidate:store.search(""))if(candidate.id==saved.get(before))row=candidate;
                    require(row!=null && row.kind.equals(SelectiveEngine.POETRY) && row.transcript.contains("paper lantern")
                            && row.cueText!=null && row.cueText.contains("paper lantern") && !row.transcript.endsWith("save as"),
                        "Wrong category or missing source passage");
                    byte[] wav=Files.readAllBytes(row.audio.toPath());
                    require(wav.length==44+frozen.length && Arrays.equals(Arrays.copyOfRange(wav,44,wav.length),frozen),
                        "Saved source PCM differs from the rewind held before the spoken question");
                    StringBuilder sha=new StringBuilder();
                    for(byte value:MessageDigest.getInstance("SHA-256").digest(wav))sha.append(String.format(Locale.ROOT,"%02x",value&255));
                    require(sha.toString().equals(row.sha256),"Saved choice hash differs from catalog");
                }finally{end.invoke(window,false,"Choice replay finished");}
            }
            require(store.search("").size()==initial+1,"Cancel or expiry wrote a catalog row");
            return "Spoken save-as, line, cancel and timeout replayed through the foreground service. Prompt loopback was excluded and frozen source PCM and WAV SHA-256 verified.";
        }finally{for(Long id:saved)store.delete(id);}
    }
}
