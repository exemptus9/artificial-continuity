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
        super(context.getApplicationContext(),DB,null,4);app=context.getApplicationContext();
    }
    @Override public void onConfigure(SQLiteDatabase db){db.setForeignKeyConstraintsEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,start INTEGER NOT NULL,end INTEGER NOT NULL DEFAULT 0,reason TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE lines(id INTEGER PRIMARY KEY AUTOINCREMENT,session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,at INTEGER NOT NULL,text TEXT NOT NULL,is_partial INTEGER NOT NULL,voice INTEGER NOT NULL DEFAULT 0,original TEXT,check_words TEXT NOT NULL DEFAULT '')");
        createVoices(db);
        createReviewIndex(db);
        db.execSQL("CREATE INDEX lines_session ON lines(session,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to){
        if(from<1||to>4)throw new IllegalStateException("Unsupported journal schema");
        if(from<2){db.execSQL("ALTER TABLE lines ADD COLUMN voice INTEGER NOT NULL DEFAULT 0");createVoices(db);}
        if(from<3)db.execSQL("ALTER TABLE lines ADD COLUMN original TEXT");
        if(from<4){db.execSQL("ALTER TABLE lines ADD COLUMN check_words TEXT NOT NULL DEFAULT ''");createReviewIndex(db);}
    }
    private static void createReviewIndex(SQLiteDatabase db){
        db.execSQL("CREATE INDEX lines_to_check ON lines(id) WHERE check_words<>''");
    }
    void confirmWording(long id,String expected){
        ContentValues clear=new ContentValues();clear.put("check_words","");
        if(getWritableDatabase().update("lines",clear,"id=? AND text=? AND check_words<>''",new String[]{String.valueOf(id),expected})!=1)
            throw new IllegalStateException("This passage changed or was already checked. Reopen the journal.");
    }
    private static void createVoices(SQLiteDatabase db){
        db.execSQL("CREATE TABLE journal_voices(session INTEGER REFERENCES sessions(id) ON DELETE CASCADE,voice INTEGER,name TEXT NOT NULL,source TEXT NOT NULL,PRIMARY KEY(session,voice))");
    }
    static String voiceLabel(int id,String name,String source){
        String label=LiveCapture.defaultVoice(id);
        if(name!=null && !name.isEmpty())label+=" · "+name;
        if(source!=null && !source.isEmpty())label+=" · "+source;
        return label;
    }
    java.util.TreeMap<Integer,String[]> voices(long session){
        java.util.TreeMap<Integer,String[]> result=new java.util.TreeMap<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT voice FROM lines WHERE session=? AND voice>0 GROUP BY voice",new String[]{String.valueOf(session)})){
            while(c.moveToNext())result.put(c.getInt(0),new String[]{"",""});
        }
        try(Cursor c=getReadableDatabase().rawQuery("SELECT voice,name,source FROM journal_voices WHERE session=?",new String[]{String.valueOf(session)})){
            while(c.moveToNext())result.put(c.getInt(0),new String[]{c.getString(1),c.getString(2)});
        }return result;
    }
    void remember(long session,int voice,String name,String source){
        getWritableDatabase().execSQL("INSERT OR IGNORE INTO journal_voices(session,voice,name,source) SELECT ?,?,?,? WHERE EXISTS(SELECT 1 FROM sessions WHERE id=? AND end=0)",new Object[]{session,voice,name,source,session});
    }
    void nameVoice(long session,int voice,String name,String source){
        if(voice<1 || name.length()>64 || name.matches("(?s).*[\\[\\]\\p{Cntrl}].*"))throw new IllegalArgumentException("Choose a short name without brackets or line breaks.");
        if(!java.util.Arrays.asList("","Me","Another person","Media").contains(source))throw new IllegalArgumentException("Choose a listed source.");
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO journal_voices VALUES(?,?,?,?)",new Object[]{session,voice,name,source});
    }
    static final class Turn{
        final long id;final int voice;final String text;
        Turn(Cursor c){id=c.getLong(0);voice=c.getInt(1);text=c.getString(2);}
    }
    List<Turn> turns(long session,long before){
        ArrayList<Turn> turns=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,voice,text FROM lines WHERE session=? AND id<? AND is_partial<>2 ORDER BY id DESC LIMIT 31",
            new String[]{String.valueOf(session),String.valueOf(before)})){while(c.moveToNext())turns.add(new Turn(c));}
        return turns;
    }
    void correctLine(long id,String before,String after){
        String words=after.trim();
        if(words.isEmpty() || words.length()>6000 || words.contains("\n") || words.contains("\r"))throw new IllegalArgumentException("Enter one line of up to 6,000 characters.");
        synchronized(GUARD){
            try(android.database.sqlite.SQLiteStatement update=getWritableDatabase().compileStatement(
                "UPDATE lines SET original=COALESCE(original,text),text=?,check_words='' WHERE id=? AND text=? AND is_partial<>2")){
                update.bindString(1,words);update.bindLong(2,id);update.bindString(3,before);
                if(update.executeUpdateDelete()!=1)throw new IllegalStateException("This line changed or expired. Open it again.");
            }
        }
        ArchiveSync.schedule(app);
    }
    void assign(long session,long row,int voice){
        ContentValues v=new ContentValues();v.put("voice",voice);
        if(getWritableDatabase().update("lines",v,"id=? AND session=? AND is_partial<>2",new String[]{String.valueOf(row),String.valueOf(session)})!=1)
            throw new IllegalStateException("That journal line is no longer available.");
    }
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
        return append(session,expectedEpoch,value,partial,0);
    }
    boolean append(long session,long expectedEpoch,String value,boolean partial,int voice){
        return append(session,expectedEpoch,value,partial,voice,null);
    }
    boolean append(long session,long expectedEpoch,String value,boolean partial,int voice,String original){
        return append(session,expectedEpoch,value,partial,voice,original,"");
    }
    boolean append(long session,long expectedEpoch,String value,boolean partial,int voice,String original,String checkWords){
        if(value==null||value.trim().isEmpty()||session<0)return false;
        synchronized(GUARD){
            if(!enabled(app)||epoch(app)!=expectedEpoch)return false;
            SQLiteDatabase db=getWritableDatabase();
            try(Cursor c=db.rawQuery("SELECT 1 FROM sessions WHERE id=? AND end=0",new String[]{String.valueOf(session)})){
                if(!c.moveToFirst())return false;
            }
            ContentValues v=new ContentValues();v.put("session",session);v.put("at",System.currentTimeMillis());
            if(original!=null && !original.equals(value))v.put("original",original);
            if(!partial && checkWords!=null && !checkWords.isEmpty())v.put("check_words",checkWords);
            v.put("voice",voice);v.put("text",value.trim());v.put("is_partial",partial?1:0);db.insertOrThrow("lines",null,v);return true;
        }
    }
    void finish(long id,String why){if(id<0)return;synchronized(GUARD){
        getWritableDatabase().update("sessions",ending(why,System.currentTimeMillis()),"id=? AND end=0",new String[]{String.valueOf(id)});
    } ArchiveSync.schedule(app);}
    public static final class Session{
        public final long id,startMs,endMs;public final int count;public final String endReason;
        Session(Cursor c){id=c.getLong(0);startMs=c.getLong(1);endMs=c.getLong(2);count=c.getInt(3);endReason=c.getString(4);}
    }
    public List<Session> sessions(){return sessions(0);}
    List<Session> sessions(int limit){
        synchronized(GUARD){
            prune();List<Session> out=new ArrayList<>();
            try(Cursor c=getReadableDatabase().rawQuery("SELECT s.id,s.start,s.end,COUNT(l.id),s.reason FROM sessions s LEFT JOIN lines l ON s.id=l.session GROUP BY s.id ORDER BY s.start DESC,s.id DESC"+(limit>0?" LIMIT "+limit:""),null)){
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
        try(Cursor c=db.rawQuery("SELECT l.at,l.text,l.is_partial,l.voice,v.name,v.source,l.original FROM lines l LEFT JOIN journal_voices v ON l.session=v.session AND l.voice=v.voice WHERE l.session=? ORDER BY l.id",new String[]{String.valueOf(id)})){
            int bytes=0;while(c.moveToNext()){
                String speaker=voiceLabel(c.getInt(3),c.getString(4),c.getString(5));
                String text=(speaker.isEmpty()?"":"["+speaker+"] ")+c.getString(1);
                if(max>0 && bytes+text.length()>max){out.write("… Export to read the complete transcript.\n");break;}
                out.write(stamp(c.getLong(0))+"  "+(c.getInt(2)==1?"[unfinished utterance] ":c.getInt(2)==2?"[capture status] ":"")+text+"\n");
                if(max==0 && !c.isNull(6))out.write("  Originally heard · "+c.getString(6)+"\n");
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
