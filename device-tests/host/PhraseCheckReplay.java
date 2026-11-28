package org.totalrecall.listening;

import android.Manifest;
import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.nio.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowAudioRecord;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Offline native replay. Use rms-04-clean.pcm from archive-dictation-holdout.py generate. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class PhraseCheckReplay {
    private static final String SPOKEN="meet me at six fifteen and bring exactly two copies";
    private static final String WRITTEN="Meet me at six fifteen and bring exactly three copies.";
    private static TextView find(View root,String text){
        if(root instanceof TextView && text.contentEquals(((TextView)root).getText()))return (TextView)root;
        if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){
            TextView found=find(g.getChildAt(i),text);if(found!=null)return found;}}
        return null;
    }
    private static TextView waitFor(View root,String text) throws Exception {
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
        while(System.nanoTime()<limit){
            shadowOf(Looper.getMainLooper()).idleFor(150,TimeUnit.MILLISECONDS);
            TextView control=find(root,text);if(control!=null)return control;
            if(find(root,"Read this line")!=null)throw new AssertionError("Returned to form before result: "+allText(root));
            Thread.sleep(25);
        }
        throw new AssertionError("Control never arrived: "+text+"; screen: "+allText(root));
    }
    private static String allText(View v){
        if(v instanceof TextView)return ((TextView)v).getText().toString()+" | ";
        StringBuilder s=new StringBuilder();if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++)s.append(allText(g.getChildAt(i)));}return s.toString();
    }
    @Test public void samePcmThroughPrivateMicAndRunningRecorderKeepsSpokenNumberOverWrittenPrompt() throws Exception {
        Application app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        ReflectionHelpers.setStaticField(org.continuity.recall.ListenPolicy.class,"paused",false);
        app.getSharedPreferences("recall-speech",0).edit().putBoolean("voices",false).commit();
        Path model=Paths.get(System.getProperty("recall.model")).toAbsolutePath();
        Files.createSymbolicLink(app.getNoBackupFilesDir().toPath().resolve(model.getFileName()),model);
        java.util.List<SpeechContext.Rule> spelling=new java.util.ArrayList<>();
        for(String line:Files.readAllLines(Paths.get(System.getProperty("recall.spellings")))){
            String[] pair=line.split("\t",2);spelling.add(new SpeechContext.Rule(pair[0],pair[1]));
        }
        ReflectionHelpers.setStaticField(SpeechContext.class,"generic",spelling);
        byte[] bytes=Files.readAllBytes(Paths.get(System.getProperty("recall.fixture")));
        ShortBuffer source=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        short[] padded=new short[PhraseCheckInput.RATE*PhraseCheckInput.SECONDS];source.get(padded,0,source.remaining());
        ShadowAudioRecord.setSource(new ShadowAudioRecord.AudioRecordSource(){
            int at;
            @Override public int readInShortArray(short[] buffer,int offset,int count,boolean blocking){
                int amount=Math.min(count,padded.length-at);System.arraycopy(padded,at,buffer,offset,amount);at+=amount;return amount;}
        });
        ActivityController<PhraseCheckActivity> ctrl=Robolectric.buildActivity(PhraseCheckActivity.class).setup();
        try{
            View root=ctrl.get().getWindow().getDecorView();
            ((EditText)find(root,"The silver river keeps the moon awake")).setText(WRITTEN);
            find(root,"Read this line").performClick();
            waitFor(root,"I read this line · compare");assertNull(find(root,"Your dictation check"));
            assertNotNull(find(root,SPOKEN));find(root,"I read this line · compare").performClick();
            assertNotNull(find(root,"9 of 10 words matched · 1 word edit"));
            assertNotNull(find(root,"Changed “three” to “two”"));
            find(root,"Try another delivery or lift").performClick();
            ReflectionHelpers.setStaticField(RecallService.class,"running",true);
            LiveCapture.start();LiveCapture.samples(new short[]{32,15},2);
            find(root,"Read this line").performClick();
            ShadowAlertDialog.getLatestAlertDialog().getButton(-1).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            PhraseCheckInput.drained();PhraseCheckInput.frame(padded,padded.length,false);
            waitFor(root,"I read this line · compare").performClick();
            assertNotNull(find(root,"Previous take · Everyday pace · Standard lift · 1 word edit\nBoth takes had the same number of edits."));
            assertNotNull(find(root,"Changed “three” to “two”"));
            assertTrue(new VoiceProfiles(app).list().isEmpty());
            assertNull(shadowOf(app).getNextStartedService());
        }finally{ctrl.pause().stop().destroy();ShadowAudioRecord.clearSource();LiveCapture.stop("Paused");ReflectionHelpers.setStaticField(RecallService.class,"running",false);}
    }
}
