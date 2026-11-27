package org.totalrecall.listening;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/** Private continuous audio catalog. Only a verified Mint receipt makes a segment evictable. */
public final class ArchiveStore extends SQLiteOpenHelper {
    private static final String DB="recall-audio-archive.db",PREF="recall-audio-archive";
    private static final long MIB=1024L*1024;
    static final int SECONDS=120,RATE=16000;
    private static final Object LOCK=new Object();
    private static final String COLS="id,start,end,duration,size,bitrate,sha,receipt,state,until_ms";
    private final Context app;
    public ArchiveStore(Context c){super(c.getApplicationContext(),DB,null,3);app=c.getApplicationContext();}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE segments(id TEXT PRIMARY KEY,start INTEGER NOT NULL,end INTEGER NOT NULL DEFAULT 0,duration INTEGER NOT NULL DEFAULT 0,size INTEGER NOT NULL DEFAULT 0,bitrate INTEGER NOT NULL,sha TEXT NOT NULL DEFAULT '',receipt TEXT NOT NULL DEFAULT '',state TEXT NOT NULL,until_ms INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX segments_state_start ON segments(state,start)");
        createOmissions(db);LongRecording.schema(db);
        db.execSQL("CREATE TABLE gaps(id INTEGER PRIMARY KEY AUTOINCREMENT,start INTEGER NOT NULL,end INTEGER NOT NULL DEFAULT 0,reason TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to){if(from<1||to!=3)throw new IllegalStateException("Unknown archive schema");if(from<2)createOmissions(db);LongRecording.schema(db);}
    private static void createOmissions(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS quiet_blocks(id TEXT PRIMARY KEY,start INTEGER,end INTEGER,bytes INTEGER)");
    }
    static boolean prune(Context c){return prefs(c).getBoolean("prune",false);}
    static void setPrune(Context c,boolean on){commit(prefs(c).edit().putBoolean("prune",on).putLong("epoch",epoch(c)+1));}
    String savings(){
        try(Cursor cur=getReadableDatabase().rawQuery("SELECT count(*),coalesce(sum(end-start),0),coalesce(sum(bytes),0) FROM quiet_blocks",null)){
            cur.moveToFirst();return cur.getLong(0)+" blocks omitted · "+cur.getLong(1)/1000+" s · "+cur.getLong(2)/1024+" KiB saved";
        }
    }
    void omitQuiet(Stage stage,long end)throws IOException{synchronized(LOCK){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            db.execSQL("INSERT INTO quiet_blocks VALUES(?,?,?,?)",new Object[]{stage.id,stage.start,end,stage.partial.length()});
            if(db.delete("segments","id=? AND state='staging'",arg(stage.id))!=1)throw new IOException("Recording catalog changed");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        if(!unlink(stage.partial))issue(app,"Quiet block cleanup pending");
    }}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    public static boolean enabled(Context c){return prefs(c).getBoolean("enabled",false);}
    static long epoch(Context c){return prefs(c).getLong("epoch",0);}
    public static void setEnabled(Context c,boolean on){synchronized(LOCK){
        SharedPreferences p=prefs(c);if(enabled(c)==on)return;
        commit(p.edit().putBoolean("enabled",on).putLong("epoch",epoch(c)+1));
        if(!on)LimitAlerts.local(c,false);
    }}
    public static int bitrate(Context c){return prefs(c).getInt("bitrate",32000);}
    public static void setBitrate(Context c,int value){
        if(value!=24000&&value!=32000&&value!=48000)throw new IllegalArgumentException("Choose 24, 32 or 48 kbps");
        commit(prefs(c).edit().putInt("bitrate",value));
    }
    public static int reserveMiB(Context c){return prefs(c).getInt("reserve",512);}
    public static void setReserveMiB(Context c,int value){setLimit(c,"reserve",value,16);}
    public static int budgetMiB(Context c){return prefs(c).getInt("budget",2048);}
    public static void setBudgetMiB(Context c,int value){setLimit(c,"budget",value,1);}
    private static void setLimit(Context c,String key,int value,int min){
        if(value<min||value>1000000)throw new IllegalArgumentException("Value outside supported storage range");
        commit(prefs(c).edit().putInt(key,value));
    }
    public static boolean evictSynced(Context c){return prefs(c).getBoolean("evict",false);}
    public static void setEvictSynced(Context c,boolean value){commit(prefs(c).edit().putBoolean("evict",value));}
    private static void commit(SharedPreferences.Editor e){if(!e.commit())throw new IllegalStateException("Could not save archive settings");}
    static void issue(Context c,String reason){prefs(c).edit().putString("issue",reason).apply();}
    public static long retainUntil(long endMs){return Instant.ofEpochMilli(endMs).atZone(ZoneOffset.UTC).plusYears(7).toInstant().toEpochMilli();}
    private File directory(){return new File(app.getFilesDir(),"audio-archive");}
    private File file(String id,boolean partial){
        if(id==null||!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("Invalid segment ID");
        return new File(directory(),id+(partial?".part":".aac"));
    }
    public static final class Segment {
        public final String id,sha256,receipt,status,format="aac-adts";
        public final long startMs,endMs,durationMs,bytes,retainUntilMs;
        public final int bitrate;
        public final boolean local;
        Segment(Cursor c){id=c.getString(0);startMs=c.getLong(1);endMs=c.getLong(2);durationMs=c.getLong(3);bytes=c.getLong(4);
            bitrate=c.getInt(5);sha256=c.getString(6);receipt=c.getString(7);status=c.getString(8);retainUntilMs=c.getLong(9);
            local="pending".equals(status)||"synced".equals(status);}
    }
    private List<Segment> list(String filter,int limit,int offset){
        int bounded=Math.max(1,Math.min(2000,limit));
        List<Segment> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT "+COLS+" FROM segments WHERE "+filter+
                " ORDER BY start "+("state='pending'".equals(filter)?"ASC":"DESC")+",id LIMIT ? OFFSET ?",
                new String[]{Integer.toString(bounded),Integer.toString(Math.max(0,offset))})){
            while(c.moveToNext())out.add(new Segment(c));
        }return out;
    }
    public List<Segment> pending(int limit){return pending(limit,0);}
    public List<Segment> pending(int limit,int offset){synchronized(LOCK){return list("state='pending'",limit,offset);}}
    public List<Segment> segments(int limit){return segments(limit,0);}
    public List<Segment> segments(int limit,int offset){synchronized(LOCK){return list("state<>'staging'",limit,offset);}}
    public Segment get(String id){synchronized(LOCK){try(Cursor c=getReadableDatabase().rawQuery(
        "SELECT "+COLS+" FROM segments WHERE id=?",new String[]{id})){return c.moveToFirst()?new Segment(c):null;}}}
    public static final class Summary{
        public final long localBytes,pendingBytes,localCount,pendingCount,syncedCount,evictedCount,gapCount;
        public final String lastIssue;
        Summary(long[] counts,String issue){localBytes=counts[0];pendingBytes=counts[1];localCount=counts[2];pendingCount=counts[3];
            syncedCount=counts[4];evictedCount=counts[5];gapCount=counts[6];lastIssue=issue;}
    }
    public Summary summary(){synchronized(LOCK){
        long[] n=new long[7];
        try(Cursor c=getReadableDatabase().rawQuery("SELECT state,COUNT(*),SUM(size) FROM segments GROUP BY state",null)){
            while(c.moveToNext()){
                String state=c.getString(0);long rows=c.getLong(1),size=c.getLong(2);
                if(state.equals("pending")||state.equals("synced")){n[0]+=size;n[2]+=rows;}
                if(state.equals("pending")){n[1]=size;n[3]=rows;}
                if(state.equals("synced")||state.equals("evicted"))n[4]+=rows;
                if(state.equals("evicted"))n[5]=rows;
            }
        }
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM gaps",null)){c.moveToFirst();n[6]=c.getLong(0);}
        return new Summary(n,prefs(app).getString("issue",""));
    }}
    private static String[] arg(String id){return new String[]{id};}
    private static String digest(InputStream source)throws IOException{
        MessageDigest hash;try{hash=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
        byte[] buf=new byte[32768];int count;
        while((count=source.read(buf))!=-1)hash.update(buf,0,count);
        char[] hex="0123456789abcdef".toCharArray();StringBuilder out=new StringBuilder(64);
        for(byte b:hash.digest()){out.append(hex[(b>>4)&15]).append(hex[b&15]);}return out.toString();
    }
    public FileInputStream openForPlayback(String id)throws IOException{synchronized(LOCK){
        Segment row=get(id);if(row==null||!row.local)throw new IOException("Audio is not on this phone");
        FileInputStream in=new FileInputStream(file(id,false));
        try{
            if(in.getChannel().size()!=row.bytes||!digest(in).equals(row.sha256))throw new IOException("Audio checksum mismatch");
            in.getChannel().position(0);return in;
        }catch(IOException|RuntimeException e){in.close();throw e;}
    }}
    public InputStream open(String id)throws IOException{return openForPlayback(id);}
    public void export(String id,OutputStream target)throws IOException{
        try(InputStream in=open(id)){byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1)target.write(buf,0,n);}
        target.flush();
    }
    public boolean markSynced(String id,String hash,String receipt)throws IOException{synchronized(LOCK){
        if(receipt==null||receipt.trim().isEmpty()||receipt.length()>8192)throw new IOException("Missing or oversized Mint receipt");
        Segment row=get(id);if(row==null||!row.sha256.equals(hash))return false;
        if(row.status.equals("synced")||row.status.equals("evicted"))return true;
        if(!row.status.equals("pending"))return false;
        ContentValues values=new ContentValues();values.put("state","synced");values.put("receipt",receipt);
        return getWritableDatabase().update("segments",values,"id=? AND state='pending' AND sha=?",new String[]{id,hash})==1;
    }}
    /** Start receipt tracking for a user-selected destination. Previously evicted files remain remote-only. */
    public void requeueLocal(){synchronized(LOCK){
        getWritableDatabase().execSQL("UPDATE segments SET state='pending',receipt='' WHERE state='synced'");
    }}
    private long localBytes(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(SUM(size),0) FROM segments WHERE state IN ('synced','pending')",null)){
        c.moveToFirst();return c.getLong(0);
    }}
    private static boolean unlink(File f){return !f.exists()||f.delete();}
    public void delete(String id){synchronized(LOCK){
        Segment row=get(id);if(row==null)return;if(row.status.equals("staging"))throw new IllegalStateException("Segment is recording");
        if(!unlink(file(id,false)))throw new IllegalStateException("Unable to remove phone audio");
        if(!row.receipt.isEmpty()){
            ContentValues values=new ContentValues();values.put("state","evicted");
            getWritableDatabase().update("segments",values,"id=?",arg(id));
        }else getWritableDatabase().delete("segments","id=?",arg(id));
    }}
    /** Ensure room for a complete segment plus AAC/container variance. Pending uploads are never evicted. */
    public boolean maintain(){synchronized(LOCK){
        long extra=SECONDS*(bitrate(app)/8L+500)+32768;
        long budget=budgetMiB(app)*MIB,reserve=reserveMiB(app)*MIB;
        long used=localBytes()+LongRecording.bytes(app,this);File volume=app.getFilesDir();
        while(volume.getUsableSpace()-reserve<extra||budget-used<extra){
            if(!evictSynced(app))return false;
            Segment oldest;try(Cursor c=getWritableDatabase().rawQuery("SELECT "+COLS+" FROM segments WHERE state='synced' AND receipt<>'' ORDER BY start,id LIMIT 1",null)){
                if(!c.moveToFirst())return false;oldest=new Segment(c);
            }
            delete(oldest.id);used-=oldest.bytes;
        }
        return true;
    }}
    static final class Stage{
        final String id;final long start;final File partial;final int bitrate;
        Stage(String uid,long time,File f,int b){id=uid;start=time;partial=f;bitrate=b;}
    }
    Stage stage(long start,int b)throws IOException{synchronized(LOCK){
        if(!maintain()){LimitAlerts.local(app,true);throw new IOException("Phone archive budget or minimum free space reached");}
        LimitAlerts.local(app,false);
        File dir=directory();if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Unable to create archive directory");
        String id=UUID.randomUUID().toString();
        ContentValues v=new ContentValues();v.put("id",id);v.put("start",start);v.put("bitrate",b);v.put("state","staging");
        getWritableDatabase().insertOrThrow("segments",null,v);
        return new Stage(id,start,file(id,true),b);
    }}
    void discardEmpty(Stage stage){synchronized(LOCK){
        if((!stage.partial.exists()||stage.partial.length()==0)&&unlink(stage.partial))
            getWritableDatabase().delete("segments","id=? AND state='staging'",arg(stage.id));
    }}
    /** ADTS 7-byte headers (AAC-LC mono/16k), independently decodable after an interrupted write. */
    static long repair(File audio)throws IOException{
        try(RandomAccessFile r=new RandomAccessFile(audio,"rw")){
            long good=0,frames=0,length=r.length();byte[] h=new byte[7];
            while(length-good>=7){
                r.seek(good);r.readFully(h);
                if((h[0]&255)!=255||(h[1]&255)!=0xf1||((h[2]>>6)&3)!=1||((h[2]>>2)&15)!=8||
                    (((h[2]&1)<<2)|((h[3]>>6)&3))!=1||(h[6]&3)!=0)break;
                int size=((h[3]&3)<<11)|((h[4]&255)<<3)|((h[5]&255)>>5);
                if(size<=7||size>length-good)break;
                good+=size;frames++;
            }
            if(length!=good)r.setLength(good);
            r.getFD().sync();return frames;
        }
    }
    Segment seal(Stage stage,long samples)throws IOException{synchronized(LOCK){
        File dst=file(stage.id,false),src=dst.exists()?dst:stage.partial;
        long frames=repair(src);if(frames==0)throw new IOException("No complete encoded audio frames");
        long duration=(samples>0?Math.min(samples,frames*1024L):frames*1024L)*1000/RATE;
        if(!src.equals(dst)&&!src.renameTo(dst))throw new IOException("Archive segment could not be committed");
        String hash;try(InputStream in=new FileInputStream(dst)){hash=digest(in);}
        ContentValues v=new ContentValues();v.put("end",stage.start+duration);v.put("duration",duration);v.put("size",dst.length());
        v.put("sha",hash);v.put("state","pending");v.put("until_ms",retainUntil(stage.start+duration));
        if(getWritableDatabase().update("segments",v,"id=? AND state='staging'",arg(stage.id))!=1)throw new IOException("Archive catalog changed");
        return get(stage.id);
    }}
    long gap(long start,String why){synchronized(LOCK){
        ContentValues v=new ContentValues();v.put("start",start);v.put("reason",why);issue(app,why);
        return getWritableDatabase().insertOrThrow("gaps",null,v);
    }}
    void endGap(long id,long end){if(id<0)return;synchronized(LOCK){
        getWritableDatabase().execSQL("UPDATE gaps SET end=MAX(start,?) WHERE id=? AND end=0",new Object[]{end,id});
    }}
    public List<String> latestGaps(int limit){synchronized(LOCK){
        List<String> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT start,end,reason FROM gaps ORDER BY id DESC LIMIT ?",
                new String[]{String.valueOf(Math.max(1,Math.min(2000,limit)))})){
            while(c.moveToNext())out.add(Instant.ofEpochMilli(c.getLong(0))+" — "+(c.getLong(1)==0?"open":Instant.ofEpochMilli(c.getLong(1)).toString())+" · "+c.getString(2));
        }return out;
    }}
    void recover(){synchronized(LOCK){
        if(!app.getDatabasePath(DB).exists())return;
        long now=System.currentTimeMillis();
        getWritableDatabase().execSQL("UPDATE gaps SET end=MAX(start,?) WHERE end=0",new Object[]{now});
        File[] partials=directory().listFiles((dir,name)->name.endsWith(".part"));
        if(partials!=null)for(File f:partials){
            String id=f.getName().substring(0,f.getName().length()-5);
            try(Cursor cur=getReadableDatabase().rawQuery("SELECT 1 FROM quiet_blocks WHERE id=?",arg(id))){
                if(cur.moveToFirst())unlink(f);
            }
        }
        for(Segment row:list("state='staging'",2000,0)){
            Stage stage=new Stage(row.id,row.startMs,file(row.id,true),row.bitrate);long end=row.startMs;
            File source=file(row.id,false).isFile()?file(row.id,false):stage.partial;
            try{
                if(!source.isFile()||source.length()==0||repair(source)==0){
                    unlink(source);getWritableDatabase().delete("segments","id=? AND state='staging'",arg(row.id));
                }else end=seal(stage,-1).endMs;
            }catch(IOException e){issue(app,"Archive recovery pending: "+e.getMessage());continue;}
            endGap(gap(end,"Recording interrupted; verified audio ends at the start of this gap"),now);
        }
        // A process can die between unlinking an acknowledged file and committing its eviction.
        for(Segment row:list("state='synced'",2000,0))if(!file(row.id,false).exists())delete(row.id);
    }}
    public static void reset(Context c){synchronized(LOCK){
        if(RecallService.isListening())throw new IllegalStateException("Pause listening before resetting audio");
        try(ArchiveStore s=new ArchiveStore(c)){
            File dir=s.directory();File[] files=dir.listFiles();
            if(files!=null)for(File f:files)if(!unlink(f))throw new IllegalStateException("Unable to delete archived audio");
            if(!unlink(dir))throw new IllegalStateException("Unable to clear archive directory");
        }
        if(c.getDatabasePath(DB).exists()&&!c.deleteDatabase(DB))throw new IllegalStateException("Unable to clear archive catalog");
        commit(prefs(c).edit().clear());
    }}
}
