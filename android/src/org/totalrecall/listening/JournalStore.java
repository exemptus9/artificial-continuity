package org.totalrecall.listening;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Optional full text journal; independent of selected audio, exports and Mint transfers. */
public final class JournalStore extends SQLiteOpenHelper {
    private static final String DB="recall-journal.db",PREF="recall-journal-settings";
    private static final Object GUARD=new Object();
    private static final long DAY=86400000L;
    private static final DateTimeFormatter FORMAT=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");
    private final Context app;
    public JournalStore(Context context){
        super(context.getApplicationContext(),DB,null,1);app=context.getApplicationContext();
    }
    @Override public void onConfigure(SQLiteDatabase db){db.setForeignKeyConstraintsEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,start INTEGER NOT NULL,end INTEGER NOT NULL DEFAULT 0,reason TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE lines(id INTEGER PRIMARY KEY AUTOINCREMENT,session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,at INTEGER NOT NULL,text TEXT NOT NULL,is_partial INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX lines_session ON lines(session,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to){throw new IllegalStateException("Unsupported journal schema");}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    public static boolean enabled(Context c){return prefs(c).getBoolean("enabled",false);}
    static long epoch(Context c){return prefs(c).getLong("epoch",0);}
    public static void setEnabled(Context c,boolean value){
        synchronized(GUARD){
            SharedPreferences p=prefs(c);
            if(p.getBoolean("enabled",false)==value)return;
            if(!p.edit().putBoolean("enabled",value).putLong("epoch",p.getLong("epoch",0)+1).commit())
                throw new IllegalStateException("Could not save journal preference");
            if(!value&&c.getDatabasePath(DB).exists()){
                try(JournalStore store=new JournalStore(c)){store.closeOpen("Journal turned off",System.currentTimeMillis());}
            }
        }
    }
    public static int retentionDays(Context c){return prefs(c).getInt("retention",7);}
    public static void setRetentionDays(Context c,int days){
        if(days<0 || days>3650)throw new IllegalArgumentException("Retention must be between 0 and 3650 days");
        synchronized(GUARD){
            if(!prefs(c).edit().putInt("retention",days).commit())throw new IllegalStateException("Could not save retention");
            if(c.getDatabasePath(DB).exists())try(JournalStore s=new JournalStore(c)){s.prune();}
        }
    }
    private static ContentValues ending(String reason,long time){
        ContentValues values=new ContentValues();values.put("end",time);values.put("reason",reason);return values;
    }
    void closeOpen(String reason,long now){
        synchronized(GUARD){getWritableDatabase().update("sessions",ending(reason,now),"end=0",null);}
    }
    void recover(){
        synchronized(GUARD){
            if(!app.getDatabasePath(DB).exists())return;
            getWritableDatabase().execSQL("UPDATE sessions SET end=COALESCE((SELECT MAX(at) FROM lines WHERE session=sessions.id),start),reason='Session interrupted; last observed time shown' WHERE end=0");
            prune();
        }
    }
    void prune(){
        synchronized(GUARD){
            int days=retentionDays(app);if(days==0)return;
            long cutoff=System.currentTimeMillis()-DAY*days;
            SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
            try{
                db.delete("lines","at<?",new String[]{String.valueOf(cutoff)});
                db.delete("sessions","end>0 AND end<?",new String[]{String.valueOf(cutoff)});
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
    }
    long begin(long expectedEpoch){
        synchronized(GUARD){
            if(!enabled(app)||epoch(app)!=expectedEpoch)return -1;
            ContentValues v=new ContentValues();v.put("start",System.currentTimeMillis());
            return getWritableDatabase().insertOrThrow("sessions",null,v);
        }
    }
    void markResumed(long session,String reason){
        synchronized(GUARD){
            if(session>=0 && enabled(app)){
                ContentValues tag=new ContentValues();tag.put("session",session);tag.put("at",System.currentTimeMillis());
                tag.put("text",reason);tag.put("is_partial",2);
                try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM sessions WHERE id=? AND end=0",new String[]{String.valueOf(session)})){
                    if(c.moveToFirst())getWritableDatabase().insertOrThrow("lines",null,tag);
                }
            }
        }
    }
    boolean append(long session,long expectedEpoch,String value,boolean partial){
        if(value==null||value.trim().isEmpty()||session<0)return false;
        synchronized(GUARD){
            if(!enabled(app)||epoch(app)!=expectedEpoch)return false;
            SQLiteDatabase db=getWritableDatabase();
            try(Cursor c=db.rawQuery("SELECT 1 FROM sessions WHERE id=? AND end=0",new String[]{String.valueOf(session)})){
                if(!c.moveToFirst())return false;
            }
            ContentValues v=new ContentValues();v.put("session",session);v.put("at",System.currentTimeMillis());
            v.put("text",value.trim());v.put("is_partial",partial?1:0);db.insertOrThrow("lines",null,v);return true;
        }
    }
    void finish(long id,String why){if(id<0)return;synchronized(GUARD){
        getWritableDatabase().update("sessions",ending(why,System.currentTimeMillis()),"id=? AND end=0",new String[]{String.valueOf(id)});
    } ArchiveSync.schedule(app);}
    public static final class Session{
        public final long id,startMs,endMs;public final int count;public final String endReason;
        Session(Cursor c){id=c.getLong(0);startMs=c.getLong(1);endMs=c.getLong(2);count=c.getInt(3);endReason=c.getString(4);}
    }
    public List<Session> sessions(){
        synchronized(GUARD){
            prune();List<Session> out=new ArrayList<>();
            try(Cursor c=getReadableDatabase().rawQuery("SELECT s.id,s.start,s.end,COUNT(l.id),s.reason FROM sessions s LEFT JOIN lines l ON s.id=l.session GROUP BY s.id ORDER BY s.start DESC,s.id DESC",null)){
                while(c.moveToNext())out.add(new Session(c));
            }return out;
        }
    }
    private static String stamp(long ms){return FORMAT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));}
    private void render(long id,Writer out,int max)throws IOException{
        prune();
        SQLiteDatabase db=getReadableDatabase();
        try(Cursor c=db.rawQuery("SELECT start,end,reason FROM sessions WHERE id=?",new String[]{String.valueOf(id)})){
            if(!c.moveToFirst())throw new IOException("Journal session no longer exists");
            out.write("Total Recall transcript · "+stamp(c.getLong(0))+"\n");
            out.write(c.getLong(1)==0?"Session open\n":("Ended "+stamp(c.getLong(1))+" · "+c.getString(2)+"\n"));
            out.write("On-device speech recognition. Timestamps mark recognition time; words may be inaccurate.\n\n");
        }
        try(Cursor c=db.rawQuery("SELECT at,text,is_partial FROM lines WHERE session=? ORDER BY id",new String[]{String.valueOf(id)})){
            int bytes=0;while(c.moveToNext()){
                String text=c.getString(1);
                if(max>0 && bytes+text.length()>max){out.write("… Export to read the complete transcript.\n");break;}
                out.write(stamp(c.getLong(0))+"  "+(c.getInt(2)==1?"[unfinished utterance] ":c.getInt(2)==2?"[capture status] ":"")+text+"\n");
                bytes+=text.length()+32;
            }
        }
    }
    public String text(long id){
        try{StringWriter out=new StringWriter();render(id,out,180000);return out.toString();}
        catch(IOException e){return e.getMessage();}
    }
    public void export(long id,OutputStream target)throws IOException{
        BufferedWriter out=new BufferedWriter(new OutputStreamWriter(target,StandardCharsets.UTF_8));render(id,out,0);out.flush();
    }
    public void delete(long id){synchronized(GUARD){getWritableDatabase().delete("sessions","id=?",new String[]{String.valueOf(id)});}}
    public void clear(){synchronized(GUARD){setEnabled(app,false);getWritableDatabase().delete("sessions",null,null);}}
    public static void reset(Context c){synchronized(GUARD){
        if(RecallService.isListening())throw new IllegalStateException("Pause listening before resetting journals");
        if(c.getDatabasePath(DB).exists()&&!c.deleteDatabase(DB))throw new IllegalStateException("Unable to remove journal");
        prefs(c).edit().clear().commit();
    }}
}
