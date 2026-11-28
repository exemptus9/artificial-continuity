package org.totalrecall.listening;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.ParcelFileDescriptor;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.provider.Settings;
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
import java.util.function.BooleanSupplier;
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
            if ("longsession".equals(phase)) longSession();
            else if ("archive".equals(phase)) archive();
            else if ("restart".equals(phase)) restart();
            else if ("asr".equals(phase)) speech();
            else if ("creative".equals(phase)) passed(CreativeSpeechSmoke.run(target,getContext()));
            else if ("microphone".equals(phase)) microphone();
            else if ("journal".equals(phase)) journal();
            else if ("overlay".equals(phase)) overlay();
            else if ("shade".equals(phase)) lockedShade();
            else if ("contention".equals(phase)) contention();
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
            try{player.setVolume(0,0);player.setDataSource(m.audio.getAbsolutePath());player.prepare();
                final String[] levelReceipt={""};
                runOnMainSync(()->{
                    Button setting=new Button(target);PlaybackLevel level=new PlaybackLevel(setting);
                    try{
                        level.select(1);level.attach(player);level.select(2);
                        levelReceipt[0]=setting.getText().toString();
                        level.select(0);require(setting.getText().toString().contains("Original"),"Playback level did not reset");
                    }finally{level.detach();}
                });
                player.start();SystemClock.sleep(240);require(player.isPlaying()||player.getCurrentPosition()>0,"Player did not start");
                passed("Session-specific playback gain: "+levelReceipt[0]+". Original restored and muted player still playable.");}
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
            ExcerptProbe.verify(target,store,m);
            passed("Android excerpt saved exact selected PCM frames and a new UID; its parent bytes and provenance survived clean-vault export/import.");
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
            verifyPredictiveSpelling();
            verifyQuietReview(fixture);
            java.util.List<Long> saves=new java.util.ArrayList<>();
            Method save=Store.class.getDeclaredMethod("save",SelectiveEngine.Clip.class);
            save.setAccessible(true);
            SelectiveEngine engine=new SelectiveEngine(new SelectiveEngine.Listener(){
                public void onClip(SelectiveEngine.Clip clip){
                    try{saves.add((Long)save.invoke(store,clip));}catch(Exception e){throw new RuntimeException(e);}
                }
                public void onPauseRequested(){throw new AssertionError("Unexpected voice pause");}
            });
            Method start=method(LiveCapture.class,"start");
            Method words=method(LiveCapture.class,"words",String.class,boolean.class);
            Method samplesIn=method(LiveCapture.class,"samples",short[].class,int.class);
            Method stop=method(LiveCapture.class,"stop",String.class);
            start.invoke(null);
            boolean sawPartial=false, sawFinal=false, sawLevel=false;
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
                    if(utterance!=null&&!utterance.isEmpty()){
                        words.invoke(null,utterance,true);
                        LiveCapture.Snapshot live=LiveCapture.snapshot();
                        sawFinal|=live.finals.contains(utterance) && live.partial.isEmpty();
                        engine.text(utterance,true);
                    }
                    else if(frame>=nextPartialFrame){
                        nextPartialFrame=frame+SelectiveEngine.RATE/4;
                        String partial=recognizer.partial();
                        if(partial!=null&&!partial.isEmpty()){
                            words.invoke(null,partial,false);
                            sawPartial|=partial.equals(LiveCapture.snapshot().partial);
                            engine.text(partial,false);
                        }
                    }
                    samplesIn.invoke(null,samples,n);
                    sawLevel|=LiveCapture.snapshot().level>0.05f;
                    engine.pcm(samples,n);
                    if(offset<fixture.length)SystemClock.sleep(100);
                }
                require(sawPartial && sawFinal && sawLevel,"Missing production live observation: partial="
                        +sawPartial+" final="+sawFinal+" meter="+sawLevel);
                passed("Real offline ASR produced visible-state partial and finalized words; the same fixture PCM moved the production microphone meter.");
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
                stop.invoke(null,"Paused");
                require(liveCleared(),"Transient captions or microphone level survived the pause boundary");
                for(Long id:saves)store.delete(id);
            }
        }
    }
    private void verifyPredictiveSpelling()throws Exception{
        SharedPreferences prefs=target.getSharedPreferences("recall-speech",0);
        String oldEntries=prefs.getString("spellings","[]");
        boolean enabled=prefs.getBoolean("contextSpelling",true);
        Class<?> type=Class.forName("org.totalrecall.listening.SpeechContext");
        java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor(Context.class);ctor.setAccessible(true);
        Object corrector=ctor.newInstance(target);
        Method apply=method(type,"apply",String.class,String.class);
        try{
            prefs.edit().putBoolean("contextSpelling",true).putString("spellings","[[\"rhyme mosaic\",\"RhymeMosaic\"]]").commit();
            require("read it aloud and write it down".equals(apply.invoke(corrector,"","read it allowed and right it down")),"Two contextual edits failed");
            require("aloud".equals(apply.invoke(corrector,"please read it","allowed")),"Nearby speech failed to disambiguate a homophone");
            require("she was allowed to read".equals(apply.invoke(corrector,"","she was allowed to read")),"Literal spelling was damaged");
            require("pun intended peace of paper".equals(apply.invoke(corrector,"","pun intended peace of paper")),"Explicit wordplay was rewritten");
            require("internal rhyme scheme".equals(apply.invoke(corrector,"","internal rime scheme")),"Poetry context lost");
            require("rime on the trees".equals(apply.invoke(corrector,"","rime on the trees")),"Frost spelling overwritten");
            verifyWeakWordRepair();
            require("for RhymeMosaic".equals(apply.invoke(corrector,"","for rhyme mosaic")),"Personal spelling was lost");
            Class<?> profileType=Class.forName("org.totalrecall.listening.VoiceProfiles$Profile");
            java.lang.reflect.Constructor<?> profile=profileType.getDeclaredConstructor(String.class,String.class,String.class);profile.setAccessible(true);
            Object me=profile.newInstance("test-id","My voice","Me"),media=profile.newInstance("media-id","Radio","Media");
            Class<?> wording=Class.forName("org.totalrecall.listening.VoiceWording");
            Object edit=method(wording,"difference",String.class,String.class).invoke(null,"publish my rhyme mosaic draft","publish my RhymeMosaic draft");
            java.lang.reflect.Field heard=wording.getDeclaredField("heard"),wanted=wording.getDeclaredField("wanted"),map=profileType.getDeclaredField("wordings");
            heard.setAccessible(true);wanted.setAccessible(true);map.setAccessible(true);
            for(Object person:new Object[]{me,media}){
                ((java.util.Map)map.get(person)).put(heard.get(edit),wanted.get(edit));method(profileType,"compileWordings").invoke(person);
            }
            prefs.edit().putString("spellings","[]").commit();
            Method adapted=method(type,"apply",String.class,String.class,profileType);
            require("for RhymeMosaic tonight".equals(adapted.invoke(corrector,"","for rhyme mosaic tonight",me)),"Confirmed voice correction lost");
            require("for rhyme mosaic tonight".equals(adapted.invoke(corrector,"","for rhyme mosaic tonight",media)),"Owner wording leaked to media");
            prefs.edit().putBoolean("contextSpelling",false).commit();
            require("read it allowed".equals(apply.invoke(corrector,"","read it allowed")),"Original wording unavailable with corrections off");
        }finally{prefs.edit().putBoolean("contextSpelling",enabled).putString("spellings",oldEntries).commit();}
    }
    private void verifyWeakWordRepair()throws Exception{
        org.json.JSONArray[] rows={new org.json.JSONArray(),new org.json.JSONArray(),new org.json.JSONArray()};
        String[] readings={"keep this lion for tomorrow","keep this line for tomorrow","keep this line for tonight"};
        for(int n=0;n<3;n++)for(String word:readings[n].split(" "))
            rows[n].put(new org.json.JSONObject().put("word",word).put("conf",word.equals("lion")?.34:.96));
        Method repair=method(OfflineSpeech.class,"repairWeakWord",String.class,org.json.JSONArray.class,org.json.JSONArray.class,org.json.JSONArray.class);
        require("keep this line for tomorrow".equals(repair.invoke(null,readings[0],rows[0],rows[1],rows[2])),"Anchored weak word not repaired");
        rows[2].getJSONObject(2).put("conf",.6);
        require(repair.invoke(null,readings[0],rows[0],rows[1],rows[2])==null,"Weak alternative adopted");
    }
    private void verifyQuietReview(byte[] fixture)throws Exception{
        java.lang.reflect.Constructor<OfflineSpeech> constructor=OfflineSpeech.class.getDeclaredConstructor(Context.class,boolean.class,boolean.class);
        constructor.setAccessible(true);
        try(OfflineSpeech review=constructor.newInstance(target,false,false)){
            short[] frame=new short[1600];StringBuilder words=new StringBuilder();
            for(int pos=0;pos<fixture.length/2+16000;){
                int count=Math.min(frame.length,fixture.length/2+16000-pos);
                for(int i=0;i<count;i++,pos++){
                    int off=2*pos;
                    frame[i]=off+1<fixture.length?(short)(((short)((fixture[off]&255)|(fixture[off+1]<<8)))/80):0;
                }
                String result=review.accept(frame,count);if(result!=null)words.append(result).append(' ');
            }
            String tail=review.finish();if(tail!=null)words.append(tail);
            java.lang.reflect.Field compared=OfflineSpeech.class.getDeclaredField("compared");compared.setAccessible(true);
            require(compared.getInt(review)>0,"A faint recording did not trigger additional decodes");
            require(words.length()>0,"Quiet recording analysis lost the whole reference phrase");
        }
    }
    private static final class SpeechChecks {
        boolean partial,finalized,level;
    }
    private SpeechChecks feedWindow(Object window,Method tick,byte[] pcm,boolean pace)throws Exception{
        SpeechChecks seen=new SpeechChecks();
        int frame=1600;
        byte[] data=Arrays.copyOf(pcm,pcm.length+4*32000);
        short[] samples=new short[frame];
        for(int at=0;at<data.length;){
            int count=Math.min(frame,(data.length-at)/2);
            for(int i=0;i<count;i++)samples[i]=(short)((data[at++]&255)|((data[at++]&255)<<8));
            tick.invoke(window,samples,count);
            LiveCapture.Snapshot live=LiveCapture.snapshot();
            seen.partial|=!live.partial.isEmpty();
            seen.finalized|=!live.finals.isEmpty() && live.partial.isEmpty();
            seen.level|=live.level>0.05f;
            if(pace && at<=pcm.length)SystemClock.sleep(110);
        }
        return seen;
    }
    private static java.util.Set<String> audioNames(Context context){
        File[] files=new File(context.getFilesDir(),"listening-audio").listFiles();
        java.util.Set<String> result=new java.util.HashSet<>();
        if(files!=null)for(File f:files)result.add(f.getName());
        return result;
    }
    private void journal()throws Exception{
        Activity a=launch();
        long session=-1;
        try(Store clips=new Store(target);JournalStore journals=new JournalStore(target);
                OfflineSpeech recognizer=new OfflineSpeech(target);
                InputStream input=getContext().getAssets().open("ordinary.pcm")){
            byte[] fixture=bytes(input);
            require(!JournalStore.enabled(target),"Journal should default to off");
            int initialSessions=journals.sessions().size(),initialClips=clips.search("").size();
            java.util.Set<String> oldFiles=audioNames(target);
            readWindows();
            // Drive the exact PCM processing, ASR and persistence methods used by the foreground service.
            // The fixture supplies input samples in place of Android AudioRecord; no release test hooks.
            RecallService service=new RecallService();
            method(android.content.ContextWrapper.class,"attachBaseContext",Context.class).invoke(service,target);
            Class<?> windowType=Class.forName(RecallService.class.getName()+"$Window",true,
                    RecallService.class.getClassLoader());
            java.lang.reflect.Constructor<?> create=windowType.getDeclaredConstructor(RecallService.class,Store.class,
                    JournalStore.class,OfflineSpeech.class,SelectiveEngine.Listener.class);
            create.setAccessible(true);
            Method save=method(Store.class,"save",SelectiveEngine.Clip.class);
            SelectiveEngine.Listener listener=new SelectiveEngine.Listener(){
                public void onClip(SelectiveEngine.Clip clip){try{save.invoke(clips,clip);}catch(Exception e){throw new RuntimeException(e);}}
                public void onPauseRequested(){throw new AssertionError("Everyday speech misheard as a pause command");}
            };
            Object window=create.newInstance(service,clips,journals,recognizer,listener);
            Method tick=method(windowType,"tick",short[].class,int.class);
            Method end=method(windowType,"end",boolean.class,String.class);
            method(LiveCapture.class,"start").invoke(null);
            method(windowType,"begin",String.class,String.class).invoke(window,"instrumentation PCM","");
            try{
                SpeechChecks off=feedWindow(window,tick,fixture,true);
                require(off.partial && off.finalized && off.level,"Production PCM pipeline did not display a partial, a final and audio level");
                require(journals.sessions().size()==initialSessions,"Disabled journal retained everyday speech");
                Method take=method(windowType,"takeAction",boolean.class);
                take.invoke(window,true);feedWindow(window,tick,fixture,false);take.invoke(window,false);
                List<Store.Moment> takes=clips.search("Voice take");require(!takes.isEmpty(),"Manual voice take was not saved");
                Store.Moment captured=takes.get(0);
                try{
                    require(captured.transcript.contains("table"),"Take did not include the original recognition");
                    byte[] audioBytes=java.nio.file.Files.readAllBytes(captured.audio.toPath());
                    require(audioBytes.length==fixture.length+4*32000+44,"Take included samples outside its boundaries");
                    for(int i=0;i<fixture.length;i++)require(audioBytes[i+44]==fixture[i],"Captured wave differs from input");
                    method(Store.class,"correctWords",long.class,String.class).invoke(clips,captured.id,"I revised this line.");
                    Store.Moment revised=clips.get(captured.id);
                    require(revised.transcript.equals(captured.transcript) && revised.correction.equals("I revised this line.") && revised.sha256.equals(captured.sha256),"Correcting words changed the source");
                }finally{clips.delete(captured.id);}

                click(waitText(a,"Full journal · off",10000));
                tapNode("Save all recognized speech on this phone");getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                require(JournalStore.enabled(target),"Journal switch failed to opt in");
                feedWindow(window,tick,fixture,true);
                List<JournalStore.Session> sessions=journals.sessions();
                require(sessions.size()==initialSessions+1,"Missing or duplicate journal session");
                session=sessions.get(0).id;
                require(sessions.get(0).count==1,"Expected one final utterance in opted-in journal");
                click(waitText(a,"Full journal · on",10000));
                tapNode("Save all recognized speech on this phone");getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                require(!JournalStore.enabled(target),"Journal switch failed to opt out");
                feedWindow(window,tick,fixture,false);
                require(journals.sessions().size()==initialSessions+1 && journals.sessions().get(0).count==1,
                        "Speech recorded after journal opt-out");
            }finally{
                end.invoke(window,false,"Paused synthetic input");
                method(LiveCapture.class,"stop",String.class).invoke(null,"Paused");
            }
            try(JournalStore reopened=new JournalStore(target)){
                ByteArrayOutputStream text=new ByteArrayOutputStream();
                reopened.export(session,text);
                String exported=new String(text.toByteArray(),StandardCharsets.UTF_8);
                require(exported.equals(reopened.text(session)) && exported.toLowerCase(Locale.ROOT).contains("table")
                        && reopened.sessions().get(0).endMs>0,"Reopened/exported journal missing final line or session end");
                require(clips.search("").size()==initialClips && oldFiles.equals(audioNames(target)),
                        "An ordinary remark created a selected clip or audio file");
                ByteArrayOutputStream archive=new ByteArrayOutputStream();
                clips.exportZip(archive,Collections.emptyList());
                try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))){
                    for(ZipEntry e;(e=zip.getNextEntry())!=null;){
                        require(e.getName().equals("index.json"),"Full-session text leaked into a clip export");
                        require(!new String(bytes(zip),StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("table"),
                                "Journal utterance leaked into clip archive index");
                    }
                }
                try(android.database.Cursor row=reopened.getReadableDatabase().rawQuery("SELECT id,text FROM lines WHERE session=? AND is_partial=0",new String[]{String.valueOf(session)})){
                    require(row.moveToFirst(),"No editable line");long line=row.getLong(0);String raw=row.getString(1);
                    Method correctLine=method(JournalStore.class,"correctLine",long.class,String.class,String.class);
                    correctLine.invoke(reopened,line,raw,"My corrected journal line.");
                    ByteArrayOutputStream revised=new ByteArrayOutputStream();reopened.export(session,revised);
                    String both=revised.toString("UTF-8");require(both.contains("Originally heard · "+raw) && both.contains("My corrected journal line."),"Journal export lost original or correction");
                    boolean stale=false;try{correctLine.invoke(reopened,line,raw,"A stale edit");}catch(java.lang.reflect.InvocationTargetException e){stale=e.getCause() instanceof IllegalStateException;}
                    require(stale,"An old editor overwrote a newer correction");
                }
                reopened.delete(session);
                final long deleted=session;
                require(reopened.sessions().stream().noneMatch(r->r.id==deleted),"Deleted journal still present");
                boolean absent=false;
                try{reopened.export(session,new ByteArrayOutputStream());}catch(java.io.IOException expected){absent=true;}
                require(absent,"A deleted session can still be exported");session=-1;
            }
            passed("Actual service PCM pipeline recognized ordinary synthetic speech, published a partial, final and meter value, wrote one opt-in journal entry and no standalone audio. Toggle-off blocked subsequent speech. Reopened text export and deletion verified.");
        }finally{
            JournalStore.setEnabled(target,false);
            if(session>=0)try(JournalStore cleanup=new JournalStore(target)){cleanup.delete(session);}
            runOnMainSync(a::finish);waitForIdleSync();
        }
    }
    private void render(Activity a,String name)throws Exception{
        final Bitmap[] out=new Bitmap[1];
        runOnMainSync(()->{
            View v=a.getWindow().getDecorView();
            out[0]=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(out[0]));
        });
        Bitmap bitmap=out[0];
        try{
            require(bitmap.getWidth()>=300 && bitmap.getHeight()>bitmap.getWidth(),"Expected phone-size portrait screen");
            ParcelFileDescriptor[] pipe=getUiAutomation().executeShellCommandRw("dd of=/data/local/tmp/"+name+".png");
            try(InputStream done=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])){
                try(java.io.OutputStream dest=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){
                    require(bitmap.compress(Bitmap.CompressFormat.PNG,100,dest),"Render PNG failed");
                }
                bytes(done);
            }
            passed("Rendered the listening app's own view at "+bitmap.getWidth()+" × "+bitmap.getHeight()+" into the emulator test receipt.");
        }finally{bitmap.recycle();}
    }
    private static Method method(Class<?> type,String name,Class<?>... signature)throws Exception {
        Method m=type.getDeclaredMethod(name,signature);
        m.setAccessible(true);
        return m;
    }
    private static boolean liveCleared(){
        LiveCapture.Snapshot s=LiveCapture.snapshot();
        return s.partial.isEmpty() && s.finals.isEmpty() && s.level==0 && !s.active;
    }
    private static void until(BooleanSupplier predicate,long timeout,String message){
        long end=SystemClock.elapsedRealtime()+timeout;
        do{if(predicate.getAsBoolean())return;SystemClock.sleep(100);}while(SystemClock.elapsedRealtime()<end);
        throw new AssertionError(message);
    }
    private void click(View v){require(v!=null,"Missing clickable view");runOnMainSync(v::performClick);}
    private void startListening(Activity a){
        click(waitText(a,RecallService.pausedAt(target)>0?"Resume listening":"Start listening",10000));
        until(()->RecallService.isListening() && RecallService.status(target).startsWith("Listening"),120000,
                "Foreground microphone failed to start");
    }
    private void stopListening(){
        if(RecallService.isListening()){
            target.startService(new Intent(target,RecallService.class).setAction(RecallService.PAUSE));
            until(()->!RecallService.isListening(),20000,"Listening remained active after pause");
        }
    }
    private void readWindows(){
        AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        getUiAutomation().setServiceInfo(info);
    }
    private AccessibilityNodeInfo node(String label){
        for(AccessibilityWindowInfo window:getUiAutomation().getWindows()){
            AccessibilityNodeInfo root=window.getRoot();
            if(root==null)continue;
            for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(label)){
                if(label.equalsIgnoreCase(n.getText()==null?"":n.getText().toString())
                        ||label.contentEquals(n.getContentDescription()==null?"":n.getContentDescription()))return n;
            }
        }
        return null;
    }
    private AccessibilityNodeInfo waitNode(String label){
        final AccessibilityNodeInfo[] result=new AccessibilityNodeInfo[1];
        until(()->(result[0]=node(label))!=null,15000,"Missing accessibility node "+label);
        return result[0];
    }
    private void tapNode(String label){
        AccessibilityNodeInfo n=waitNode(label);
        require(n.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Control rejected click: "+label);
    }
    private void assertOverlayInScreenshot(String label){
        Rect r=new Rect();waitNode(label).getBoundsInScreen(r);
        until(()->{
            Bitmap screenshot=getUiAutomation().takeScreenshot();if(screenshot==null)return false;
            if(screenshot.getConfig()==Bitmap.Config.HARDWARE){Bitmap hardware=screenshot;screenshot=hardware.copy(Bitmap.Config.ARGB_8888,false);hardware.recycle();}
            try{
                for(int y=Math.max(0,r.top);y<Math.min(screenshot.getHeight(),r.bottom);y+=3)
                    for(int x=Math.max(0,r.left);x<Math.min(screenshot.getWidth(),r.right);x+=3){
                        int color=screenshot.getPixel(x,y);
                        if(Math.abs(android.graphics.Color.red(color)-18)<5 && Math.abs(android.graphics.Color.green(color)-54)<5 && Math.abs(android.graphics.Color.blue(color)-50)<5)return true;
                    }
                return false;
            }finally{screenshot.recycle();}
        },8000,"Screenshot did not include the floating panel");
    }
    private String miniStatus(){
        final String[] label={""};
        runOnMainSync(()->{
            try{
                java.lang.reflect.Field active=RecallOverlay.class.getDeclaredField("active");active.setAccessible(true);
                Object controller=active.get(null);if(controller==null)return;
                java.lang.reflect.Field status=controller.getClass().getDeclaredField("status");status.setAccessible(true);
                android.widget.TextView view=(android.widget.TextView)status.get(controller);
                if(view!=null && view.isShown())label[0]=view.getText().toString();
            }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        });
        return label[0];
    }
    private static final java.util.regex.Pattern MINI_CLOCK=java.util.regex.Pattern.compile(
            "(?:(\\d+):)?(\\d+):([0-5]\\d) · (?:↶(\\d{1,2})s|(Saved))");

    private void waitForMiniClock(boolean receipt,long timeout){
        int[] first={-1,-1};
        until(()->{
            java.util.regex.Matcher reading=MINI_CLOCK.matcher(miniStatus());
            if(!reading.matches() || (reading.group(5)!=null)!=receipt)return false;
            int hour=reading.group(1)==null?0:Integer.parseInt(reading.group(1));
            int elapsed=hour*3600+60*Integer.parseInt(reading.group(2))+Integer.parseInt(reading.group(3));
            int rewind=receipt?-1:Integer.parseInt(reading.group(4));
            LiveCapture.Snapshot actual=LiveCapture.snapshot();
            if(!actual.receiving || Math.abs(actual.sessionSeconds-elapsed)>2)return false;
            if(!receipt && (rewind<1 || rewind>60))return false;
            if(first[0]<0){first[0]=elapsed;first[1]=rewind;return false;}
            // An idle label swap cannot satisfy this. A growing rewind must advance until its 60 s cap.
            return elapsed>=first[0]+2 && (receipt || rewind>first[1] || (rewind==60 && first[1]==60));
        },timeout,receipt?"Ultra mini must keep the clock advancing during the confirmed save receipt":
                "Ultra mini must show the advancing session clock and growing or full rewind buffer");
    }
    private String takeStatus(Activity sheet){
        final String[] label={"unknown"};
        runOnMainSync(()->{
            try{
                java.lang.reflect.Field status=VoiceTakeActivity.class.getDeclaredField("status");
                status.setAccessible(true);TextView view=(TextView)status.get(sheet);
                if(view!=null)label[0]=view.getText().toString();
            }catch(ReflectiveOperationException ignored){}
        });
        return label[0];
    }
    private void overlay()throws Exception{
        String app=target.getPackageName(), handle="Expand panel";
        Activity a=launch();
        try{
            readWindows();
            RecallOverlay.reset(target);
            deviceShell("appops set "+app+" SYSTEM_ALERT_WINDOW allow");
            until(()->Settings.canDrawOverlays(target),10000,"Emulator failed to grant overlay access");
            require(node(handle)==null,"Granting Android permission started an overlay without opt-in");
            click(waitText(a,"Floating controls",10000));
            tapNode("Show controls during listening");tapNode("Back to Total Recall");
            startListening(a);
            Rect before=new Rect();waitNode("Open the complete transcript across saved sessions").getBoundsInScreen(before);
            int dx=Math.round(45*target.getResources().getDisplayMetrics().density);
            int dy=Math.round(65*target.getResources().getDisplayMetrics().density);
            deviceShell("input swipe "+before.centerX()+" "+before.centerY()+" "+(before.centerX()+dx)+" "
                    +(before.centerY()+dy)+" 600");
            until(()->{
                AccessibilityNodeInfo n=node("Open the complete transcript across saved sessions");if(n==null)return false;
                Rect after=new Rect();n.getBoundsInScreen(after);
                return Math.abs(after.centerY()-before.centerY())>=dy/2;
            },8000,"Floating control did not move on a physical pointer drag");
            assertOverlayInScreenshot(handle);tapNode(handle);assertOverlayInScreenshot("Shrink panel");waitNode("Pause recording and release the microphone");waitNode("Shrink panel");
            ActivityMonitor takeLaunch=addMonitor(VoiceTakeActivity.class.getName(),null,false);
            Activity takeSheet;
            try{
                until(()->{
                    AccessibilityNodeInfo button=node("Record a new voice take and edit what you actually said");
                    return button!=null && button.isVisibleToUser() && button.isEnabled() && button.isClickable() &&
                        LiveCapture.snapshot().receiving && button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                },10000,"Overlay take button did not accept a tap while receiving microphone input");
                takeSheet=waitForMonitorWithTimeout(takeLaunch,10000);
                require(takeSheet!=null,"Accepted overlay tap did not launch the voice take activity");
            }finally{removeMonitor(takeLaunch);}
            try{waitText(takeSheet,"Finish & edit",20000);}
            catch(AssertionError e){throw new AssertionError("Opened the take activity but recording did not start. Microphone: "+
                RecallService.status(target)+", receiving="+LiveCapture.snapshot().receiving+", activity="+takeSheet.getClass().getSimpleName()+
                ", take status="+takeStatus(takeSheet),e);}
            waitNode("Finish & edit");
            SystemClock.sleep(500);tapNode("Finish & edit");waitNode("Listen to original");waitNode("Save corrected words");
            waitNode("Share original audio");waitNode("Copy these words");
            getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            waitNode("Shrink panel");
            tapNode("More overlay actions");tapNode("Use the smallest panel with live status and words");
            assertOverlayInScreenshot("Open medium panel");
            waitForMiniClock(false,20000);
            int beforeHold=LiveCapture.snapshot().savedCount;
            AccessibilityNodeInfo mini=waitNode("Open the complete transcript across saved sessions");
            require(mini.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK),"Small overlay rejected hold-to-save");
            until(()->LiveCapture.snapshot().savedCount>beforeHold,16000,"Holding the mini readout did not save audio");
            waitForMiniClock(true,6500);
            tapNode("Stop listening and close Total Recall");waitNode("End & close");tapNode("Go back");
            waitNode("Open medium panel");require(RecallService.isListening(),"Canceling from ultra mini stopped capture");
            tapNode("Open medium panel");waitNode(handle);tapNode(handle);
            tapNode("More overlay actions");tapNode("Hide live captions; transcription continues");waitNode("Show live captions as speech is recognized");
            tapNode("Show live captions as speech is recognized");waitNode("Hide live captions; transcription continues");
            deviceShell("input keyevent KEYCODE_HOME");tapNode("Open the main listening screen");
            waitNode("More listening and archive options");require(RecallService.isListening(),"Opening app stopped capture");
            Method visible=method(RecallOverlay.class,"visible");
            deviceShell("input keyevent KEYCODE_SLEEP");
            until(()->{
                final boolean[] attached={true};
                runOnMainSync(()->{
                    try{attached[0]=(boolean)visible.invoke(null);}catch(Exception e){throw new RuntimeException(e);}
                });
                return !attached[0];
            },10000,"Overlay window remained attached after the display locked");
            deviceShell("input keyevent KEYCODE_WAKEUP");deviceShell("wm dismiss-keyguard");
            waitNode("Shrink panel");tapNode("Shrink panel");waitNode(handle);tapNode(handle);tapNode("More overlay actions");tapNode("Dismiss the overlay and keep the current microphone state");
            until(()->node(handle)==null,7000,"Dismissed overlay still displayed");
            require(RecallService.isListening(),"Hiding the overlay stopped listening");
            RecallOverlay.sessionStarted(target);waitNode("Shrink panel");tapNode("More overlay actions");
            tapNode("Stop listening and close Total Recall");tapNode("Go back");
            require(RecallService.isListening(),"Canceling End stopped the microphone");
            tapNode("More overlay actions");
            tapNode("Stop listening and close Total Recall");tapNode("End & close");
            until(()->!RecallService.isListening() && node("Shrink panel")==null && node(handle)==null && RecallService.pausedAt(target)==0,12000,"End did not stop the recording and close the panel");
            passed("Explicit app opt-in plus Android special access displayed real overlay controls. A pointer swipe moved the window, expansion exposed Pause, locking hid the window, and Hide left the microphone running.");
            stopListening();require(liveCleared(),"Live data survived explicit pause");
        }finally{
            deviceShell("input keyevent KEYCODE_WAKEUP");deviceShell("wm dismiss-keyguard");
            stopListening();RecallOverlay.reset(target);
            deviceShell("appops set "+app+" SYSTEM_ALERT_WINDOW default");
            runOnMainSync(a::finish);waitForIdleSync();
        }
    }
    private int countGaps(Store store,String value){
        int count=0;for(String s:store.gaps())if(s.contains(value))count++;return count;
    }
    private void compete()throws Exception {
        deviceShell("am start -W -n org.totalrecall.contender/.VoiceActivity");
        waitNode("Competing microphone active");
        until(()->RecallService.isListening() && RecallService.status(target).startsWith("Waiting for microphone"),
                30000,"Android did not report microphone contention from the separate foreground voice app: "
                        +RecallService.status(target));
        LiveCapture.Snapshot live=LiveCapture.snapshot();
        require(live.partial.isEmpty() && live.finals.isEmpty() && live.level==0,
                "Unavailable microphone continued to report captions or samples");
    }
    private void releaseOther()throws Exception{deviceShell("am force-stop org.totalrecall.contender");}
    private void contention()throws Exception{
        Activity a=launch();
        ArchiveStore.reset(target);ArchiveStore.setEnabled(target,true);
        try(Store store=new Store(target);ArchiveStore archive=new ArchiveStore(target)){
            readWindows();
            int opens=countGaps(store,"Microphone gap began"), ends=countGaps(store,"Microphone gap ended");
            startListening(a);SystemClock.sleep(2300);
            int rows=store.search("").size();
            compete();
            until(()->!archive.pending(4).isEmpty(),10000,"The displaced microphone did not seal its AAC tail");
            List<String> archiveGaps=archive.latestGaps(4);
            require(archiveGaps.size()==1 && archiveGaps.get(0).contains(" — open"),"Archive failed to open a real microphone gap");
            int sealedAtLoss=archive.segments(8).size();
            target.startService(new Intent(target,RecallService.class).setAction(RecallService.KEEP));
            SystemClock.sleep(3000);
            require(store.search("").size()==rows,"Saved unavailable audio during interruption");
            require(archive.segments(8).size()==sealedAtLoss,"Archive retained unavailable microphone audio during the gap");
            long releaseTime=System.currentTimeMillis();
            releaseOther();
            until(()->RecallService.isListening() && RecallService.status(target).startsWith("Listening"),
                    30000,"Releasing the competing app failed to resume the current session");
            SystemClock.sleep(4500);
            require(store.search("").size()==rows,"An action requested during the gap replayed after recovery");
            require(countGaps(store,"Microphone gap began")==opens+1 && countGaps(store,"Microphone gap ended")==ends+1,
                    "One interruption should have one start and one end in coverage history");
            target.startService(new Intent(target,RecallService.class).setAction(RecallService.KEEP));
            until(()->store.search("").size()>rows,30000,"Post-recovery audio could not be saved");
            Store.Moment newest=store.search("").get(0);
            require(newest.startMs>=releaseTime-650 && newest.kind.contains(SelectiveEngine.MANUAL)
                    && file(newest.audio).length>44,"Post-recovery capture includes audio from before release");
            store.delete(newest.id);
            require(archive.latestGaps(4).size()==1 && !archive.latestGaps(1).get(0).contains(" — open"),
                    "The recovered microphone did not close its continuous-archive gap");
            passed("Opted-in continuous AAC sealed on real foreground microphone displacement, retained no unavailable interval, and closed the corresponding gap when capture resumed.");
            passed("A separate foreground privacy-sensitive Android recorder displaced Recall. Capture resumed without restarting, the catalog recorded one bounded gap, and retained audio began after the other recorder released the microphone.");
            compete();stopListening();releaseOther();
            SystemClock.sleep(6000);
            require(!RecallService.isListening() && liveCleared(),"User pause while displaced was undone on mic release");
            passed("Explicit pause while another app owned the mic prevented automatic resumption and cleared live data.");
        }finally{releaseOther();stopListening();ArchiveStore.setEnabled(target,false);runOnMainSync(a::finish);waitForIdleSync();}
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
    private AccessibilityNodeInfo shadeNode(String kind,boolean log){
        for(AccessibilityWindowInfo window:getUiAutomation().getWindows()){
            AccessibilityNodeInfo root=window.getRoot();
            if(root==null)continue;
            if(log)Log.i("RecallSmoke","Shade window "+window.getType()+" "+root.getPackageName()+" "+window.getTitle());
            if(!"com.android.systemui".contentEquals(String.valueOf(root.getPackageName())))continue;
            java.util.ArrayDeque<AccessibilityNodeInfo> todo=new java.util.ArrayDeque<>();todo.add(root);
            while(!todo.isEmpty()){
                AccessibilityNodeInfo n=todo.removeFirst();
                String text=String.valueOf(n.getText()),desc=String.valueOf(n.getContentDescription()),id=String.valueOf(n.getViewIdResourceName());
                if(n.isVisibleToUser()){
                    if(log && (!"null".equals(text)||!"null".equals(desc)))Log.i("RecallSmoke","Shade item "+text+" | "+desc+" | "+id);
                    if(kind.equals("save") && (text.equalsIgnoreCase("Save last min")||desc.equalsIgnoreCase("Save last min")))return n;
                    if(kind.equals("recall") && (text.equalsIgnoreCase("Total Recall")||text.equalsIgnoreCase("Listening")
                        ||desc.startsWith("Total Recall")||text.contains(" words · ")))return n;
                }
                for(int k=0;k<n.getChildCount();k++){AccessibilityNodeInfo child=n.getChild(k);if(child!=null)todo.add(child);}
            }
        }
        return null;
    }
    private AccessibilityNodeInfo shadeButton(){return shadeNode("save",false);}
    private void expandRecallRow(AccessibilityNodeInfo item)throws Exception{
        for(AccessibilityNodeInfo row=item;row!=null;row=row.getParent()){
            for(String id:new String[]{"android:id/expand_button","com.android.systemui:id/expand_button"}){
                for(AccessibilityNodeInfo button:row.findAccessibilityNodeInfosByViewId(id)){
                    if(button.isVisibleToUser()){touch(button);return;}
                }
            }
            if(row.performAction(AccessibilityNodeInfo.ACTION_EXPAND))return;
            if(String.valueOf(row.getClassName()).contains("NotificationRow"))break;
        }
        Rect r=new Rect();item.getBoundsInScreen(r);
        deviceShell("input swipe "+r.centerX()+" "+r.centerY()+" "+r.centerX()+" "+(r.centerY()+200)+" 370");
    }
    private void touch(AccessibilityNodeInfo item)throws Exception{
        Rect bounds=new Rect();item.getBoundsInScreen(bounds);
        require(!bounds.isEmpty(),"Empty System UI control");
        deviceShell("input tap "+bounds.centerX()+" "+bounds.centerY());
    }
    private void longSession()throws Exception{
        try(ArchiveStore db=new ArchiveStore(target)){
            Class<?> type=Class.forName("org.totalrecall.listening.LongRecording");
            java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor(Context.class,ArchiveStore.class);ctor.setAccessible(true);
            Object recorder=ctor.newInstance(target,db);
            Method begin=method(type,"begin"), accept=method(type,"accept",short[].class,int.class),mark=method(type,"mark",String.class,long.class),finish=method(type,"finish",String.class);
            java.lang.reflect.Field active=type.getDeclaredField("active");active.setAccessible(true);
            begin.invoke(recorder);String id=(String)active.get(null);require(!id.isEmpty(),"Long recorder did not start");
            short[] tone=new short[16000],quiet=new short[16000];
            for(int i=0;i<tone.length;i++)tone[i]=(short)(4000*Math.sin(2*Math.PI*220*i/16000));
            for(int sec=0;sec<3601;sec++){
                accept.invoke(recorder,(sec/300)%2==0?tone:quiet,16000);
                if(sec==1)require((boolean)mark.invoke(recorder,SelectiveEngine.FORGE,8000L),"Could not mark opening word");
                if(sec==47)require((boolean)mark.invoke(recorder,SelectiveEngine.POETRY,8000L),"Could not mark recent line");
                if(sec==137 || sec==3599)require((boolean)mark.invoke(recorder,"",0L),"Could not mark current point");
            }
            require((boolean)finish.invoke(recorder,"Finished"),"Hour-long recording was not finalized");
            File audio=(File)method(type,"audio",Context.class,String.class,boolean.class).invoke(null,target,id,false);
            try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT ms,bytes FROM long_sessions WHERE id=?",new String[]{id})){
                require(c.moveToFirst() && c.getLong(0)==3601000 && c.getLong(1)==audio.length(),"Hour duration or byte count wrong");
            }
            try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT ms,label FROM long_marks WHERE session=? ORDER BY ms",new String[]{id})){
                require(c.moveToFirst() && c.getLong(0)==0 && SelectiveEngine.FORGE.equals(c.getString(1)),"Opening word crossed the session boundary");
                require(c.moveToNext() && c.getLong(0)==40000 && SelectiveEngine.POETRY.equals(c.getString(1)),"Line marker lost its lead-in");
                require(c.moveToNext() && c.getLong(0)==138000 && c.moveToNext() && c.getLong(0)==3600000 && !c.moveToNext(),"Marker position wrong");
            }
            long offset=(Long)method(type,"offset",File.class,long.class).invoke(null,audio,3600000L);
            require(offset>0 && offset<audio.length(),"Late marker outside recording");
            try(java.io.RandomAccessFile stream=new java.io.RandomAccessFile(audio,"r")){stream.seek(offset);require(stream.readUnsignedShort()==65521,"Marker did not land on ADTS frame");}
            passed("Encoded 3,601 seconds of synthetic 16 kHz audio including five-minute quiet passages into one "+audio.length()+"-byte AAC file; four markers, typed lead-in and the one-hour frame boundary verified.");
            int budget=ArchiveStore.budgetMiB(target);
            try{ArchiveStore.setBudgetMiB(target,1);begin.invoke(recorder);require(((String)active.get(null)).isEmpty(),"Storage limit allowed a new session");}
            finally{ArchiveStore.setBudgetMiB(target,budget);}
        }
        Activity a=launch();click(waitText(a,"Long sessions",10000));readWindows();tapNode("Start long session");
        android.app.PendingIntent marker=waitForButton("Mark point"),end=waitForButton("Finish session");
        marker.send();SystemClock.sleep(900);end.send();
        until(()->!RecallService.isListening(),20000,"Finishing from idle did not release microphone");
        waitNode("Start long session");passed("Direct start from the recorder, lock-screen mark and finish, and microphone release verified.");
        getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);runOnMainSync(a::finish);
    }
    private void lockedShade()throws Exception{
        readWindows();Activity activity=launch();
        android.app.KeyguardManager keyguard=target.getSystemService(android.app.KeyguardManager.class);
        android.os.PowerManager power=target.getSystemService(android.os.PowerManager.class);
        try{
            startListening(activity);waitForButton("Save last min");
            deviceShell("input keyevent KEYCODE_SLEEP");
            until(()->!power.isInteractive(),6000,"Screen did not turn off");
            SystemClock.sleep(1500);deviceShell("input keyevent KEYCODE_WAKEUP");
            until(keyguard::isKeyguardLocked,8000,"Swipe lock did not engage on disposable emulator");
            SystemClock.sleep(950);
            android.util.DisplayMetrics metrics=target.getResources().getDisplayMetrics();
            int x=metrics.widthPixels/3,y=metrics.heightPixels*3/4;
            for(int attempt=0;attempt<4 && shadeButton()==null;attempt++){
                shadeNode("dump",true);
                AccessibilityNodeInfo item=shadeNode("recall",false);
                if(item!=null)expandRecallRow(item);
                else if(attempt==2)deviceShell("cmd statusbar expand-notifications");
                else deviceShell("input swipe "+x+" 1 "+x+" "+y+" 480");
                SystemClock.sleep(1050);
            }
            if(shadeButton()==null){shadeNode("dump",true);throw new AssertionError("Save last min was not visible in System UI");}
            require(keyguard.isKeyguardLocked(),"Shade dismissed keyguard before save");
            int before=LiveCapture.snapshot().savedCount;
            touch(shadeButton());
            until(()->LiveCapture.snapshot().savedCount>before,16000,"Touching the rendered lock-screen button did not save");
            require(keyguard.isKeyguardLocked(),"Saving from the shade dismissed the swipe lock");
            passed("Tapped Android System UI's rendered Save last min action while the swipe keyguard was active and verified a new on-device save.");
        }finally{
            deviceShell("cmd statusbar collapse");deviceShell("wm dismiss-keyguard");stopListening();
            runOnMainSync(activity::finish);
        }
    }
    private android.app.PendingIntent noticeButton(String title){
        android.app.NotificationManager manager=target.getSystemService(android.app.NotificationManager.class);
        for(android.service.notification.StatusBarNotification row:manager.getActiveNotifications()){
            android.app.Notification n=row.getNotification();
            if(n.actions!=null)for(android.app.Notification.Action action:n.actions)
                if(title.contentEquals(action.title))return action.actionIntent;
        }
        return null;
    }
    private android.app.PendingIntent waitForButton(String title){
        final android.app.PendingIntent[] found={null};
        until(()->(found[0]=noticeButton(title))!=null,16000,"Missing notification action "+title);
        return found[0];
    }
    private void microphone()throws Exception{
        Activity a=launch();
        android.os.PowerManager power=(android.os.PowerManager) target.getSystemService(Context.POWER_SERVICE);
        java.util.Set<String> previous=new java.util.HashSet<>();
        try(Store store=new Store(target)){
            for(Store.Moment row:store.search(""))previous.add(row.uid);
            render(a,"recall-v11-paused");
            View start=waitText(a,RecallService.pausedAt(target)>0?"Resume listening":"Start listening",8000);runOnMainSync(start::performClick);
            long end=SystemClock.elapsedRealtime()+120000;
            while(SystemClock.elapsedRealtime()<end&&!RecallService.status(target).startsWith("Listening"))SystemClock.sleep(250);
            require(RecallService.isListening()&&RecallService.status(target).startsWith("Listening"),"Foreground capture not active: "+RecallService.status(target));
            require(SelectiveEngine.pronunciationCount()==124903,
                    "Live service did not load the bundled 124,903-word pronunciation dictionary");
            passed("Foreground microphone service loaded all 124,903 bundled pronunciations before listening.");
            waitText(a,"Pause listening",10000);
            render(a,"recall-v11-listening");
            deviceShell("input keyevent KEYCODE_SLEEP");
            end=SystemClock.elapsedRealtime()+7000;
            while(power.isInteractive()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
            require(!power.isInteractive(),"Emulator display did not turn off");
            SystemClock.sleep(4000);
            require(!power.isInteractive()&&RecallService.isListening(),"Listening interrupted while screen was off");
            android.app.PendingIntent begin=waitForButton("Start take");begin.send();
            android.app.PendingIntent finish=waitForButton("Finish take");SystemClock.sleep(600);finish.send();
            until(()->store.search("Voice take").stream().anyMatch(row->!previous.contains(row.uid)),16000,"Lock-screen take failed to save");
            waitForButton("Start take");begin.send();SystemClock.sleep(900);
            require(noticeButton("Finish take")==null,"Expired Start take action restarted recording");
            require(!power.isInteractive(),"A lock-screen take woke the screen");
            for(Store.Moment row:store.search("Voice take"))if(!previous.contains(row.uid))store.delete(row.id);
            waitForButton("Save last min").send();
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
            SharedPreferences controls=target.getSharedPreferences("recall-speech",0);
            controls.edit().putBoolean("noticeMissed",true).commit();
            try{
                android.app.PendingIntent missed=waitForButton("Mark missed");
                require(noticeButton("Start take")==null,"Take shortcut remained after switching to Mark missed");
                missed.send();
                until(()->store.search("").stream().anyMatch(row->!previous.contains(row.uid) && row.kind.contains(SelectiveEngine.MISSED)),16000,"Screen-off mark missed did not store audio");
                for(Store.Moment row:store.search(""))if(!previous.contains(row.uid)&&row.kind.contains(SelectiveEngine.MISSED))store.delete(row.id);
                require(!power.isInteractive(),"Mark missed woke the display");
            }finally{controls.edit().remove("noticeMissed").commit();}
            android.app.PendingIntent priorPause=waitForButton("Pause");priorPause.send();
            android.app.PendingIntent resume=waitForButton("Resume");
            require(!RecallService.isListening() && !power.isInteractive(),"Pause did not release the mic while asleep");
            waitForButton("End & close");
            resume.send();waitForButton("Save last min");
            require(RecallService.isListening() && !power.isInteractive(),"Resume did not recover an active microphone with screen off");
            priorPause.send();SystemClock.sleep(500);require(RecallService.isListening(),"Old pause affected the new session");
            passed("Posted notification actions started and finished a voice take, saved recent PCM, paused and resumed the microphone with the display off. Stale take and pause actions were rejected.");
            deviceShell("input keyevent KEYCODE_WAKEUP");deviceShell("wm dismiss-keyguard");
            View pause=waitText(a,"Pause listening",10000);runOnMainSync(pause::performClick);
            waitText(a,"Resume listening",20000);
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
