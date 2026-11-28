package org.totalrecall.listening;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,35},manifest=Config.NONE)
public class PhraseCheck31Test {
    Application app;
    @Before public void prepare(){
        app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("recall-speech",0).edit().clear().commit();
        VoiceProfiles.reset(app);
        ReflectionHelpers.setStaticField(org.continuity.recall.ListenPolicy.class,"paused",false);
        ReflectionHelpers.setStaticField(RecallService.class,"running",false);LiveCapture.stop("Paused");
    }
    @After public void end(){ReflectionHelpers.setStaticField(RecallService.class,"running",false);LiveCapture.stop("Paused");}
    static TextView find(View v,String text){
        if(v instanceof TextView && text.contentEquals(((TextView)v).getText()))return (TextView)v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++){TextView t=find(g.getChildAt(i),text);if(t!=null)return t;}}
        return null;
    }
    @Test public void scoresRepeatedWordsUnicodeInsertionsAndSilence(){
        PhraseCheckScore s=PhraseCheckScore.compare("Don’t STØP, moon, moon—yet.","don't støp moon now");
        assertEquals(5,s.words);assertEquals(3,s.matches);assertEquals(1,s.changed);assertEquals(1,s.missed);
        assertEquals(2,s.edits());assertEquals(2,s.differences.size());
        assertEquals(2,PhraseCheckScore.compare("bright moon","the bright silver moon").extra);
        assertEquals(3,PhraseCheckScore.compare("one two three","").missed);
        assertTrue(PhraseCheckScore.samePhrase("Écho's LINE!","E\u0301chos line"));
        assertFalse(PhraseCheckScore.samePhrase("write four words","write 4 words"));
    }
    @Test public void liveTapRejectsOlderPcmOldOwnersCuesGapsAndOverflow(){
        PhraseCheckInput a=new PhraseCheckInput(),b=new PhraseCheckInput();
        try{
            PhraseCheckInput.borrow(a);PhraseCheckInput.frame(new short[]{91},1,false);assertEquals(0,a.size());
            PhraseCheckInput.drained();PhraseCheckInput.frame(new short[]{12,13},2,false);
            assertArrayEquals(new short[]{12,13},a.finish());
            PhraseCheckInput.borrow(b);PhraseCheckInput.release(a);
            PhraseCheckInput.frame(new short[]{34},1,false);assertEquals(0,b.size());
            PhraseCheckInput.drained();PhraseCheckInput.frame(new short[]{56},1,false);
            PhraseCheckInput.frame(new short[]{78},1,true);
            assertFalse(b.problem().isEmpty());assertEquals(0,b.finish().length);
            PhraseCheckInput c=new PhraseCheckInput();PhraseCheckInput.borrow(c);PhraseCheckInput.drained();
            PhraseCheckInput.frame(new short[]{1},1,false);PhraseCheckInput.gap();assertEquals(0,c.finish().length);PhraseCheckInput.release(c);
            PhraseCheckInput bounded=new PhraseCheckInput();bounded.ready();short[] longBlock=new short[PhraseCheckInput.RATE*13];
            bounded.add(longBlock,longBlock.length);bounded.fail("Later route change");
            assertEquals(PhraseCheckInput.RATE*12,bounded.finish().length);
            assertEquals(0,bounded.finish().length);
        }finally{PhraseCheckInput.release(a);PhraseCheckInput.release(b);}
    }
    private void running(){
        ReflectionHelpers.setStaticField(RecallService.class,"running",true);
        LiveCapture.start();LiveCapture.samples(new short[]{100,200},2);
    }
    @Test public void existingVoicesEntryNeedsNoProfileAndDoesNotStartTheRecorder(){
        ActivityController<VoiceProfilesActivity> ctrl=Robolectric.buildActivity(VoiceProfilesActivity.class).setup();
        try{find(ctrl.get().getWindow().getDecorView(),"Check how Recall hears me").performClick();
            assertEquals(PhraseCheckActivity.class.getName(),shadowOf(ctrl.get()).getNextStartedActivity().getComponent().getClassName());
            assertNull(shadowOf(app).getNextStartedService());assertTrue(new VoiceProfiles(app).list().isEmpty());
        }finally{ctrl.pause().stop().destroy();}
    }
    @Test public void explicitLiveConsentAndLeavingDiscardUnconfirmedSamples(){
        running();ActivityController<PhraseCheckActivity> c=Robolectric.buildActivity(PhraseCheckActivity.class).setup();
        try{
            View root=c.get().getWindow().getDecorView();find(root,"Read this line").performClick();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing());
            dialog.getButton(-2).performClick();shadowOf(Looper.getMainLooper()).idle();
            assertNull(ReflectionHelpers.getStaticField(PhraseCheckInput.class,"borrowed"));
            find(root,"Read this line").performClick();ShadowAlertDialog.getLatestAlertDialog().getButton(-1).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            PhraseCheckInput trial=ReflectionHelpers.getStaticField(PhraseCheckInput.class,"borrowed");assertNotNull(trial);
            PhraseCheckInput.drained();PhraseCheckInput.frame(new short[]{456},1,false);assertEquals(1,trial.size());
            c.pause();assertEquals(0,trial.finish().length);assertNull(ReflectionHelpers.getStaticField(PhraseCheckInput.class,"borrowed"));
            c.resume();assertNotNull(find(root,"Read this line"));assertNull(find(root,"I read this line · compare"));
            assertFalse(app.getSharedPreferences("recall-speech",0).contains("liftQuiet"));
            assertTrue(new VoiceProfiles(app).list().isEmpty());assertNull(shadowOf(app).getNextStartedService());
        }finally{c.pause().stop().destroy();}
    }
    @Test public void vaultPauseKeepsTheReferenceAndDoesNotOpenAMicrophone(){
        ReflectionHelpers.setStaticField(org.continuity.recall.ListenPolicy.class,"paused",true);
        ActivityController<PhraseCheckActivity> c=Robolectric.buildActivity(PhraseCheckActivity.class).setup();
        try{
            View root=c.get().getWindow().getDecorView();EditText field=(EditText)find(root,"The silver river keeps the moon awake");
            field.setText("A word I invented");find(root,"Read this line").performClick();
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
            assertNull(ReflectionHelpers.getStaticField(PhraseCheckInput.class,"borrowed"));assertNull(shadowOf(app).getNextStartedService());
            assertEquals("A word I invented",field.getText().toString());assertFalse(field.isSaveEnabled());
        }finally{c.pause().stop().destroy();}
    }
}
