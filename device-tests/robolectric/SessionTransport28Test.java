package org.totalrecall.listening;

import android.app.AlertDialog;
import android.database.MatrixCursor;
import android.media.MediaPlayer;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,35},manifest=Config.NONE)
public class SessionTransport28Test {
    @Test public void indexBindsTwoFullChunksAndPartialTail() throws Exception {
        File sample=File.createTempFile("marker-index",".aac",RuntimeEnvironment.getApplication().getCacheDir());
        try {
            int size=2*4*1024*1024+37;
            byte[] buf=new byte[8192];
            try(OutputStream out=new FileOutputStream(sample)){
                for(int at=0;at<size;at+=buf.length){
                    int len=Math.min(buf.length,size-at);
                    for(int i=0;i<len;i++)buf[i]=(byte)(((at+i)*7+13)%251);
                    out.write(buf,0,len);
                }
            }
            // Independently computed from 3 discrete SHA-256 chunk digests.
            String digest="sha256-chunks-4m-v1:ea18c4ec9d726df22aa2e0ab890806103a917f2654047365efe29efdff3b8f2f";
            assertEquals(digest,LongRecording.audioFingerprint(sample));
            try(RandomAccessFile out=new RandomAccessFile(sample,"rw")){
                out.seek(size-1);out.write(255);
            }
            assertNotEquals(digest,LongRecording.audioFingerprint(sample));
        } finally {sample.delete();}
    }

    private Object open(LongSessionsActivity activity,String title,long offset) throws Exception {
        String id=UUID.randomUUID().toString();
        File file=LongRecording.audio(activity,id,false);
        file.getParentFile().mkdirs();
        try(OutputStream out=new FileOutputStream(file)){
            byte[] frame={(byte)255,(byte)241,0,0,1,0,0,0};
            for(int n=0;n<500;n++)out.write(frame);
        }
        Class<?> rowClass=Class.forName(LongSessionsActivity.class.getName()+"$Row");
        Constructor<?> rowCtor=rowClass.getDeclaredConstructor(android.database.Cursor.class);
        rowCtor.setAccessible(true);
        try(MatrixCursor cursor=new MatrixCursor(new String[]{"id","title","status","ms","bytes"})){
            cursor.addRow(new Object[]{id,title,"Finished",32000,4000});cursor.moveToFirst();
            Method play=LongSessionsActivity.class.getDeclaredMethod("play",rowClass,long.class);
            play.setAccessible(true);play.invoke(activity,rowCtor.newInstance(cursor),offset);
        }
        return ReflectionHelpers.getField(activity,"playback");
    }
    private void waitFor(Object player,String prefix) throws Exception {
        TextView position=ReflectionHelpers.getField(player,"position");
        for(int i=0;i<100;i++){
            shadowOf(Looper.getMainLooper()).idleFor(20,TimeUnit.MILLISECONDS);
            if(position.getText().toString().startsWith(prefix))return;
            Thread.sleep(10);
        }
        fail("Expected "+prefix+", got "+position.getText());
    }
    @Test public void markerStartSeekPauseSwitchAndCloseReleaseAudio() throws Exception {
        ShadowMediaPlayer.setMediaInfoProvider(ds->new ShadowMediaPlayer.MediaInfo(32000,0));
        ActivityController<LongSessionsActivity> ctrl=Robolectric.buildActivity(LongSessionsActivity.class).setup();
        try {
            Object first=open(ctrl.get(),"First",8000);
            waitFor(first,"Playing · 0:08");
            Button toggle=ReflectionHelpers.getField(first,"toggle");
            toggle.performClick();waitFor(first,"Paused · 0:08");
            android.view.ViewGroup row=(android.view.ViewGroup)toggle.getParent();
            row.getChildAt(2).performClick();waitFor(first,"Paused · 0:18");
            row.getChildAt(0).performClick();waitFor(first,"Paused · 0:08");
            toggle.performClick();waitFor(first,"Playing · 0:08");
            MediaPlayer old=ReflectionHelpers.getField(first,"audio");
            Object next=open(ctrl.get(),"Second",0);
            waitFor(next,"Playing · 0:00");
            assertEquals(ShadowMediaPlayer.State.END,shadowOf(old).getState());
            AlertDialog shown=ReflectionHelpers.getField(next,"dialog");
            MediaPlayer current=ReflectionHelpers.getField(next,"audio");
            ctrl.pause();
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse(shown.isShowing());
            assertEquals(ShadowMediaPlayer.State.END,shadowOf(current).getState());
            assertNull(ReflectionHelpers.getField(ctrl.get(),"playback"));
        } finally {ctrl.stop().destroy();}
    }
}
