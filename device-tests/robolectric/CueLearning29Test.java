package org.totalrecall.listening;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,35},manifest=Config.NONE)
public class CueLearning29Test {
    private Context app;
    private static final String DB="recall-listening.db";
    private static final String CUE="copper flickers purple";
    private static final String EARLIER="groceries apricots shelves";

    @Before public void setUp(){
        app=RuntimeEnvironment.getApplication();
        app.deleteDatabase(DB);
        ReflectionHelpers.setStaticField(org.continuity.recall.ListenPolicy.class,"paused",Boolean.FALSE);
    }

    @Test public void populatedVersionThreeDoesNotGuessWhereTheMarkerWas(){
        try(SQLiteDatabase old=app.openOrCreateDatabase(DB,Context.MODE_PRIVATE,null)){
            old.execSQL("CREATE TABLE clips(id INTEGER PRIMARY KEY AUTOINCREMENT,uid TEXT NOT NULL UNIQUE,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,title TEXT NOT NULL,reason TEXT NOT NULL,transcript TEXT NOT NULL,kind TEXT NOT NULL,feedback TEXT NOT NULL DEFAULT 'none',note TEXT NOT NULL DEFAULT '',correction TEXT NOT NULL DEFAULT '',sha256 TEXT NOT NULL,parent_id TEXT NOT NULL DEFAULT '',start_sample INTEGER NOT NULL DEFAULT 0,end_sample INTEGER NOT NULL DEFAULT 0)");
            old.execSQL("CREATE INDEX clips_start ON clips(start_ms DESC)");
            old.execSQL("CREATE TABLE deletions(uid TEXT PRIMARY KEY)");
            old.execSQL("CREATE TABLE gaps(id INTEGER PRIMARY KEY,start_ms INTEGER NOT NULL,detail TEXT NOT NULL)");
            old.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            old.execSQL("INSERT INTO metadata VALUES('device','original-device')");
            old.execSQL("INSERT INTO clips(uid,start_ms,end_ms,title,reason,transcript,kind,sha256) VALUES('archived',1,2,'Saved title',?,?,?,'oldhash')",
                new Object[]{"You chose to keep this as "+SelectiveEngine.POETRY+".",EARLIER,SelectiveEngine.POETRY});
            old.setVersion(3);
        }
        try(Store store=new Store(app)){
            assertEquals(4,store.getReadableDatabase().getVersion());
            assertEquals("Saved title",store.get(1).title);
            assertNull(store.get(1).cueText);
            assertEquals(EARLIER,store.get(1).transcript);
            assertTrue(store.examples().isEmpty());
            store.feedback(1,"keep");
            assertTrue(store.examples().get(0).terms.contains("groceries"));
        }
    }

    private static List<SelectiveEngine.Clip> labeledClips(){
        List<SelectiveEngine.Clip> clips=new ArrayList<>();
        SelectiveEngine engine=new SelectiveEngine(new SelectiveEngine.Listener(){
            public void onClip(SelectiveEngine.Clip clip){if(clip.labeledRewind)clips.add(clip);}
            public void onPauseRequested(){}
        },System.currentTimeMillis()-60000);
        short[] frames=new short[SelectiveEngine.RATE];
        engine.pcm(frames,frames.length);engine.text(EARLIER,true);
        for(int i=0;i<14;i++)engine.pcm(frames,frames.length);
        engine.text(CUE,true);engine.keepAs(SelectiveEngine.POETRY);
        for(int i=0;i<14;i++)engine.pcm(frames,frames.length);
        engine.keepAs(SelectiveEngine.FORGE);
        return clips;
    }

    @Test public void onlyMarkerWordsTrainAndFeedbackCanReverseTheExample() throws Exception {
        List<SelectiveEngine.Clip> marks=labeledClips();
        try(Store store=new Store(app)){
            long before=Store.feedbackRevision();
            long id=store.save(marks.get(0)),empty=store.save(marks.get(1));
            assertTrue(Store.feedbackRevision()>before);
            assertTrue(store.get(id).transcript.contains(EARLIER));
            assertEquals(CUE,store.get(id).cueText);
            assertEquals("",store.get(empty).cueText);
            store.edit(empty,"Silent marker","","Unrelated correction from the whole clip");
            long beforeAuto=Store.feedbackRevision();
            long auto=store.save(new SelectiveEngine.Clip(new short[1600],"tangerine noodles",SelectiveEngine.POETRY,
                "Automatic detection",System.currentTimeMillis()));
            store.getWritableDatabase().execSQL("UPDATE clips SET cue_text='imported metadata' WHERE id=?",new Object[]{auto});
            assertEquals(beforeAuto,Store.feedbackRevision());
            List<SelectiveEngine.Example> examples=store.examples();
            assertEquals(1,examples.size());assertTrue(examples.get(0).positive);
            assertTrue(examples.get(0).terms.containsAll(Arrays.asList("copper","flickers","purple")));
            assertFalse(examples.get(0).terms.contains("groceries"));
            store.feedback(id,"skip");
            assertTrue(Store.feedbackRevision()>beforeAuto);
            assertEquals(1,store.examples().size());assertFalse(store.examples().get(0).positive);
        }
    }

    private static byte[] changeCue(byte[] archive,String value) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipInputStream source=new ZipInputStream(new ByteArrayInputStream(archive));ZipOutputStream target=new ZipOutputStream(out)){
            for(ZipEntry entry;(entry=source.getNextEntry())!=null;){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[32768];
                for(int n;(n=source.read(block))!=-1;)bytes.write(block,0,n);
                byte[] content=bytes.toByteArray();
                if(entry.getName().equals("index.json")){
                    JSONObject doc=new JSONObject(new String(content,StandardCharsets.UTF_8));
                    JSONObject row=doc.getJSONArray("clips").getJSONObject(0);
                    if(value==null)row.remove("cueText");else row.put("cueText",value);
                    content=doc.toString().getBytes(StandardCharsets.UTF_8);
                }
                target.putNextEntry(new ZipEntry(entry.getName()));target.write(content);target.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test public void zipRestoresCueAndBackfillsOnlyMissingEvidence() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        String uid,digest;
        try(Store store=new Store(app)){
            long id=store.save(labeledClips().get(0));Store.Moment original=store.get(id);
            uid=original.uid;digest=original.sha256;
            store.exportZip(out,Collections.singletonList(id));
            assertTrue(original.audio.delete());
        }
        assertTrue(app.deleteDatabase(DB));
        byte[] zip=out.toByteArray(),older=changeCue(zip,null);
        try(Store store=new Store(app)){
            store.importZip(new ByteArrayInputStream(older));
            long id=store.search("").get(0).id;
            assertNull(store.get(id).cueText);assertTrue(store.examples().isEmpty());
            store.importZip(new ByteArrayInputStream(zip));
            assertEquals(CUE,store.get(id).cueText);
            store.importZip(new ByteArrayInputStream(older));
            assertEquals(CUE,store.get(id).cueText);
            try {store.importZip(new ByteArrayInputStream(changeCue(zip,"conflicting marker")));fail("accepted changed marker wording");}
            catch(IOException expected){assertTrue(expected.getMessage().contains("marker wording"));}
            assertEquals(1,store.search("").size());assertEquals(uid,store.get(id).uid);
            assertEquals(digest,store.get(id).sha256);assertEquals(CUE,store.get(id).cueText);
            assertFalse(store.examples().get(0).terms.contains("groceries"));
        }
    }
}
