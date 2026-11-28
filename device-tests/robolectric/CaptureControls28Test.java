package org.totalrecall.listening;

import android.app.AlertDialog;
import android.app.Application;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Looper;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
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

/** UI regressions for actual view clicks and persisted journal preferences. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,35},manifest=Config.NONE,qualifiers="w360dp-h780dp-mdpi")
public class CaptureControls28Test {
    private Application app;
    private Object overlay;

    @Before public void setUp() {
        app=RuntimeEnvironment.getApplication();
        RecallOverlay.options(app).edit().clear().commit();
        LongRecording.active="";
        LiveCapture.start();
        LiveCapture.resume("Listening");
    }
    @After public void tearDown() throws Exception {
        if(overlay!=null)invoke(overlay,"finish");
        LongRecording.active="";
        LongRecording.duration=0;
        LongRecording.marks=0;
        LiveCapture.stop("Finished");RecallService.saveChoice=SelectiveEngine.SaveChoice.NONE;
        ReflectionHelpers.setStaticField(RecallService.class,"running",false);
    }
    private static Object invoke(Object owner,String name) throws Exception {
        Method m=owner.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(owner);
    }
    private static <T> T field(Object owner,String name,Class<T> type) throws Exception {
        Field f=owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return type.cast(f.get(owner));
    }
    private static TextView find(View view,String label) {
        if(view instanceof TextView && label.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) {
                TextView found=find(group.getChildAt(i),label);
                if(found!=null)return found;
            }
        }
        return null;
    }
    private void openOverlay(boolean mini,boolean captions) throws Exception {
        ReflectionHelpers.setStaticField(RecallService.class,"running",true);
        RecallOverlay.options(app).edit().putBoolean("expanded",!mini).putBoolean("ultra",mini).putBoolean("captions",captions).commit();
        Class<?> type=Class.forName("org.totalrecall.listening.RecallOverlay$Controller");
        Constructor<?> create=type.getDeclaredConstructor(Context.class,boolean.class);
        create.setAccessible(true);
        overlay=create.newInstance(app,false);
        invoke(overlay,"build");
        ready();
        paint();
    }
    private void ready() { LiveCapture.samples(new short[1600],1600); }
    private void paint() throws Exception { paint(LiveCapture.snapshot()); }
    private void paint(LiveCapture.Snapshot snapshot) throws Exception {
        Method paint=overlay.getClass().getDeclaredMethod("paint",LiveCapture.Snapshot.class);
        paint.setAccessible(true);
        paint.invoke(overlay,snapshot);
    }
    private TextView part(String name) throws Exception { return field(overlay,name,TextView.class); }
    private static String value(TextView v) {return v.getText().toString();}
    private Intent dispatched(String expected) {
        Intent intent=shadowOf(app).getNextStartedService();
        assertNotNull("Service command "+expected,intent);
        assertEquals(expected,intent.getAction());
        assertEquals(RecallService.class.getName(),intent.getComponent().getClassName());
        return intent;
    }
    @Test public void sessionButtonsAreBoundToDisplayedRecordingAndMicLossOverridesClock() throws Exception {
        openOverlay(false,true);
        TextView toggle=part("sessionToggle");
        assertEquals("Start session",value(toggle));
        toggle.performClick();
        dispatched(RecallService.BEGIN_LONG);
        String first=UUID.randomUUID().toString();
        LongRecording.active=first;
        LongRecording.duration=127000;
        LongRecording.marks=2;
        ready();paint();
        assertTrue(value(part("sessionSummary")).contains("2:07"));
        assertTrue(value(part("sessionSummary")).contains("2 markers"));
        TextView mark=part("sessionMark");
        assertEquals(View.VISIBLE,mark.getVisibility());
        mark.performClick();
        assertEquals(first,dispatched(RecallService.MARK_LONG).getStringExtra("longId"));
        assertEquals("Finish session",value(toggle));
        toggle.performClick();
        assertEquals(first,dispatched(RecallService.FINISH_LONG).getStringExtra("longId"));
        LongRecording.active=UUID.randomUUID().toString();
        toggle.performClick();
        mark.performClick();
        assertNull("Stale controls cannot alter a different recording",shadowOf(app).getNextStartedService());
        LiveCapture.saved(123,"Forge word","Explicit keep");ready();paint();
        assertTrue(value(part("status")).contains("saved"));
        assertTrue(value(part("latest")).contains("#123"));
        LiveCapture.waitFor("Microphone used by another app");paint();
        assertTrue(value(part("status")).contains("microphone"));
        assertTrue(value(part("sessionSummary")).contains("2:07"));
        assertFalse(mark.isEnabled());
        assertFalse(part("keep").isEnabled());
    }
    @Test public void typedRewindUsesSelectedCategoryBeforeAReceiptExists() throws Exception {
        openOverlay(false,false);
        assertNull("Hiding words must also hide inferred topic",field(overlay,"contextCue",TextView.class));
        String[] labels={"Forge word","Poem line","Project decision"};
        String[] actions={RecallService.KEEP_WORD,RecallService.KEEP_LINE,RecallService.KEEP_DECISION};
        View root=field(overlay,"root",View.class);
        for(int i=0;i<labels.length;i++) {
            ready();paint();
            part("saveAs").performClick();
            TextView category=find(root,labels[i]);
            assertNotNull(category);
            assertEquals(View.VISIBLE,((View)category.getParent()).getVisibility());
            category.performClick();
            dispatched(actions[i]);
            assertEquals(View.GONE,((View)category.getParent()).getVisibility());
            assertEquals(View.GONE,part("latest").getVisibility());
        }
        assertEquals(0,LiveCapture.snapshot().savedCount);
    }
    private SelectiveEngine choiceEngine(java.util.List<SelectiveEngine.Clip> saved){
        return new SelectiveEngine(new SelectiveEngine.Listener(){
            public void onClip(SelectiveEngine.Clip clip){saved.add(clip);}
            public void onPauseRequested(){}
            public void onChoiceState(SelectiveEngine.SaveChoice choice){RecallService.saveChoice=choice;}
        });
    }
    private String ask(SelectiveEngine engine){
        short[] audio=new short[SelectiveEngine.RATE*3];java.util.Arrays.fill(audio,(short)10);
        engine.pcm(audio,audio.length);engine.text("hey recall save as",true);
        assertEquals(8,engine.choiceState().seconds);
        return engine.choiceState().token;
    }
    @Test public void heldRewindButtonsUseTheSameSerializedRequestExactlyOnce() throws Exception {
        openOverlay(false,false);
        java.util.List<SelectiveEngine.Clip> clips=new java.util.ArrayList<>();
        SelectiveEngine engine=choiceEngine(clips);
        RecallService service=Robolectric.buildService(RecallService.class).get();
        ReflectionHelpers.setField(service,"worker",new Thread());
        ReflectionHelpers.setField(service,"capturing",true);
        java.util.Queue<?> queued=field(service,"commands",java.util.Queue.class);
        String[] answers={"Word","Line","Decision","Cancel"};
        String[] expected={SelectiveEngine.FORGE,SelectiveEngine.POETRY,SelectiveEngine.DECISION};
        TextView prior=null;
        for(int i=0;i<answers.length;i++){
            String token=ask(engine);ready();paint();
            View root=field(overlay,"root",View.class);
            TextView button=find(root,answers[i]);assertTrue(button.isShown());
            assertTrue(button.getContentDescription().toString().contains("held"));
            assertFalse(part("keep").isEnabled());assertFalse(part("saveAs").isEnabled());
            if(prior!=null){prior.performClick();assertNull("Old view cannot file a replacement question",shadowOf(app).getNextStartedService());}
            engine.pcm(new short[16000],16000);ready();paint();
            assertEquals("File held audio · 7s",value(part("status")));
            button.performClick();
            Intent intent=dispatched(RecallService.ANSWER_CHOICE);
            assertEquals(token,intent.getStringExtra("choiceToken"));
            assertEquals(answers[i].toLowerCase(java.util.Locale.ROOT),intent.getStringExtra("choiceAnswer"));
            button.performClick();assertNull("Double tap dispatched a second request",shadowOf(app).getNextStartedService());
            service.onStartCommand(intent,0,i+1);String cmd=(String)queued.poll();
            assertNotNull(cmd);assertTrue(RecallService.applyChoice(engine,cmd));assertFalse(RecallService.applyChoice(engine,cmd));
            service.onStartCommand(intent,0,i+2);assertNull("Receiver accepted a closed prompt",queued.poll());
            assertEquals(Math.min(i+1,3),clips.size());
            if(i<3){
                SelectiveEngine.Clip clip=clips.get(i);assertTrue(clip.fromSpokenChoice);assertEquals(expected[i],clip.category);
                assertEquals("The following silent second must be excluded",10,clip.audio[clip.audio.length-1]);
            }
            prior=button;ready();paint();assertFalse(button.isShown());assertTrue(part("keep").isEnabled());
        }
    }
    @Test public void miniQuietChoiceReturnsToItsOriginalSizeOnExpiryOrGap() throws Exception {
        openOverlay(true,false);
        java.util.List<SelectiveEngine.Clip> clips=new java.util.ArrayList<>();
        SelectiveEngine engine=choiceEngine(clips);
        String first=ask(engine);ready();paint();
        TextView header=part("status");assertEquals("Save as · 8s",value(header));
        View readout=(View)header.getParent();assertTrue(readout.getContentDescription().toString().contains("held"));
        assertTrue(value(part("caption")).contains("Tap to file"));assertFalse(readout.isLongClickable());
        readout.performClick();assertTrue((Boolean)ReflectionHelpers.getField(overlay,"expanded"));
        assertFalse(RecallOverlay.options(app).getBoolean("expanded",false));
        View button=find(field(overlay,"root",View.class),"Line");assertTrue(button.isShown());
        engine.cueAudio(16000);engine.pcm(new short[16000],16000);assertEquals(8,engine.choiceState().seconds);
        engine.pcm(new short[8*16000+1],8*16000+1);ready();paint();
        assertFalse((Boolean)ReflectionHelpers.getField(overlay,"expanded"));assertTrue((Boolean)ReflectionHelpers.getField(overlay,"ultra"));
        assertFalse(engine.answerChoice(first,"line"));button.performClick();assertNull(shadowOf(app).getNextStartedService());
        String second=ask(engine);ready();paint();((View)part("status").getParent()).performClick();
        engine.closeChoice(null);LiveCapture.waitFor("Microphone gap");paint();
        assertEquals("Mic busy",value(part("status")));assertTrue((Boolean)ReflectionHelpers.getField(overlay,"ultra"));
        assertFalse(engine.answerChoice(second,"word"));assertTrue(clips.isEmpty());
    }
    @Test public void mediumAndMoreRespectReplacementPromptsAndExplicitPanelSize() throws Exception {
        openOverlay(false,false);
        Method size=overlay.getClass().getDeclaredMethod("setSize",boolean.class,boolean.class);size.setAccessible(true);
        size.invoke(overlay,false,false);
        java.util.List<SelectiveEngine.Clip> clips=new java.util.ArrayList<>();
        SelectiveEngine engine=choiceEngine(clips);
        String old=ask(engine);ready();paint();
        assertEquals("Save as · 8s",value(part("status")));
        ((View)part("status").getParent()).performClick();
        TextView oldButton=find(field(overlay,"root",View.class),"Line");
        assertTrue(oldButton.isShown());String oldCommand=RecallService.ANSWER_CHOICE+old+"/line";
        String current=ask(engine);ready();paint();
        assertFalse((Boolean)ReflectionHelpers.getField(overlay,"expanded"));assertFalse((Boolean)ReflectionHelpers.getField(overlay,"ultra"));
        assertFalse(RecallService.applyChoice(engine,oldCommand));
        oldButton.performClick();assertNull(shadowOf(app).getNextStartedService());
        size.invoke(overlay,false,true);
        View root=field(overlay,"root",View.class);
        find(root,"More").performClick();
        View updated=field(overlay,"root",View.class);
        TextView held=find(updated,"Decision");assertTrue(held.isShown());
        assertEquals("Choice row must stay outside the scrolling More controls",
            updated,((View)held.getParent()).getParent());
        engine.answerChoice(current,"cancel");ready();paint();
        assertTrue((Boolean)ReflectionHelpers.getField(overlay,"expanded"));
        assertTrue(find(updated,"Voices").isShown());assertFalse(held.isShown());
        assertTrue(clips.isEmpty());
        String last=ask(engine);ready();paint();
        find(updated,"Decision").performClick();dispatched(RecallService.ANSWER_CHOICE);
        // The final spoken answer may arrive before the microphone worker consumes the tap.
        engine.text("line",true);
        assertFalse(RecallService.applyChoice(engine,RecallService.ANSWER_CHOICE+last+"/decision"));
        assertEquals(1,clips.size());assertEquals(SelectiveEngine.POETRY,clips.get(0).category);
    }
    @Test public void writingSetupsRestoreTheFirstPreferencesAndPreservePrivacyChanges() {
        android.content.SharedPreferences options=RecallOverlay.options(app);
        options.edit().putBoolean("captions",false).putBoolean("enabled",false)
                .putInt("holdSave",3).putString("miniFocus","scene").commit();
        RecallOverlay.applyWritingSetup(app,0);
        assertEquals(2,RecallOverlay.holdChoice(app));
        assertEquals(6,options.getInt("captionLines",-1));
        assertEquals("buffer",options.getString("miniFocus",""));
        assertFalse(options.getBoolean("captions",true));
        assertFalse(RecallOverlay.enabled(app));
        options.edit().putBoolean("fullDetail",false).commit();
        RecallOverlay.applyWritingSetup(app,1);
        assertEquals(1,RecallOverlay.holdChoice(app));
        assertEquals("stats",options.getString("miniFocus",""));
        assertEquals(4,options.getInt("captionLines",-1));
        RecallOverlay.restoreWritingSetup(app);
        assertEquals(3,RecallOverlay.holdChoice(app));
        assertEquals("scene",options.getString("miniFocus",""));
        assertFalse("Restoring defaults must restore an originally missing option",options.contains("captionLines"));
        assertFalse(options.getBoolean("captions",true));
        assertFalse(options.getBoolean("fullDetail",true));
        options.edit().putInt("holdSave",0).commit();
        RecallOverlay.restoreWritingSetup(app);
        assertEquals(0,RecallOverlay.holdChoice(app));
    }
    @Test public void miniClockKeepsCountingDuringSaveThenReturnsToMeasuredRewind() throws Exception {
        openOverlay(true,false);
        LiveCapture.saved(13,"Recent audio","On request");
        long t=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        long first=-1;
        for(int i=1;i<=3;i+=2){
            long at=t+i*1000L;
            LiveCapture.meterAt(new short[1600],1600,at);
            LiveCapture.Snapshot s=LiveCapture.at(at);
            paint(s);
            assertEquals(LongRecording.time(s.sessionSeconds*1000)+" · Saved",value(part("status")));
            if(first<0)first=s.sessionSeconds;
            else assertEquals(2,s.sessionSeconds-first);
        }
        for(int i=10;i<=12;i+=2){
            long at=t+i*1000L;
            LiveCapture.buffered(i);
            LiveCapture.meterAt(new short[1600],1600,at);
            LiveCapture.Snapshot s=LiveCapture.at(at);
            paint(s);
            assertEquals(LongRecording.time(s.sessionSeconds*1000)+" · ↶"+i+"s",value(part("status")));
        }
    }
    @Test public void micCheckShowsLiveMeasurementsAndDoesNotChangeCaptureChoices() throws Exception {
        android.content.SharedPreferences speech=app.getSharedPreferences("recall-speech",0);
        boolean journal=JournalStore.enabled(app),archive=ArchiveStore.enabled(app),sync=ArchiveSync.auto(app);
        ReflectionHelpers.setStaticField(RecallService.class,"running",true);
        ReflectionHelpers.setStaticField(RecallService.class,"inputRoute","USB fixture");
        ActivityController<CaptureReviewActivity> controller=Robolectric.buildActivity(CaptureReviewActivity.class,
                new Intent(app,CaptureReviewActivity.class).putExtra("micCheck",true)).setup();
        try{
            CaptureReviewActivity a=controller.get();
            View root=a.getWindow().getDecorView();
            assertNotNull(find(root,"Microphone check"));
            android.widget.Switch boost=(android.widget.Switch)find(root,"Boost quiet speech for transcription");
            android.widget.Switch reach=(android.widget.Switch)find(root,"Extended reach for very quiet speech");
            assertTrue(boost.isChecked());assertFalse(reach.isChecked());
            assertFalse(speech.contains("extendedReach"));
            LiveCapture.micSignal="Input −32 dBFS · Estimated room −60 dBFS · Decoder 1.0×";
            ready();invoke(a,"paintLive");
            String current=value(field(a,"micCheck",TextView.class));
            assertTrue(current,current.contains("USB fixture") && current.contains("−32 dBFS"));
            LiveCapture.waitFor("Mic occupied");invoke(a,"paintLive");
            current=value(field(a,"micCheck",TextView.class));
            assertTrue(current,current.contains("Waiting"));
            assertFalse(current.contains("USB fixture") || current.contains("dBFS"));
            reach.performClick();assertTrue(speech.getBoolean("extendedReach",false));
            boost.performClick();assertFalse(reach.isEnabled());
            assertEquals(journal,JournalStore.enabled(app));assertEquals(archive,ArchiveStore.enabled(app));assertEquals(sync,ArchiveSync.auto(app));
        }finally{
            ReflectionHelpers.setStaticField(RecallService.class,"inputRoute","");
            controller.pause().stop().destroy();
        }
    }
    @Test public void miniHoldUsesPersistedChoiceAndActualRewindSeconds() throws Exception {
        RecallOverlay.options(app).edit().putInt("holdSave",2).commit();
        openOverlay(true,false);
        LiveCapture.buffered(17);ready();paint();
        TextView status=part("status");
        assertTrue(value(status),value(status).contains("17"));
        View readout=(View)status.getParent();
        assertTrue(readout.performLongClick());
        dispatched(RecallService.KEEP_LINE);
        LiveCapture.waitFor("Microphone busy");paint();
        assertTrue(readout.performLongClick());
        assertNull(shadowOf(app).getNextStartedService());
    }
    @Test public void longSessionNotificationKeepsMicLossVisibleAndSpeechPrivate() throws Exception {
        RecallService service=Robolectric.buildService(RecallService.class).create().get();
        ReflectionHelpers.setField(service,"capturing",true);
        LongRecording.active=UUID.randomUUID().toString();
        LongRecording.duration=93000;LongRecording.marks=3;
        LiveCapture.attributed("my unpublished line",1);ready();
        Method build=RecallService.class.getDeclaredMethod("notification",String.class);build.setAccessible(true);
        Notification n=(Notification)build.invoke(service,"Listening");
        String details=n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString();
        assertFalse(details.contains("my unpublished line"));
        assertTrue(details,details.contains("3 markers"));
        LiveCapture.waitFor("Waiting for microphone");
        n=(Notification)build.invoke(service,"Waiting");
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().toLowerCase().contains("microphone"));
    }
    @Test public void labeledMarkersUseRewindLeadInAndPersistExactAudioPositions() throws Exception {
        try(ArchiveStore db=new ArchiveStore(app)){
            LongRecording recorder=new LongRecording(app,db);
            recorder.begin();
            String id=LongRecording.active;
            assertFalse(LongRecording.message,id.isEmpty());
            // Set the elapsed audio position without depending on a native hardware encoder.
            try{
                LongRecording.duration=2000;
                assertTrue(recorder.mark(SelectiveEngine.FORGE,8000));
                LongRecording.duration=48000;
                assertTrue(recorder.mark(SelectiveEngine.POETRY,8000));
                assertTrue(recorder.mark("",0));
                try(Cursor c=db.getReadableDatabase().rawQuery(
                        "SELECT ms,label FROM long_marks WHERE session=? ORDER BY rowid",new String[]{id})){
                    assertTrue(c.moveToNext());assertEquals(0,c.getLong(0));assertEquals(SelectiveEngine.FORGE,c.getString(1));
                    assertTrue(c.moveToNext());assertEquals(40000,c.getLong(0));assertEquals(SelectiveEngine.POETRY,c.getString(1));
                    assertTrue(c.moveToNext());assertEquals(48000,c.getLong(0));assertEquals("Marker 3",c.getString(1));
                    assertFalse(c.moveToNext());
                }
            }finally{
                field(recorder,"encoder",ArchiveRecorder.Encoder.class).close();
            }
        }
    }
    @Test public void captionsDistinguishInterimWordsAndResumeWithoutRevealingEarlierSpeech() throws Exception {
        RecallOverlay.options(app).edit().putInt("captionLines",6).commit();
        openOverlay(false,true);
        LiveCapture.words("the paper boat",true);
        LiveCapture.words("is still",false);
        ready();paint();
        assertEquals(6,part("caption").getMaxLines());
        assertEquals("Heard · the paper boat\nLive · is still",value(part("caption")));
        for(int i=1;i<=7;i++)LiveCapture.words("line "+i,true);
        ready();paint();
        assertEquals("Heard · line 2\nline 3\nline 4\nline 5\nline 6\nline 7",value(part("caption")));
        LiveCapture.words("next line",false);paint();
        assertEquals("Heard · line 3\nline 4\nline 5\nline 6\nline 7\nLive · next line",value(part("caption")));
        LiveCapture.waitFor("Another app is using the microphone");paint();
        assertFalse(value(part("caption")).contains("line 7"));
        LiveCapture.resume("Listening");
        ready();paint();
        LiveCapture.words("a different thought",false);paint();
        assertEquals("Live · a different thought",value(part("caption")));
    }
    @Test public void teachingCoinedSpellingRequiresConfirmationAndPreservesOriginalJournal() throws Exception {
        String raw="I call it paper lantern";
        String corrected="I call it Paperlantern";
        JournalStore.setEnabled(app,true);
        long epoch=JournalStore.epoch(app);
        long id;
        try(JournalStore db=new JournalStore(app)){
            id=db.begin(epoch);
            assertTrue(db.append(id,epoch,raw,false,0));db.finish(id,"Finished");
        }
        ActivityController<FullJournalActivity> controller=Robolectric.buildActivity(FullJournalActivity.class).setup();
        try{
            TextView transcript=field(controller.get(),"transcript",TextView.class);
            for(int i=0;i<150 && !value(transcript).toLowerCase().contains("paper lantern");i++){
                shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            assertTrue(transcript.getText() instanceof Spanned);
            Spanned source=(Spanned)transcript.getText();
            ClickableSpan[] entries=source.getSpans(0,source.length(),ClickableSpan.class);
            assertEquals(1,entries.length);
            entries[0].onClick(transcript);
            AlertDialog passage=ShadowAlertDialog.getLatestAlertDialog();
            View root=passage.getWindow().getDecorView();
            ((EditText)find(root,raw)).setText(corrected);
            TextView teach=find(root,"Teach this spelling");assertNotNull(teach);
            teach.performClick();
            AlertDialog suggestion=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(find(suggestion.getWindow().getDecorView(),"paper lantern"));
            assertNotNull(find(suggestion.getWindow().getDecorView(),"Paperlantern"));
            suggestion.getButton(-2).performClick();
            assertTrue(SpeechContext.entries(app.getSharedPreferences("recall-speech",0)).isEmpty());
            teach.performClick();
            suggestion=ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Save spelling",value(suggestion.getButton(-1)));
            suggestion.getButton(-1).performClick();
            java.util.List<SpeechContext.Entry> rules=SpeechContext.entries(app.getSharedPreferences("recall-speech",0));
            assertEquals(1,rules.size());
            assertEquals("paper lantern",rules.get(0).heard);
            assertEquals("Paperlantern",rules.get(0).spelling);
            try(JournalStore db=new JournalStore(app);Cursor r=db.getReadableDatabase().rawQuery(
                    "SELECT text, original FROM lines WHERE session=? AND is_partial=0",new String[]{Long.toString(id)})){
                assertTrue(r.moveToFirst());
                assertEquals(raw,r.getString(0));
                assertTrue("Teaching alone must preserve journal bytes",r.isNull(1));
            }
        }finally{controller.pause().stop().destroy();}
    }
}
