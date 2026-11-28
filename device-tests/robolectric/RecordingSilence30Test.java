package org.totalrecall.listening;

import android.app.Activity;
import android.content.Context;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,35},manifest=Config.NONE,shadows=RecordingSilence30Test.NoWords.class,
        instrumentedPackages="org.totalrecall.listening")
public class RecordingSilence30Test {
    @Implements(value=OfflineSpeech.class,isInAndroidSdk=false)
    public static class NoWords {
        @Implementation protected void __constructor__(Context ignored,boolean commands,boolean voices,boolean review,boolean publishSignal){}
        @Implementation protected String accept(short[] pcm,int used){return null;}
        @Implementation protected String finish(){return "";}
        @Implementation protected String takeReviewHint(){return "";}
    }

    private ActivityController<Activity> screen;
    private Map<String,Pattern> previousRules;
    @Before public void setUp(){
        screen=Robolectric.buildActivity(Activity.class).setup();
        previousRules=ReflectionHelpers.getStaticField(Situation.class,"rules");
        ReflectionHelpers.setStaticField(Situation.class,"rules",Collections.emptyMap());
    }
    @After public void tearDown(){
        ReflectionHelpers.setStaticField(Situation.class,"rules",previousRules);
        screen.pause().stop().destroy();
    }
    private String report(int amplitude,int samples)throws Exception{
        Activity activity=screen.get();
        AudioSituation.Scan scan=new AudioSituation.Scan(activity,new OfflineSpeech(activity,false,false));
        ByteBuffer audio=ByteBuffer.allocate(samples*2).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<samples;i++)audio.putShort((short)(i%2==0?amplitude:-amplitude));
        audio.flip();scan.consume(audio,16000,1);
        return scan.finish();
    }
    @Test public void emptySilentAndLowLevelFilesReportOnlyMeasuredInput()throws Exception{
        assertEquals("No audio samples were decoded.",report(0,0));
        String silent=report(0,16000);
        assertTrue(silent.startsWith("All decoded samples were silent. No words were recognized."));
        assertTrue(silent.contains("Above −50 dBFS  0%"));assertFalse(silent.contains("Suggested account"));
        String quiet=report(8,16000);
        assertTrue(quiet.contains("below −50 dBFS in every analysis window"));
        assertTrue(quiet.contains("No words were recognized."));assertFalse(quiet.contains("were silent"));
    }
    @Test public void measuredToneWithoutRecognitionInvitesCheckingOriginalWithoutInferringSpeech()throws Exception{
        String result=report(3000,32000);
        assertTrue(result.startsWith("Sound was measured above −50 dBFS. No words were recognized."));
        assertTrue(result.contains("Listen to the original recording"));
        assertTrue(result.contains("Above −50 dBFS  100%"));assertTrue(result.contains("Audio decoded  0:02"));
        assertFalse(result.contains("Suggested account"));assertFalse(result.contains("Recognized passages"));
    }
}
