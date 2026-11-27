package org.totalrecall.listening;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,35},manifest=Config.NONE)
public class WearableMic30Test {
    private static void measure(WearableMicCheck check,int stage,int value){
        check.start(stage);
        short[] oneSecond=new short[16000];Arrays.fill(oneSecond,(short)value);
        check.accept(oneSecond,8000);check.accept(oneSecond,16000);check.accept(oneSecond,16000);
    }
    @Test public void rawLevelsExcludeSettlingAndResetWhenSourceChanges(){
        WearableMicCheck check=new WearableMicCheck();
        assertFalse(check.available(WearableMicCheck.ROOM));
        Object input=new Object();check.route(input,5,"USB receiver",16000,1);
        assertFalse(check.available(WearableMicCheck.B));
        check.start(WearableMicCheck.ROOM);
        short[] tap=new short[8000];Arrays.fill(tap,Short.MAX_VALUE);check.accept(tap,tap.length);
        short[] quiet=new short[16000];Arrays.fill(quiet,(short)100);
        check.accept(quiet,quiet.length);assertFalse(check.available(WearableMicCheck.A));
        check.accept(quiet,quiet.length);assertTrue(check.available(WearableMicCheck.A));
        measure(check,WearableMicCheck.A,1000);measure(check,WearableMicCheck.B,0);
        assertTrue(check.reading().contains("+20 dB vs room"));assertTrue(check.reading().contains("Wrist B · digital silence"));
        assertFalse(check.reading().contains("near full scale"));
        measure(check,WearableMicCheck.B,32767);assertTrue(check.reading().contains("near full scale"));
        check.route(input,6,"Phone microphone",16000,1);
        assertFalse(check.available(WearableMicCheck.A));assertFalse(check.reading().contains("dBFS"));
        check.pause();assertFalse(check.available(WearableMicCheck.ROOM));assertTrue(check.reading().contains("No live frames"));
    }
    private static TextView find(View root,String label){
        if(root instanceof TextView&&label.contentEquals(((TextView)root).getText()))return (TextView)root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){
            TextView hit=find(group.getChildAt(i),label);if(hit!=null)return hit;
        }}
        return null;
    }
    @Test public void liveScreenOpensCheckWithoutStartingMicrophone(){
        ActivityController<CaptureReviewActivity> ctrl=Robolectric.buildActivity(CaptureReviewActivity.class).setup();
        try{
            find(ctrl.get().getWindow().getDecorView(),"Wearable mic check").performClick();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing());
            View root=dialog.getWindow().getDecorView();
            Button room=(Button)find(root,"Measure quiet room");
            assertFalse(room.isEnabled());assertFalse(RecallService.isListening());
            WearableMicCheck active=ReflectionHelpers.getStaticField(WearableMicCheck.class,"visible");
            active.route(new Object(),5,"USB receiver",16000,1);
            shadowOf(Looper.getMainLooper()).idleFor(201,TimeUnit.MILLISECONDS);
            assertTrue(room.isEnabled());assertFalse(find(root,"Measure wrist A").isEnabled());
            measure(active,WearableMicCheck.ROOM,0);
            shadowOf(Looper.getMainLooper()).idleFor(201,TimeUnit.MILLISECONDS);
            assertTrue(find(root,"Measure wrist A").isEnabled());
            dialog.dismiss();shadowOf(Looper.getMainLooper()).idle();
            assertNull(ReflectionHelpers.getStaticField(WearableMicCheck.class,"visible"));
        }finally{ctrl.pause().stop().destroy();}
    }
}
