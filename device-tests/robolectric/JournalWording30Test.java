package org.totalrecall.listening;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.os.UserManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import android.widget.EditText;
import android.widget.TextView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,35},manifest=Config.NONE,qualifiers="w360dp-h780dp-mdpi")
public class JournalWording30Test {
    private Context app;

    @Before public void setUp(){
        app=RuntimeEnvironment.getApplication();
        ArchiveStore.setReserveMiB(app,16);
        JournalStore.setEnabled(app,true);
    }

    @Test public void upgradesRealV3RowsWithoutInventingUncertaintyOrLosingEdits(){
        long at=System.currentTimeMillis();
        try(SQLiteDatabase old=app.openOrCreateDatabase("recall-journal.db",Context.MODE_PRIVATE,null)){
            old.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,start INTEGER NOT NULL,end INTEGER NOT NULL DEFAULT 0,reason TEXT NOT NULL DEFAULT '')");
            old.execSQL("CREATE TABLE lines(id INTEGER PRIMARY KEY AUTOINCREMENT,session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,at INTEGER NOT NULL,text TEXT NOT NULL,is_partial INTEGER NOT NULL,voice INTEGER NOT NULL DEFAULT 0,original TEXT)");
            old.execSQL("CREATE INDEX lines_session ON lines(session,id)");
            old.execSQL("CREATE TABLE journal_voices(session INTEGER REFERENCES sessions(id) ON DELETE CASCADE,voice INTEGER,name TEXT NOT NULL,source TEXT NOT NULL,PRIMARY KEY(session,voice))");
            old.execSQL("INSERT INTO sessions(id,start) VALUES (7,?)",new Object[]{at});
            old.execSQL("INSERT INTO lines VALUES (23,7,?,'old correction',0,2,'old recognition')",new Object[]{at});
            old.execSQL("INSERT INTO journal_voices VALUES (7,2,'Poet','Me')");
            old.setVersion(3);
        }
        try(JournalStore store=new JournalStore(app)){
            SQLiteDatabase db=store.getWritableDatabase();
            assertEquals(4,db.getVersion());
            try(Cursor row=db.rawQuery("SELECT text,original,name,source,check_words FROM lines JOIN journal_voices USING(session,voice) WHERE id=23",null)){
                assertTrue(row.moveToFirst());assertEquals("old correction",row.getString(0));assertEquals("old recognition",row.getString(1));
                assertEquals("Poet",row.getString(2));assertEquals("Me",row.getString(3));assertEquals("",row.getString(4));
            }
            try(Cursor idx=db.rawQuery("SELECT sql FROM sqlite_master WHERE name='lines_to_check'",null)){
                assertTrue(idx.moveToFirst());assertTrue(idx.getString(0).contains("WHERE check_words<>''"));
            }
            long epoch=JournalStore.epoch(app);
            assertTrue(store.append(7,epoch,"ordinary passage",false,2));
            assertTrue(store.append(7,epoch,"unfinished passage",true,2,null,"unfinished"));
            store.markResumed(7,"input resumed");
            assertTrue(store.append(7,epoch,"one uncertain word",false,2,null,"uncertain"));
            try(Cursor row=db.rawQuery("SELECT text FROM lines WHERE check_words<>''",null)){
                assertTrue(row.moveToFirst());assertEquals("one uncertain word",row.getString(0));assertFalse(row.moveToNext());
            }
        }
    }

    @Test public void chaptersKeepVoicesAndOriginalWordingAndTheLiveSession(){
        assertFalse(JournalStore.chapters(app));
        long epoch=JournalStore.epoch(app);
        try(JournalStore store=new JournalStore(app)){
            long live=store.begin(epoch);
            assertEquals(-1,store.saveChapter(live,epoch,0));
            store.nameVoice(live,2,"Poet","Me");
            store.append(live,epoch,"RhymeMosaic",false,2,"rhyme mosaic","mosaic");
            assertEquals(-1,store.saveChapter(live,epoch+1,0));
            long saved=store.saveChapter(live,epoch,0);
            assertTrue(saved>0);assertEquals(-1,store.saveChapter(live,epoch,0));
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT text,original,check_words,name,source FROM lines JOIN journal_voices USING(session,voice) WHERE session=?",new String[]{Long.toString(saved)})){
                assertTrue(c.moveToFirst());assertEquals("RhymeMosaic",c.getString(0));assertEquals("rhyme mosaic",c.getString(1));
                assertEquals("mosaic",c.getString(2));assertEquals("Poet",c.getString(3));assertEquals("Me",c.getString(4));assertFalse(c.moveToNext());
            }
            JournalStore.setChapters(app,true);
            store.getWritableDatabase().execSQL("UPDATE sessions SET start=? WHERE id=?",new Object[]{System.currentTimeMillis()-JournalStore.CHAPTER_INTERVAL-1,live});
            assertTrue(store.append(live,epoch,"a second thought",true,2));
            assertEquals(2,store.sessions().size());
            assertTrue(store.append(live,epoch,"a second thought completed",false,2));
            assertEquals(3,store.sessions().size());
            assertTrue(store.append(live,epoch,"another line",false,2));
            assertEquals(3,store.sessions().size());assertEquals("Poet",store.voices(live).get(2)[0]);
        }
    }

    private static void await(String message,BooleanSupplier condition)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
        do{
            shadowOf(Looper.getMainLooper()).idleFor(40,TimeUnit.MILLISECONDS);
            if(condition.getAsBoolean())return;
            Thread.sleep(15);
        }while(System.nanoTime()<deadline);
        fail(message);
    }

    private static TextView find(View v,String text){
        if(v instanceof TextView && text.contentEquals(((TextView)v).getText()))return (TextView)v;
        if(v instanceof ViewGroup){
            ViewGroup group=(ViewGroup)v;
            for(int i=0;i<group.getChildCount();i++){
                TextView match=find(group.getChildAt(i),text);
                if(match!=null)return match;
            }
        }
        return null;
    }

    private static AlertDialog openLine(TextView transcript,String snippet){
        Spanned body=(Spanned)transcript.getText();
        for(ClickableSpan entry:body.getSpans(0,body.length(),ClickableSpan.class)){
            if(body.subSequence(body.getSpanStart(entry),body.getSpanEnd(entry)).toString().contains(snippet)){
                entry.onClick(transcript);
                return ShadowAlertDialog.getLatestAlertDialog();
            }
        }
        throw new AssertionError("Missing journal line "+snippet);
    }

    private static void assertRow(JournalStore db,String text,String original,String flagged){
        try(Cursor row=db.getReadableDatabase().rawQuery("SELECT original,check_words FROM lines WHERE text=?",new String[]{text})){
            assertTrue(row.moveToFirst());assertEquals(original,row.getString(0));assertEquals(flagged,row.getString(1));
        }
    }

    @Test public void actualReviewActionsKeepRawRecognitionAndClearOnlyReviewedLines()throws Exception{
        long epoch=JournalStore.epoch(app);
        try(JournalStore db=new JournalStore(app)){
            long session=db.begin(epoch);
            db.append(session,epoch,"First wording",false,0,null,"First");
            db.append(session,epoch,"Coined spelling",false,0,"coined spilling","spelling");
            db.append(session,epoch,"Ordinary entry",false,0);
            ActivityController<FullJournalActivity> screen=Robolectric.buildActivity(FullJournalActivity.class).setup();
            try{
                FullJournalActivity activity=screen.get();
                View root=activity.getWindow().getDecorView();
                TextView transcript=ReflectionHelpers.getField(activity,"transcript");
                await("Missing review queue count",()->find(root,"Check uncertain wording · 2")!=null);
                find(root,"Check uncertain wording · 2").performClick();
                await("Ordinary entry still visible",()->!transcript.getText().toString().contains("Ordinary entry"));
                AlertDialog first=openLine(transcript,"First wording");
                View passage=first.getWindow().getDecorView();
                Button right=first.getButton(AlertDialog.BUTTON_NEUTRAL),save=first.getButton(AlertDialog.BUTTON_POSITIVE);
                EditText input=(EditText)find(passage,"First wording");
                assertFalse(save.isEnabled());input.setText("Different");assertFalse(right.isEnabled());assertTrue(save.isEnabled());
                input.setText("First wording");assertTrue(right.isEnabled());right.performClick();
                await("Accepted line remains",()->!transcript.getText().toString().contains("First wording"));
                assertRow(db,"First wording",null,"");

                AlertDialog second=openLine(transcript,"Coined spelling");
                View fields=second.getWindow().getDecorView();
                assertNotNull(find(fields,"Originally heard\ncoined spilling"));
                ((EditText)find(fields,"Coined spelling")).setText("CoinedSpell");
                second.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                await("Review did not finish",()->transcript.getText().toString().contains("No passages left to check."));
                assertRow(db,"CoinedSpell","coined spilling","");
                find(root,"Show all journal lines").performClick();
                await("Ordinary entry unavailable",()->transcript.getText().toString().contains("Ordinary entry"));
                find(root,"Compare with original recognition").performClick();
                await("Original unavailable",()->transcript.getText().toString().contains("Originally heard · coined spilling"));
            }finally{screen.pause().stop().destroy();}
        }
    }

    @Test public void clearingLastOlderPassageReturnsToOutstandingReview()throws Exception{
        long epoch=JournalStore.epoch(app);
        try(JournalStore db=new JournalStore(app)){
            long session=db.begin(epoch);
            for(int i=0;i<41;i++)db.append(session,epoch,"Passage number "+i,false,0,null,"number");
        }
        ActivityController<FullJournalActivity> screen=Robolectric.buildActivity(FullJournalActivity.class).setup();
        try{
            FullJournalActivity activity=screen.get();
            View root=activity.getWindow().getDecorView();
            TextView transcript=ReflectionHelpers.getField(activity,"transcript");
            await("Queue count",()->find(root,"Check uncertain wording · 41")!=null);
            find(root,"Check uncertain wording · 41").performClick();
            await("Older page unavailable",()->find(root,"Show all journal lines")!=null && find(root,"Older lines").isEnabled());
            find(root,"Older lines").performClick();
            await("Oldest line unavailable",()->transcript.getText().toString().contains("Passage number 0"));
            openLine(transcript,"Passage number 0").getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            await("Remaining queue hidden",()->transcript.getText().toString().contains("Passage number 40"));
            assertFalse(find(root,"Older lines").isEnabled());
        }finally{screen.pause().stop().destroy();}
    }

    @Test public void opensUsableOverlappingAudioAtTheActualPlayheadAndHandlesUncoveredPassage()throws Exception{
        ReflectionHelpers.setStaticField(org.continuity.recall.ListenPolicy.class,"paused",Boolean.FALSE);
        long at=System.currentTimeMillis()-60000,epoch=JournalStore.epoch(app),valid;
        try(JournalStore db=new JournalStore(app);Store clips=new Store(app)){
            long session=db.begin(epoch);
            db.append(session,epoch,"Covered wording",false,0,null,"Covered");
            db.append(session,epoch,"Faraway wording",false,0,null,"Faraway");
            db.getWritableDatabase().execSQL("UPDATE lines SET at=? WHERE text='Covered wording'",new Object[]{at});
            valid=clips.save(new SelectiveEngine.Clip(new short[30*16000],"original","Poetry","kept",at-15000));
            long broken=clips.save(new SelectiveEngine.Clip(new short[4*16000],"short clip","Poetry","kept",at-2000));
            assertTrue(clips.get(broken).audio.delete());
            try(ArchiveStore archive=new ArchiveStore(app)){segment(archive,at-18000,400);}
        }
        ActivityController<FullJournalActivity> journal=Robolectric.buildActivity(FullJournalActivity.class).setup();
        try{
            FullJournalActivity activity=journal.get();
            TextView transcript=ReflectionHelpers.getField(activity,"transcript");
            await("Missing passages",()->transcript.getText().toString().contains("Faraway wording"));
            AlertDialog unavailable=openLine(transcript,"Faraway wording");
            View fields=unavailable.getWindow().getDecorView();
            find(fields,"Find audio at this time").performClick();
            await("Missing audio was not reported",()->find(fields,"No saved audio on this phone covers this time.")!=null);
            assertNull(shadowOf(activity).peekNextStartedActivity());
            unavailable.dismiss();

            AlertDialog dismissed=openLine(transcript,"Covered wording");
            ExecutorService io=ReflectionHelpers.getField(activity,"io");
            CountDownLatch resumeLookup=new CountDownLatch(1);
            io.execute(()->{
                try{resumeLookup.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            });
            find(dismissed.getWindow().getDecorView(),"Find audio at this time").performClick();
            dismissed.dismiss();
            resumeLookup.countDown();
            io.submit(()->{}).get(5,TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            assertNull("A closed passage must not open a player",shadowOf(activity).peekNextStartedActivity());

            AlertDialog covered=openLine(transcript,"Covered wording");
            find(covered.getWindow().getDecorView(),"Find audio at this time").performClick();
            await("No saved clip opened",()->shadowOf(activity).peekNextStartedActivity()!=null);
            Intent target=shadowOf(activity).getNextStartedActivity();
            assertEquals(RecallListenActivity.class.getName(),target.getComponent().getClassName());
            assertEquals(valid,target.getLongExtra("moment_id",-1));assertEquals(9000L,target.getLongExtra("clip_offset_ms",-1));
            assertTrue(covered.isShowing());assertTrue(target.getBooleanExtra("return_to_journal",false));
            ActivityController<RecallListenActivity> player=Robolectric.buildActivity(RecallListenActivity.class,target).setup();
            try{
                await("Clip Studio did not load the saved audio",()->{
                    ClipStudio studio=ReflectionHelpers.getField(player.get(),"studio");
                    return studio!=null && (Boolean)ReflectionHelpers.getField(studio,"ready");
                });
                ClipStudio studio=ReflectionHelpers.getField(player.get(),"studio");
                assertEquals(9*16000,(int)ReflectionHelpers.getField(studio,"cursor"));
                assertFalse((Boolean)ReflectionHelpers.getField(studio,"playing"));
                studio.close();
                await("Closing clip did not return to the journal",()->player.get().isFinishing());
                assertTrue(covered.isShowing());covered.dismiss();
            }finally{player.pause().stop().destroy();}
        }finally{journal.pause().stop().destroy();}
    }
    private static ArchiveStore.Segment segment(ArchiveStore archive,long start,int frames)throws Exception{
        ArchiveStore.Stage stage=archive.stage(start,32000);
        // One valid silent AAC-LC ADTS frame at 16 kHz, 1024 samples per frame.
        byte[] frame={(byte)0xff,(byte)0xf1,0x60,0x40,0x01,0x7f,(byte)0xfc,0x01,0x18,0x20,0x07};
        try(FileOutputStream out=new FileOutputStream(stage.partial)){for(int i=0;i<frames;i++)out.write(frame);}
        return archive.seal(stage,1024L*frames);
    }
    private void ordinaryLine(long at,String words){
        try(JournalStore db=new JournalStore(app)){
            long epoch=JournalStore.epoch(app),session=db.begin(epoch);
            assertTrue(db.append(session,epoch,words,false,0));
            db.getWritableDatabase().execSQL("UPDATE lines SET at=? WHERE session=?",new Object[]{at,session});
        }
    }
    @Test public void ordinaryLineFindsVerifiedSegmentAndSeeksOnlyOnPlay()throws Exception{
        long at=System.currentTimeMillis()-60000;
        ordinaryLine(at,"Ordinary audio passage");
        ArchiveStore.Segment valid;
        try(ArchiveStore db=new ArchiveStore(app)){
            valid=segment(db,at-9013,250);
            ArchiveStore.Segment corrupt=segment(db,at-1000,40);
            File data=new File(new File(app.getFilesDir(),"audio-archive"),corrupt.id+".aac");
            try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(data,"rw")){file.seek(9);file.write(0xff);}
            assertEquals(valid.id,db.covering(at).id);
            assertNull(db.covering(valid.endMs));
        }
        ActivityController<FullJournalActivity> screen=Robolectric.buildActivity(FullJournalActivity.class).setup();
        try{
            FullJournalActivity activity=screen.get();TextView transcript=ReflectionHelpers.getField(activity,"transcript");
            await("Missing ordinary journal line",()->transcript.getText().toString().contains("Ordinary audio passage"));
            AlertDialog passage=openLine(transcript,"Ordinary audio passage");
            View root=passage.getWindow().getDecorView();
            CheckBox lead=(CheckBox)find(root,"Include 6 seconds before the timestamp");
            assertTrue(lead.isChecked());lead.performClick();
            find(root,"Find audio at this time").performClick();
            await("Segment player did not open",()->ShadowAlertDialog.getLatestAlertDialog()!=passage);
            AlertDialog preview=ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(passage.isShowing());assertNull(shadowOf(activity).peekNextStartedActivity());
            assertNotNull(find(preview.getWindow().getDecorView(),"Paused · 0:09.013 / 0:16.000"));
            JournalSegmentPlayer segmentPlayer=ReflectionHelpers.getField(activity,"segmentPlayer");
            assertNull(ReflectionHelpers.getField(segmentPlayer,"player"));
            DataSource.setFileDescriptorTransform((fd,offset)->"verified-journal-segment-"+offset);
            ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(new FileDescriptor()),new ShadowMediaPlayer.MediaInfo(16000,0));
            shadowOf(app.getSystemService(AudioManager.class)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            await("Requested position was not played",()->(Boolean)ReflectionHelpers.getField(segmentPlayer,"playing"));
            MediaPlayer transport=ReflectionHelpers.getField(segmentPlayer,"player");
            assertTrue(transport.isPlaying());
            assertTrue("Playback restarted from the beginning",transport.getCurrentPosition()>=9013);
            preview.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            await("Closing segment did not return to the same passage",()->!preview.isShowing() && passage.isShowing());
            assertNull(ReflectionHelpers.getField(segmentPlayer,"player"));

            lead.performClick();find(root,"Find audio at this time").performClick();
            await("Lead-in player unavailable",()->ShadowAlertDialog.getLatestAlertDialog()!=preview);
            AlertDialog leadPreview=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(find(leadPreview.getWindow().getDecorView(),"Paused · 0:03.013 / 0:16.000"));
            screen.pause();assertFalse(leadPreview.isShowing());screen.resume();
            assertTrue(passage.isShowing());passage.dismiss();
        }finally{screen.pause().stop().destroy();}
    }

    @Test public void reportsLockedEvictedAndExpiredWithoutLaunchingPlayback()throws Exception{
        long at=System.currentTimeMillis()-40000;
        ordinaryLine(at,"Archived passage");
        try(ArchiveStore db=new ArchiveStore(app)){
            ArchiveStore.Segment saved=segment(db,at-3000,90);
            assertTrue(db.markSynced(saved.id,saved.sha256,"test receipt"));db.delete(saved.id);
        }
        ActivityController<FullJournalActivity> screen=Robolectric.buildActivity(FullJournalActivity.class).setup();
        try{
            FullJournalActivity activity=screen.get();TextView transcript=ReflectionHelpers.getField(activity,"transcript");
            await("Missing retained text",()->transcript.getText().toString().contains("Archived passage"));
            AlertDialog passage=openLine(transcript,"Archived passage");View root=passage.getWindow().getDecorView();
            Button check=(Button)find(root,"Find audio at this time");
            UserManager users=app.getSystemService(UserManager.class);shadowOf(users).setUserUnlocked(false);
            check.performClick();await("Locked storage reported incorrectly",()->check.getText().toString().contains("Unlock this phone"));
            shadowOf(users).setUserUnlocked(true);check.performClick();
            await("Evicted segment reported incorrectly",()->check.getText().toString().contains("no longer on this phone"));
            try(JournalStore store=new JournalStore(app)){store.getWritableDatabase().delete("lines",null,null);}
            check.performClick();await("Stale journal passage opened",()->check.getText().toString().contains("expired or was deleted"));
            assertNull(shadowOf(activity).peekNextStartedActivity());assertNull(ReflectionHelpers.getField(activity,"segmentPlayer"));
            passage.dismiss();
        }finally{screen.pause().stop().destroy();}
    }

}
