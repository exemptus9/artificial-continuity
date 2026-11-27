package org.totalrecall.listening;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,35},manifest=Config.NONE)
public class RecognitionReview30Test {
    private static JSONArray words(String text,String... weak)throws Exception{
        JSONArray rows=new JSONArray();
        for(String w:text.split(" "))rows.put(new JSONObject().put("word",w).put("conf",java.util.Arrays.asList(weak).contains(w)?.36:.96));
        return rows;
    }
    @Test public void twoAlignedReadsCanRepairTwoWeakWordsDespiteExtraWordsElsewhere()throws Exception{
        String first="keep the lion under the silver clod near dusk";
        JSONArray raw=words(first,"lion","clod");
        JSONArray left=words("please keep the line under the silver cloud near dusk");
        JSONArray right=words("keep the line under the silver cloud near dusk tonight");
        assertEquals("keep the line under the silver cloud near dusk",RecognitionReview.repair(first,raw,left,right));
        assertEquals("lion, clod",RecognitionReview.toCheck(raw));
        assertNull(RecognitionReview.repair(first,raw,words("keep the line under the silver cloud near dusk","line","cloud"),right));
    }
    @Test public void repeatedAnchorsAndReversalsAbstain() throws Exception{
        String first="the bird sang then the herd sang";
        assertNull(RecognitionReview.repair(first,words(first,"herd"),words("the bird sang then the bird sang"),words("the bird sang then the bird sang")));
        assertFalse(RecognitionReview.safeReplacement("we can leave today","we can't leave today"));
        assertFalse(RecognitionReview.safeReplacement("meet at five","meet at nine"));
        assertFalse(RecognitionReview.safeReplacement("the word is mist","the word is missed"));
        assertTrue(RecognitionReview.safeReplacement("keep the lion","keep the line"));
        assertEquals("",RecognitionReview.toCheck(new JSONArray().put(new JSONObject().put("word","silver"))));
    }
    @Test public void strongSentenceAverageCannotCoverAWeakChange() throws Exception{
        String raw="please keep the lion for tomorrow",alt="please keep this line for me tomorrow";
        assertTrue(RecognitionReview.supportedRewrite(raw,words(raw,"the","lion"),alt,words(alt)));
        assertFalse(RecognitionReview.supportedRewrite(raw,words(raw,"the","lion"),alt,words(alt,"line")));
        assertFalse(RecognitionReview.supportedRewrite(raw,words(raw),alt,words(alt)));
        assertFalse(RecognitionReview.supportedRewrite("call tomorrow",words("call tomorrow"),"call",words("call")));
        assertTrue(RecognitionReview.supportedRewrite("call tomorrow",words("call tomorrow","tomorrow"),"call",words("call")));
    }
    @Test public void confirmAndCorrectAreSeparateAndRequireAnUnchangedLine() throws Exception{
        Context app=RuntimeEnvironment.getApplication();app.deleteDatabase("recall-journal.db");
        JournalStore.setEnabled(app,true);
        try(JournalStore journal=new JournalStore(app)){
            long epoch=JournalStore.epoch(app),session=journal.begin(epoch);
            assertTrue(journal.append(session,epoch,"amber light",false,1,"amber lite","lite"));
            SQLiteDatabase db=journal.getReadableDatabase();
            long id;
            try(Cursor c=db.rawQuery("SELECT id,check_words FROM lines",null)){assertTrue(c.moveToFirst());id=c.getLong(0);assertEquals("lite",c.getString(1));}
            try{journal.confirmWording(id,"something different");fail();}catch(IllegalStateException expected){}
            journal.confirmWording(id,"amber light");
            try(Cursor c=db.rawQuery("SELECT text,original,check_words FROM lines",null)){
                c.moveToFirst();assertEquals("amber light",c.getString(0));assertEquals("amber lite",c.getString(1));assertEquals("",c.getString(2));
            }
            journal.correctLine(id,"amber light","amber lights");
            try(Cursor c=db.rawQuery("SELECT original,text FROM lines",null)){c.moveToFirst();assertEquals("amber lite",c.getString(0));assertEquals("amber lights",c.getString(1));}
            assertTrue(journal.append(session,epoch,"partial",true,0,null,"partial"));
            try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM lines WHERE check_words<>''",null)){c.moveToFirst();assertEquals(0,c.getInt(0));}
        }
    }
}
