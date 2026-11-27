package org.totalrecall.listening;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,35},manifest=Config.NONE)
public class VoicePractice29Test {
    private static double[] vector(double angle){
        double[] v=new double[128];v[0]=Math.cos(angle);v[1]=Math.sin(angle);return v;
    }
    @Test public void retainsDistinctDeliveryAndSkipsDuplicateExamples(){
        List<double[]> bank=new ArrayList<>();
        VoiceProfiles.retainExample(bank,vector(0));
        VoiceProfiles.retainExample(bank,vector(-.85));
        for(int i=1;i<=17;i++)VoiceProfiles.retainExample(bank,vector(i*.105));
        int count=bank.size();
        assertEquals(VoiceProfiles.MAX_SAMPLES,count);
        assertArrayEquals(vector(0),bank.get(0),1e-10);
        assertTrue(bank.stream().anyMatch(v->Math.abs(v[1]-Math.sin(-.85))<1e-9));
        VoiceProfiles.retainExample(bank,bank.get(0));
        assertEquals(count,bank.size());
    }
    @Test public void onlyTaughtTargetInsideNeutralNamingFrameCanChange(){
        List<SpeechContext.Rule> learned=Collections.singletonList(new VoiceWording("moon cool","Moonquill").rule());
        List<SpeechContext.Rule> stock=Collections.singletonList(new SpeechContext.Rule("write","(right)"));
        assertEquals("I use the word Moonquill right here",SpeechContext.correct("","I use the word moon cool right here",stock,learned));
        assertEquals("Moonquill",SpeechContext.correct("the word is","moon cool",stock,learned));
        for(String verbatim:new String[]{"the word moon cool rhymes with school","spell the word moon cool","quote the word moon cool end quote","the word is right next to moon cool","pun intended the word moon cool"})
            assertEquals(verbatim,SpeechContext.correct("",verbatim,stock,learned));
    }
    private static TextView view(View v,String words){
        if(v instanceof TextView && (words.contentEquals(((TextView)v).getText()) ||
                words.equals(v.getContentDescription())))return (TextView)v;
        if(v instanceof ViewGroup){
            ViewGroup group=(ViewGroup)v;
            for(int i=0;i<group.getChildCount();i++){
                TextView hit=view(group.getChildAt(i),words);if(hit!=null)return hit;
            }
        }
        return null;
    }
    private static void offer(String text,double[] embedding){
        shadowOf(Looper.getMainLooper()).idleFor(3,TimeUnit.SECONDS);
        VoiceProfiles.offer(text,embedding,220);
        shadowOf(Looper.getMainLooper()).idleFor(350,TimeUnit.MILLISECONDS);
    }
    private void chooseWord(VoiceProfilesActivity activity){
        view(activity.getWindow().getDecorView(),"Practice a name or coined word").performClick();
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
        ((EditText)view(dialog.getWindow().getDecorView(),"Preferred spelling")).setText("Moonquill");
        dialog.getButton(-1).performClick();
    }

