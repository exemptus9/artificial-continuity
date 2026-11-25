package org.totalrecall.listening;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;

/** Runs only in a disposable emulator via run.sh. All fixture clips are removed on success. */
public final class RecallSmoke extends Instrumentation {
    private String phase;
    private Context target;
    private StringBuilder receipt = new StringBuilder();
    private void passed(String text) {
        receipt.append(text).append('\n');
        Log.i("RecallSmoke", text);
        Bundle b=new Bundle();
        b.putString("step",text);
        sendStatus(0,b);
    }
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        phase=args==null?"archive":args.getString("phase","archive");
        start();
    }
    @Override public void onStart() {
        Bundle b=new Bundle();
        try {
            target=getTargetContext();
            if ("archive".equals(phase)) archive();
            else if ("restart".equals(phase)) restart();
            else if ("asr".equals(phase)) speech();
            else if ("microphone".equals(phase)) microphone();
            else throw new IllegalArgumentException(phase);
            b.putString("receipt",receipt.toString()); b.putString("outcome","PASS_"+phase);
            finish(Activity.RESULT_OK,b);
        } catch(Throwable failure) {
            Log.e("RecallSmoke","Test failed",failure);
            b.putString("outcome","FAIL_"+phase); b.putString("detail",Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED,b);
        }
    }
    private static void require(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    private static byte[] bytes(InputStream in)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192];
        for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();
    }
    private static String hex(byte[] data)throws Exception{
        byte[] md=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder s=new StringBuilder();
        for(byte b:md)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    private static byte[] file(File path)throws Exception{try(InputStream in=new FileInputStream(path)){return bytes(in);}}
    private void archive()throws Exception {
        final String marker="recall-smoke-"+UUID.randomUUID();
        final Store store=new Store(target);
        final long[] id={-1};
        Method save=Store.class.getDeclaredMethod("save",SelectiveEngine.Clip.class);
        save.setAccessible(true);
        SelectiveEngine engine=new SelectiveEngine(new SelectiveEngine.Listener(){
            public void onClip(SelectiveEngine.Clip clip){try{id[0]=(Long)save.invoke(store,clip);}catch(Exception ex){throw new RuntimeException(ex);}}
            public void onPauseRequested(){throw new AssertionError("Unexpected voice pause");}
        });
        try {
            short[] second=new short[16000]; Arrays.fill(second,(short)620);
            engine.pcm(second,second.length);engine.text("Now we are speaking ordinary words.",true);
            Arrays.fill(second,(short)0);for(int i=0;i<6;i++)engine.pcm(second,second.length);
            require(id[0]==-1,"Incidental data retained");
            Arrays.fill(second,(short)800); engine.pcm(second,second.length);
            engine.text("I decided to use the blue cover for the book "+marker,true);
            Arrays.fill(second,(short)0);for(int i=0;i<5;i++)engine.pcm(second,second.length);
            require(id[0]>0,"No automatic saved draft");
            List<Store.Moment> rows=store.search(marker);
            require(rows.size()==1&&rows.get(0).kind.contains(SelectiveEngine.DECISION),"Decision/uniqueness failure");
            Store.Moment m=rows.get(0);
            require(m.transcript.contains("ordinary words"),"Missing audio lead-in transcript");
            byte[] wav=file(m.audio);require(wav.length>2*16000*8,"Lead-in audio lost");
            require(hex(wav).equals(m.sha256),"Stored WAV digest mismatch");
            passed("Typed recognizer fixture + synthetic PCM: ordinary speech produced no stand-alone event; selected decision retained with lead-in, one SQLite row and WAV hash verified.");
            MediaMetadataRetriever meta=new MediaMetadataRetriever();
            try{meta.setDataSource(m.audio.getAbsolutePath());require(Long.parseLong(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))>=8000,"Unplayable audio duration");}
            finally{meta.release();}
            MediaPlayer player=new MediaPlayer();
            try{player.setVolume(0,0);player.setDataSource(m.audio.getAbsolutePath());player.prepare();player.start();
                SystemClock.sleep(240);require(player.isPlaying()||player.getCurrentPosition()>0,"Player did not start");}
            finally{player.release();}
            passed("Android decoded the retained WAV and started muted playback.");
            store.feedback(m.id,"keep");store.edit(m.id,"Fixture title",marker+" note","corrected words");
            m=store.search(marker+" corrected").get(0);
            require("keep".equals(m.feedback)&&m.transcript.contains("blue cover")&&hex(file(m.audio)).equals(m.sha256),"Original or feedback lost");
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            store.exportZip(output,Collections.singletonList(m.id));
            boolean audio=false,index=false;
            try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))){
                for(ZipEntry e;(e=zip.getNextEntry())!=null;){
                    byte[] data=bytes(zip);
                    if(e.getName().equals("audio/"+m.uid+".wav"))audio=hex(data).equals(m.sha256);
                    if(e.getName().equals("index.json")){
                        JSONObject doc=new JSONObject(new String(data,StandardCharsets.UTF_8));
                        JSONObject record=doc.getJSONArray("clips").getJSONObject(0);
                        index=doc.getJSONArray("clips").length()==1 && record.getString("id").equals(m.uid)
                            && record.getString("sha256").equals(m.sha256)&&record.getString("feedback").equals("keep");
                    }
                }
            }
            require(audio&&index,"Archive contents mismatch");
            require(store.importZip(new ByteArrayInputStream(output.toByteArray())).contains("1 already present"),"Duplicate ZIP failed dedup");
            require(store.search(marker).size()==1,"Duplicate clip after ZIP restore");
            require(target.getSharedPreferences("recall_test_fixture",0).edit().putString("marker",marker)
                .putString("sha256",m.sha256).commit(),"Cannot save test receipt");
            passed("Feedback, correction, original preservation, multi-term search and scoped ZIP content verified.");
        } finally {engine.finish();store.close();}
    }
    private void restart()throws Exception {
        String marker=target.getSharedPreferences("recall_test_fixture",0).getString("marker",null);
        require(marker!=null,"Missing prior phase");
        try(Store store=new Store(target)){
            List<Store.Moment> found=store.search(marker);
            require(found.size()==1,"Expected exactly one recovered row");
            Store.Moment m=found.get(0);
            require(m.sha256.equals(target.getSharedPreferences("recall_test_fixture",0).getString("sha256",""))
                &&hex(file(m.audio)).equals(m.sha256)&&"keep".equals(m.feedback),"Restart durability failed");
            passed("Stored clip, feedback and SHA-256 survived process restart.");
            Activity activity=launch();
            try{
                assertControl(activity,"Start listening");
                final EditText field=(EditText)findOnUi(activity,"Search saved moments");
                require(field!=null,"Missing search field"); runOnMainSync(()->field.setText(marker));
                waitText(activity,"1 moment",10000);
                passed("Listening activity launched and its search filtered the persisted record.");
            }finally{runOnMainSync(activity::finish);waitForIdleSync();}
            ByteArrayOutputStream restored=new ByteArrayOutputStream();
            store.exportZip(restored, Collections.singletonList(m.id));
            store.delete(m.id);
            require(store.importZip(new ByteArrayInputStream(restored.toByteArray())).contains("1 previously deleted"),
                    "Restore revived a forgotten clip");
            require(!m.audio.exists()&&store.search(marker).isEmpty(),"Deleted fixture remains");
            target.getSharedPreferences("recall_test_fixture",0).edit().clear().commit();
            passed("Fixture row and audio deleted.");
        }
    }
    private void speech()throws Exception {
        try(Store store=new Store(target);OfflineSpeech recognizer=new OfflineSpeech(target);
                InputStream input=getContext().getAssets().open("decision.pcm")){
            byte[] fixture=bytes(input);
            require(fixture.length%2==0 && fixture.length>0,"Invalid PCM fixture");
            java.util.List<Long> saves=new java.util.ArrayList<>();
            Method save=Store.class.getDeclaredMethod("save",SelectiveEngine.Clip.class);
            save.setAccessible(true);
            SelectiveEngine engine=new SelectiveEngine(new SelectiveEngine.Listener(){
                public void onClip(SelectiveEngine.Clip clip){
                    try{saves.add((Long)save.invoke(store,clip));}catch(Exception e){throw new RuntimeException(e);}
                }
                public void onPauseRequested(){throw new AssertionError("Unexpected voice pause");}
            });
            try {
                // Same 100 ms blocks and ASR->command/final/partial->PCM order as the service.
                byte[] stream=Arrays.copyOf(fixture,fixture.length+8*SelectiveEngine.RATE*2);
                short[] samples=new short[1600];
                int offset=0,nextPartialFrame=0;
                while(offset<stream.length){
                    int frame=offset/2;
                    int n=Math.min(samples.length,(stream.length-offset)/2);
                    for(int i=0;i<n;i++)samples[i]=(short)((stream[offset++]&255)|((stream[offset++]&255)<<8));
                    String utterance=recognizer.accept(samples,n);
                    String command=recognizer.pollCommand();
                    require(command==null,"Fixture misheard as manual voice command");
                    if(utterance!=null&&!utterance.isEmpty())engine.text(utterance,true);
                    else if(frame>=nextPartialFrame){
                        nextPartialFrame=frame+SelectiveEngine.RATE/4;
                        String partial=recognizer.partial();
                        if(partial!=null&&!partial.isEmpty())engine.text(partial,false);
                    }
                    engine.pcm(samples,n);
                }
                require(saves.size()==1,"Expected one automatic decision clip after silence; got "+saves.size());
                Store.Moment saved=null;
                for(Store.Moment row:store.search("blue book"))if(row.id==saves.get(0))saved=row;
                require(saved!=null,"Saved synthetic speech missing from text search");
                require(SelectiveEngine.DECISION.equals(saved.kind)&&saved.reason.toLowerCase(Locale.ROOT).contains("choice"),
                    "Unexpected selection reason: "+saved.reason);
                byte[] wav=file(saved.audio);
                require(wav.length>=44+fixture.length && wav[0]=='R' && wav[8]=='W',"WAV missing fixture or header");
                for(int i=0;i<fixture.length;i++)
                    require(wav[44+i]==fixture[i],"Original PCM fixture changed at byte "+i);
                require(hex(wav).equals(saved.sha256),"Automatic clip differs from its stored digest");
                passed("Bundled offline ASR -> automatic project decision -> searchable SQLite row and original WAV. "
                    +"Verified "+fixture.length+" input bytes and catalog SHA-256; no manual Keep or supplied transcript. Recognized: "
                    +saved.transcript);
            } finally {
                engine.pause();
                for(Long id:saves)store.delete(id);
            }
        }
    }
    private Activity launch(){
        Intent intent=new Intent().setClassName(target.getPackageName(),"org.totalrecall.listening.RecallListenActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity act=startActivitySync(intent);
        waitForIdleSync();return act;
    }
    private View find(View v,String wanted){
        if(v.getContentDescription()!=null && wanted.contentEquals(v.getContentDescription()))return v;
        if(v instanceof TextView && wanted.contentEquals(((TextView)v).getText()))return v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View match=find(g.getChildAt(i),wanted);if(match!=null)return match;}}
        return null;
    }
    private View findOnUi(Activity a,String wanted){
        final View[] v={null};runOnMainSync(()->v[0]=find(a.getWindow().getDecorView(),wanted));return v[0];
    }
    private View waitText(Activity a,String text,long timeout){
        long end=SystemClock.elapsedRealtime()+timeout;
        do{View v=findOnUi(a,text);if(v!=null)return v;SystemClock.sleep(100);}while(SystemClock.elapsedRealtime()<end);
        throw new AssertionError("Control or value missing: "+text);
    }
    private void assertControl(Activity a,String text){require(waitText(a,text,8000) instanceof Button,"Expected button "+text);}
    private void deviceShell(String command)throws Exception {
        android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);
        try(InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){bytes(in);}
    }
    private void microphone()throws Exception{
        Activity a=launch();
        android.os.PowerManager power=(android.os.PowerManager) target.getSystemService(Context.POWER_SERVICE);
        java.util.Set<String> previous=new java.util.HashSet<>();
        try(Store store=new Store(target)){
            for(Store.Moment row:store.search(""))previous.add(row.uid);
            View start=waitText(a,"Start listening",8000);runOnMainSync(start::performClick);
            long end=SystemClock.elapsedRealtime()+120000;
            while(SystemClock.elapsedRealtime()<end&&!RecallService.status(target).startsWith("Listening"))SystemClock.sleep(250);
            require(RecallService.isListening()&&RecallService.status(target).startsWith("Listening"),"Foreground capture not active: "+RecallService.status(target));
            deviceShell("input keyevent KEYCODE_SLEEP");
            end=SystemClock.elapsedRealtime()+7000;
            while(power.isInteractive()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
            require(!power.isInteractive(),"Emulator display did not turn off");
            SystemClock.sleep(4000);
            require(!power.isInteractive()&&RecallService.isListening(),"Listening interrupted while screen was off");
            // Dispatch the same foreground service intent used by the notification's Keep action.
            target.startService(new Intent(target,RecallService.class).setAction(RecallService.KEEP));
            Store.Moment kept=null;
            end=SystemClock.elapsedRealtime()+35000;
            while(kept==null && SystemClock.elapsedRealtime()<end){
                require(!power.isInteractive(),"Display became interactive before the save finished");
                for(Store.Moment row:store.search(""))if(!previous.contains(row.uid)&&row.kind.contains(SelectiveEngine.MANUAL)){
                    kept=row;break;
                }
                if(kept==null)SystemClock.sleep(250);
            }
            require(kept!=null,"No buffered Keep saved while asleep: "+RecallService.status(target));
            byte[] wav=file(kept.audio);
            require(kept.endMs>kept.startMs && wav.length>44 && hex(wav).equals(kept.sha256),"Asleep clip missing audio or hash");
            require(!power.isInteractive(),"Display woke before WAV verification");
            passed("Screen off throughout retrospective save: foreground microphone accepted Keep, stored "
                +(kept.endMs-kept.startMs)+" ms of emulator PCM and verified WAV bytes against catalog SHA-256.");
            deviceShell("input keyevent KEYCODE_WAKEUP");deviceShell("wm dismiss-keyguard");
            View pause=waitText(a,"Pause listening",10000);runOnMainSync(pause::performClick);
            waitText(a,"Start listening",20000);
            require(!RecallService.isListening(),"UI pause did not stop service");
            store.delete(kept.id);
            passed("UI start/pause and emulator microphone foreground service, real ASR model initialization and release.");
        }finally{
            deviceShell("input keyevent KEYCODE_WAKEUP");deviceShell("wm dismiss-keyguard");
            if(RecallService.isListening())target.startService(new Intent(target,RecallService.class).setAction(RecallService.PAUSE));
            runOnMainSync(a::finish);waitForIdleSync();
        }
    }
}
