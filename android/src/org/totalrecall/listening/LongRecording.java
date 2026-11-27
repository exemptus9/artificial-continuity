package org.totalrecall.listening;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.os.SystemClock;
import java.io.*;
import java.text.DateFormat;
import java.util.*;

/** One explicitly requested, local AAC stream. No growing PCM buffer or silence trimming. */
final class LongRecording implements AutoCloseable {
    private final Context app;
    private final ArchiveStore db;
    private ArchiveRecorder.Encoder encoder;
    private long checkAt;
    static volatile String active="",message="";
    static volatile long duration;
    static volatile int marks;
    private static final Object LIFE=new Object();
    LongRecording(Context c,ArchiveStore store){app=c;db=store;}
    static void schema(android.database.sqlite.SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS long_sessions(id TEXT PRIMARY KEY,title TEXT NOT NULL,start INTEGER NOT NULL,ms INTEGER NOT NULL DEFAULT 0,bytes INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS long_marks(session TEXT NOT NULL,ms INTEGER NOT NULL,label TEXT NOT NULL)");
    }
    static File audio(Context c,String id,boolean part){
        UUID.fromString(id);
        return new File(new File(c.getFilesDir(),"audio-archive"),"long-"+id+(part?".part":".aac"));
    }
    static String time(long ms){long sec=Math.max(0,ms)/1000;return sec>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",sec/3600,sec/60%60,sec%60):String.format(Locale.ROOT,"%d:%02d",sec/60,sec%60);}
    static void recover(Context c,ArchiveStore db){synchronized(LIFE){
        try(Cursor rows=db.getReadableDatabase().rawQuery("SELECT id FROM long_sessions WHERE status='Recording'",null)){
            while(rows.moveToNext()){
                String id=rows.getString(0);if(id.equals(active))continue;
                try{seal(c,db,id,-1,"Interrupted · recovered");}catch(IOException e){message="Session recovery pending · "+e.getMessage();}
            }
        }
    }}
    void begin(){synchronized(LIFE){
        if(!active.isEmpty())return;
        String id=UUID.randomUUID().toString();
        try{
            recover(app,db);
            if(!db.maintain()){LimitAlerts.local(app,true);throw new IOException("Phone storage limit reached");}
            File part=audio(app,id,true);File dir=part.getParentFile();
            if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Could not create session folder");
            long start=System.currentTimeMillis();
            ContentValues v=new ContentValues();v.put("id",id);v.put("title","Session · "+DateFormat.getDateTimeInstance().format(new Date(start)));v.put("start",start);v.put("status","Recording");
            db.getWritableDatabase().insertOrThrow("long_sessions",null,v);
            encoder=new ArchiveRecorder.Encoder(new ArchiveStore.Stage(id,start,part,ArchiveStore.bitrate(app)));
            duration=0;marks=0;checkAt=0;message="Recording long session";active=id;
        }catch(IOException|RuntimeException e){
            message="Session could not start · "+e.getMessage();
            try{db.getWritableDatabase().delete("long_sessions","id=? AND status='Recording'",new String[]{id});}catch(RuntimeException ignored){}
            audio(app,id,true).delete();
        }
    }}
    void accept(short[] pcm,int count){
        if(encoder==null)return;
        try{
            long now=SystemClock.elapsedRealtime();
            if(now>=checkAt){checkAt=now+2000;
                if(!db.maintain()){LimitAlerts.local(app,true);finish("Storage limit reached");return;}
            }
            encoder.write(pcm,0,count);duration=encoder.samples*1000/ArchiveStore.RATE;
        }catch(IOException|RuntimeException error){finish("Audio write interrupted");}
    }
    void mark(){
        if(encoder==null)return;
        addMark(db,active,duration,"Marker "+(marks+1));marks++;message="Marker saved at "+time(duration);
    }
    static void addMark(ArchiveStore db,String id,long ms,String label){
        ContentValues v=new ContentValues();v.put("session",id);v.put("ms",ms);v.put("label",label);
        db.getWritableDatabase().insertOrThrow("long_marks",null,v);
    }
    boolean finish(String reason){synchronized(LIFE){
        if(encoder==null)return false;
        long samples=encoder.samples;String id=active;
        try{encoder.end();}catch(IOException|RuntimeException e){samples=-1;reason="Audio ended early";}
        finally{encoder.close();encoder=null;}
        try{
            boolean saved=seal(app,db,id,samples,reason);
            message=saved?"Session saved · "+time(duration)+" · "+reason:"No audio reached the session";
            return saved;
        }catch(IOException|RuntimeException e){message="Session recovery pending · "+e.getMessage();return false;}
        finally{active="";}
    }}
    private static boolean seal(Context c,ArchiveStore db,String id,long samples,String reason)throws IOException{
        File dst=audio(c,id,false),src=dst.isFile()?dst:audio(c,id,true);
        long frames=src.isFile()?ArchiveStore.repair(src):0;
        if(frames==0){src.delete();db.getWritableDatabase().execSQL("UPDATE long_sessions SET status='No audio received' WHERE id=?",new Object[]{id});return false;}
        long ms=(samples<0?frames*1024:Math.min(frames*1024,samples))*1000/ArchiveStore.RATE;
        if(!src.equals(dst)&&!src.renameTo(dst))throw new IOException("Could not finalize audio");
        db.getWritableDatabase().execSQL("UPDATE long_sessions SET ms=?,bytes=?,status=? WHERE id=?",new Object[]{ms,dst.length(),reason,id});
        return true;
    }
    // AAC-LC ADTS frames contain 1024 samples. Start at a complete frame even for VBR recordings.
    static long offset(File audio,long ms)throws IOException{
        long frames=Math.max(0,ms)*ArchiveStore.RATE/(1000L*1024);
        try(RandomAccessFile in=new RandomAccessFile(audio,"r")){
            byte[] h=new byte[7];long at=0,previous=0,length=in.length();
            for(long n=0;n<frames && at+7<=length;n++){
                in.readFully(h);int size=((h[3]&3)<<11)|((h[4]&255)<<3)|((h[5]&255)>>5);
                if((h[0]&255)!=255 || (h[1]&255)!=241 || size<8 || at+size>length)throw new IOException("Invalid audio frame");
                previous=at;at+=size;in.seek(at);
            }
            return at>=length?previous:at;
        }
    }
    static long bytes(Context c,ArchiveStore db){
        try(Cursor cur=db.getReadableDatabase().rawQuery("SELECT COALESCE(SUM(bytes),0) FROM long_sessions",null)){
            cur.moveToFirst();long total=cur.getLong(0);
            try(Cursor open=db.getReadableDatabase().rawQuery("SELECT id FROM long_sessions WHERE status='Recording'",null)){
                while(open.moveToNext()){String id=open.getString(0);total+=Math.max(audio(c,id,true).length(),audio(c,id,false).length());}
            }
            return total;
        }
    }
    @Override public void close(){finish("Listening ended");}
}