    @Test public void explicitStartAndRecreationReturnToRequestedTerm(){
        android.app.Application app=RuntimeEnvironment.getApplication();
        VoiceProfiles.reset(app);new VoiceProfiles(app).enroll(null,"Poet","Me",vector(0));
        ReflectionHelpers.setStaticField(org.continuity.recall.ListenPolicy.class,"paused",false);
        ReflectionHelpers.setStaticField(RecallService.class,"running",false);
        LiveCapture.stop("Paused");shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO);
        ActivityController<VoiceProfilesActivity> c=Robolectric.buildActivity(VoiceProfilesActivity.class).setup();
        try{
            chooseWord(c.get());
            assertNull(shadowOf(app).getNextStartedService());
            view(c.get().getWindow().getDecorView(),"Start listening and continue").performClick();
            android.content.Intent start=shadowOf(app).getNextStartedService();
            assertNotNull(start);assertEquals(RecallService.START,start.getAction());
            android.os.Bundle rotation=new android.os.Bundle();
            c.pause().saveInstanceState(rotation).stop().destroy();
            ReflectionHelpers.setStaticField(RecallService.class,"running",true);
            LiveCapture.start();
            c=Robolectric.buildActivity(VoiceProfilesActivity.class).create(rotation).start().resume().visible();
            assertNotNull(view(c.get().getWindow().getDecorView(),"Practice “Moonquill”"));
            assertNull(view(c.get().getWindow().getDecorView(),"Use this sample"));
            LiveCapture.voiceSession(-1,true);
            shadowOf(Looper.getMainLooper()).idleFor(450,TimeUnit.MILLISECONDS);
            assertNotNull(view(c.get().getWindow().getDecorView(),"Use this sample"));
            assertNull(shadowOf(app).getNextStartedService());
        }finally{
            c.pause().stop().destroy();LiveCapture.stop("Finished");VoiceProfiles.reset(app);
            ReflectionHelpers.setStaticField(RecallService.class,"running",false);
        }
    }
    @Test public void deniedMicPreservesPhraseAndPermitsRecoveryAfterBackgrounding(){
        android.app.Application app=RuntimeEnvironment.getApplication();
        VoiceProfiles.reset(app);new VoiceProfiles(app).enroll(null,"Poet","Me",vector(0));
        ReflectionHelpers.setStaticField(org.continuity.recall.ListenPolicy.class,"paused",false);
        ReflectionHelpers.setStaticField(RecallService.class,"running",false);LiveCapture.stop("Paused");
        shadowOf(app).denyPermissions(android.Manifest.permission.RECORD_AUDIO);
        ActivityController<VoiceProfilesActivity> c=Robolectric.buildActivity(VoiceProfilesActivity.class).setup();
        try{
            chooseWord(c.get());
            view(c.get().getWindow().getDecorView(),"Start listening and continue").performClick();
            c.get().onRequestPermissionsResult(51,new String[]{android.Manifest.permission.RECORD_AUDIO},new int[]{-1});
            assertNull(shadowOf(app).getNextStartedService());c.pause().resume().visible();
            View root=c.get().getWindow().getDecorView();
            assertNotNull(view(root,"Practice “Moonquill”"));assertNotNull(view(root,"App permissions"));
            view(root,"Cancel").performClick();
            assertNull(view(root,"Practice “Moonquill”"));
            assertNull(shadowOf(app).getNextStartedService());
        }finally{c.pause().stop().destroy();VoiceProfiles.reset(app);}
    }
    @Test public void twoConfirmedVariantsApplyToFutureMatchingLines(){
        Context app=RuntimeEnvironment.getApplication();VoiceProfiles.reset(app);
        VoiceProfiles profiles=new VoiceProfiles(app);
        double[] speaker=vector(0);
        profiles.enroll(null,"Poet","Me",speaker);
        ReflectionHelpers.setStaticField(RecallService.class,"running",true);
        LiveCapture.start();LiveCapture.voiceSession(-1,true);
        ActivityController<VoiceProfilesActivity> c=Robolectric.buildActivity(VoiceProfilesActivity.class).setup();
        try{
            View body=c.get().getWindow().getDecorView();
            view(body,"Practice a name or coined word").performClick();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            ((EditText)view(dialog.getWindow().getDecorView(),"Preferred spelling")).setText("Moonquill");
            dialog.getButton(-1).performClick();
            offer("short",speaker);
            assertFalse(view(body,"Use this sample").isEnabled());
            assertNotNull(view(body,"Heard 1 word · say a whole sentence, then pause."));
            offer("i keep moon cool in my notebook",speaker);
            assertEquals("moon cool",view(body,"What Recall heard for this word").getText().toString());
            view(body,"Use this sample").performClick();
            view(body,"Try another delivery").performClick();
            assertNotNull(view(body,"Say “I wrote Moonquill on this page” at a pace you normally use, then pause."));
            offer("i note moon quill on this page",speaker);
            EditText heard=(EditText)view(body,"What Recall heard for this word");
            heard.setText("a word that was not heard");view(body,"Use this sample").performClick();
            assertNotNull(heard.getError());
            TextView reading=ReflectionHelpers.getField(c.get(),"sampleText");
            android.text.Spanned spans=(android.text.Spanned)reading.getText();
            String text=spans.toString();
            int first=text.indexOf("moon "),last=text.indexOf("quill ");
            spans.getSpans(first,first+1,android.text.style.ClickableSpan.class)[0].onClick(reading);
            spans.getSpans(last,last+1,android.text.style.ClickableSpan.class)[0].onClick(reading);
            assertEquals("moon quill",heard.getText().toString());
            view(body,"Use this sample").performClick();
            view(body,"Done").performClick();
            VoiceProfiles.Profile self=profiles.list().get(0);
            assertEquals(1,self.examples.size());
            assertEquals(2,self.wordings.size());
            assertEquals("the next Moonquill reading",SpeechContext.correct("","the next moon cool reading",Collections.emptyList(),self.trainedRules));
            assertEquals("we use Moonquill",SpeechContext.correct("","we use moon quill",Collections.emptyList(),self.trainedRules));
            assertEquals("we use moonlight",SpeechContext.correct("","we use moonlight",Collections.emptyList(),self.trainedRules));
        }finally{
            c.pause().stop().destroy();
            VoiceProfiles.reset(app);LiveCapture.stop("Finished");
            ReflectionHelpers.setStaticField(RecallService.class,"running",false);
        }
    }
}
