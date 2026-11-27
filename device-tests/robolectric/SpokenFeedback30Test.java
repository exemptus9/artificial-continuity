package org.totalrecall.listening;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,35},manifest=Config.NONE)
public class SpokenFeedback30Test {
    private TextView find(View v,String label){
        if(v instanceof TextView && label.contentEquals(((TextView)v).getText()))return (TextView)v;
        if(v instanceof ViewGroup){
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++){TextView child=find(g.getChildAt(i),label);if(child!=null)return child;}
        }
        return null;
    }
    @Test public void standardMutedGuidedAndAutomaticPolicies(){
        SharedPreferences p=RuntimeEnvironment.getApplication().getSharedPreferences("recall-speech",0);
        p.edit().clear().commit();
        assertFalse(CaptureCues.enabled(p,"spokenReceipts"));assertFalse(CaptureCues.enabled(p,"spokenRecovery"));
        assertTrue(CaptureCues.enabled(p,"spokenAnswers"));
        p.edit().putBoolean("chime",false).commit();assertFalse(CaptureCues.enabled(p,"spokenAnswers"));
        p.edit().putBoolean("spokenReceipts",true).commit();assertTrue(CaptureCues.enabled(p,"spokenRecovery"));
        p.edit().putBoolean("spokenRecovery",false).putBoolean("spokenAnswers",false).commit();
        assertFalse(CaptureCues.enabled(p,"spokenRecovery"));assertFalse(CaptureCues.enabled(p,"spokenAnswers"));
        assertEquals("spokenReady",CaptureCues.voiceSetting(CaptureCues.Cue.WAKE,false));
        assertEquals("spokenRetry",CaptureCues.voiceSetting(CaptureCues.Cue.UNCLEAR,false));
        assertEquals("spokenAuto",CaptureCues.voiceSetting(CaptureCues.Cue.WORD,true));
        assertEquals("spokenRecovery",CaptureCues.voiceSetting(CaptureCues.Cue.SAVE_FAILED,false));
        assertEquals("spokenAnswers",CaptureCues.voiceSetting(CaptureCues.Cue.CHOICE,false));
        assertTrue(CaptureCues.confirmationTone(CaptureCues.Cue.WORD));
        assertFalse(CaptureCues.confirmationTone(CaptureCues.Cue.NO_AUDIO));
        assertFalse(CaptureCues.confirmationTone(CaptureCues.Cue.SAVE_FAILED));
        assertFalse(CaptureCues.confirmationTone(CaptureCues.Cue.SESSION_CHECK));
        assertTrue(CaptureCues.minimumInterval(CaptureCues.Cue.MIC_WAIT,false)>=20_000);
        assertTrue(CaptureCues.minimumInterval(CaptureCues.Cue.WORD,true)>=8000);
        assertEquals(0,CaptureCues.minimumInterval(CaptureCues.Cue.WORD,false));
    }
    @Test public void handsFreeSetupPreservesUnrelatedChoicesAndDoesNotStartMic(){
        Application app=RuntimeEnvironment.getApplication();SharedPreferences p=app.getSharedPreferences("recall-speech",0);
        p.edit().putBoolean("chime",false).putBoolean("spokenAuto",false).putInt("cueLevel",0).commit();
        boolean journal=JournalStore.enabled(app),archive=ArchiveStore.enabled(app),sync=ArchiveSync.auto(app);
        ActivityController<CaptureReviewActivity> c=Robolectric.buildActivity(CaptureReviewActivity.class,new Intent(app,CaptureReviewActivity.class).putExtra("noticeSettings",true)).setup();
        try{
            android.widget.Switch noise=(android.widget.Switch)find(c.get().getWindow().getDecorView(),"Voice commands in heavy noise");
            assertFalse(noise.isChecked());
            noise.performClick();assertTrue(p.getBoolean("commandNoise",false));
            AlertDialog settings=ShadowAlertDialog.getLatestAlertDialog();
            find(settings.getWindow().getDecorView(),"Hands-free voice setup").performClick();
            AlertDialog preset=ShadowAlertDialog.getLatestAlertDialog();
            String details=((TextView)preset.findViewById(android.R.id.message)).getText().toString();
            assertTrue(details.contains("missed commands"));assertTrue(details.contains("microphone recovery"));
            preset.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            for(String k:new String[]{"spokenReady","spokenReceipts","spokenRetry","spokenRecovery","spokenAnswers"})assertTrue(k,p.getBoolean(k,false));
            assertTrue(p.getBoolean("commandNoise",false));
            assertFalse(p.getBoolean("chime",true));assertFalse(p.getBoolean("spokenAuto",true));assertEquals(0,p.getInt("cueLevel",2));
            assertEquals(journal,JournalStore.enabled(app));assertEquals(archive,ArchiveStore.enabled(app));assertEquals(sync,ArchiveSync.auto(app));assertFalse(RecallService.isListening());
        }finally{c.pause().stop().destroy();}
    }
    @Test public void choiceMarkerBindsOnlyToTheRecordingThatWasHeard(){
        Application app=RuntimeEnvironment.getApplication();
        String original=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
        try(ArchiveStore db=new ArchiveStore(app);LongRecording rec=new LongRecording(app,db)){
            SQLiteDatabase sql=db.getWritableDatabase();
            for(String id:new String[]{original,other}){
                sql.execSQL("INSERT INTO long_sessions(id,title,start,ms,bytes,status) VALUES(?,?,0,24000,100,'Finished')",new Object[]{id,"Fixture"});
            }
            assertTrue(rec.markAt(original,9000,"Poem line"));
            assertTrue(rec.markAt(original,90000,"Forge word"));
            assertFalse(rec.markAt(UUID.randomUUID().toString(),100,"Missing"));
            try(Cursor rows=sql.rawQuery("SELECT session,ms FROM long_marks ORDER BY rowid",null)){
                assertTrue(rows.moveToFirst());assertEquals(original,rows.getString(0));assertEquals(9000,rows.getLong(1));
                assertTrue(rows.moveToNext());assertEquals(original,rows.getString(0));assertEquals(24000,rows.getLong(1));
                assertFalse(rows.moveToNext());
            }
        }
    }
    @Test public void overlappingPlayersAndBufferedFrameTimestamps(){
        long before=System.nanoTime()-1;
        long a=CuePlaybackGate.begin(2000),during=System.nanoTime();
        long b=CuePlaybackGate.begin(2000);
        try{
            assertFalse(CuePlaybackGate.overlaps(before-100,before));
            assertTrue(CuePlaybackGate.overlaps(during,during+2));
            CuePlaybackGate.end(a);assertTrue(CuePlaybackGate.active());
            CuePlaybackGate.cancel(b);
            assertTrue("allow acoustic tail",CuePlaybackGate.active());
            assertFalse(CuePlaybackGate.overlaps(System.nanoTime()+900_000_000L,System.nanoTime()+910_000_000L));
            assertTrue("old PCM delivered from AudioRecord buffer",CuePlaybackGate.overlaps(during,during+2));
            assertTrue("guard delayed PCM on hardware without timestamps",
                RecallService.promptWithoutTimestamp(during,1600,during+1_000_000_000L));
            assertFalse("fresh user response after the app finishes",
                RecallService.promptWithoutTimestamp(during+1_100_000_000L,1600,during+1_103_000_000L));
        }finally{CuePlaybackGate.cancel(a);CuePlaybackGate.cancel(b);}
    }
    @Test public void currentStatusPrioritizesActualMicOverOpenSession(){
        LiveCapture.start();LongRecording.active=UUID.randomUUID().toString();
        try{
            LiveCapture.samples(new short[1600],1600);
            assertEquals(CaptureCues.Cue.STATUS_LONG,RecallService.statusCue(LiveCapture.snapshot()));
            LiveCapture.waitFor("Mic busy");
            assertEquals(CaptureCues.Cue.MIC_WAIT,RecallService.statusCue(LiveCapture.snapshot()));
        }finally{LongRecording.active="";LiveCapture.stop("Off");}
    }
}
