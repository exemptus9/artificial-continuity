package org.totalrecall.listening;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.media.MediaPlayer;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,35},manifest=Config.NONE)
public class PracticeGuidance30Test {
    Application app;
    @Before public void init(){
        app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("recall-speech",0).edit().clear().commit();
        VoiceProfiles.reset(app);ReflectionHelpers.setStaticField(RecallService.class,"running",false);
        ReflectionHelpers.setStaticField(org.continuity.recall.ListenPolicy.class,"paused",false);
        LiveCapture.stop("Paused");
    }
    @After public void finish(){VoiceProfiles.reset(app);ReflectionHelpers.setStaticField(RecallService.class,"running",false);LiveCapture.stop("Paused");}
    private static TextView find(View v,String text){
        if(v instanceof TextView && text.contentEquals(((TextView)v).getText()))return (TextView)v;
        if(v instanceof ViewGroup){
            ViewGroup group=(ViewGroup)v;
            for(int i=0;i<group.getChildCount();i++){TextView found=find(group.getChildAt(i),text);if(found!=null)return found;}
        }
        return null;
    }
    @Test public void examplesDoNotEnableAutomaticSpeechOrStartTheMic(){
        ActivityController<VoiceProfilesActivity> ctrl=Robolectric.buildActivity(VoiceProfilesActivity.class).setup();
        try{
            View body=ctrl.get().getWindow().getDecorView();
            Switch opt=(Switch)find(body,"Speak choices during practice and when closing");assertNotNull(opt);assertFalse(opt.isChecked());
            find(body,"Preview spoken choices").performClick();
            AlertDialog list=ShadowAlertDialog.getLatestAlertDialog();
            list.getListView().performItemClick(null,0,0);
            AlertDialog preview=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(find(preview.getWindow().getDecorView(),SpokenChoices.Prompt.MIC.words));
            assertNotNull(preview.getButton(-1));
            preview.getButton(-1).performClick();preview.dismiss();
            assertFalse(new SpokenChoices(app).enabled());assertNull(shadowOf(app).getNextStartedService());
            opt.performClick();assertTrue(new SpokenChoices(app).enabled());
        }finally{ctrl.pause().stop().destroy();}
    }
    @Test public void differentSpeakerRequiresRetryAndReplayCannotConfirm(){
        app.getSharedPreferences("recall-speech",Context.MODE_PRIVATE).edit().putBoolean(SpokenChoices.PREF,true).commit();
        VoiceProfiles store=new VoiceProfiles(app);
        double[] self=new double[128],other=new double[128];self[0]=1;other[1]=1;
        store.enroll(null,"Me","Me",self);store.enroll(null,"Other","Another person",other);
        ReflectionHelpers.setStaticField(RecallService.class,"running",true);LiveCapture.start();LiveCapture.voiceSession(-1,true);
        ActivityController<VoiceProfilesActivity> ctrl=Robolectric.buildActivity(VoiceProfilesActivity.class).setup();
        try{
            View body=ctrl.get().getWindow().getDecorView();
            find(body,"Add a sample of my voice").performClick();
            assertNull(find(body,"Hear choices again"));
            shadowOf(Looper.getMainLooper()).idleFor(3,TimeUnit.SECONDS);
            VoiceProfiles.offer("Three synthetic test words",other,180);
            shadowOf(Looper.getMainLooper()).idleFor(400,TimeUnit.MILLISECONDS);
            assertFalse(find(body,"Use this sample").isEnabled());
            find(body,"Hear choices again").performClick();
            assertFalse(find(body,"Use this sample").isEnabled());
            assertEquals(1,store.list().get(0).examples.size());assertEquals(2,store.list().size());
            find(body,"Try another phrase").performClick();
            assertNull(find(body,"Hear choices again"));
            assertFalse(find(body,"Use this sample").isEnabled());
        }finally{ctrl.pause().stop().destroy();}
    }
    @Test public void replayOfExitChoiceCannotEndTheSession(){
        app.getSharedPreferences("recall-speech",Context.MODE_PRIVATE).edit().putBoolean(SpokenChoices.PREF,true).commit();
        ActivityController<EndListeningActivity> ctrl=Robolectric.buildActivity(EndListeningActivity.class).setup();
        try{
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Hear choices again",dialog.getButton(-3).getText());
            dialog.getButton(-3).performClick();
            assertTrue(dialog.isShowing());assertFalse(ctrl.get().isFinishing());
            assertNull(shadowOf(app).getNextStartedService());
            dialog.getButton(-2).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(ctrl.get().isFinishing());
            assertNull(shadowOf(app).getNextStartedService());
        }finally{ctrl.pause().stop().destroy();}
    }
    @Test public void foregroundPromptBracketsPlaybackAndKeepsReleaseTail() throws Exception{
        SpokenChoices guide=new SpokenChoices(app);
        MediaPlayer player=new MediaPlayer();
        ShadowMediaPlayer.setMediaInfoProvider(ds->new ShadowMediaPlayer.MediaInfo(3000,0));
        player.setDataSource("test-prompt");player.prepare();
        try{
            assertTrue(guide.start(player));
            assertTrue(CuePlaybackGate.active());
            long worstPrompt=System.nanoTime()+5_400_000_000L;
            assertTrue(CuePlaybackGate.overlaps(worstPrompt,worstPrompt));
            guide.close();
            assertEquals(ShadowMediaPlayer.State.END,shadowOf(player).getState());
            long beyondTail=System.nanoTime()+1_000_000_000L;
            assertFalse(CuePlaybackGate.overlaps(beyondTail,beyondTail));
        }finally{guide.close();}
    }
}
