package org.totalrecall.listening;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** One immutable manifest per approved item. Advancing a large queue mutates only the acknowledged row. */
final class ArchiveBatchQueue extends SQLiteOpenHelper {
    ArchiveBatchQueue(Context c){super(c.getApplicationContext(),"archive-transfer-queue.sqlite",null,1);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE batches(id TEXT PRIMARY KEY)");
        db.execSQL("CREATE TABLE queue(batch TEXT NOT NULL, position INTEGER NOT NULL, bytes INTEGER NOT NULL, manifest TEXT NOT NULL, PRIMARY KEY(batch,position))");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to){throw new IllegalStateException("Unknown transfer queue version");}
    static boolean reference(String value){return value!=null && value.startsWith("batch:");}
    String put(List<JSONObject> items)throws Exception{
        String ref="batch:"+UUID.randomUUID();
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            db.execSQL("INSERT INTO batches(id) VALUES(?)",new Object[]{ref});
            android.database.sqlite.SQLiteStatement stmt=db.compileStatement("INSERT INTO queue(batch,position,bytes,manifest) VALUES(?,?,?,?)");
            try{
                stmt.bindString(1,ref);int index=0;
                for(JSONObject item:items){
                    stmt.bindLong(2,index++);stmt.bindLong(3,item.getLong("bytes"));stmt.bindString(4,item.toString());stmt.executeInsert();
                }
            }finally{stmt.close();}
            db.setTransactionSuccessful();return ref;
        }finally{db.endTransaction();}
    }
    static final class Summary {final long bytes;final int count;Summary(int count,long bytes){this.count=count;this.bytes=bytes;}}
    Summary summary(String ref)throws IOException{
        try(Cursor r=getReadableDatabase().rawQuery("SELECT 1 FROM batches WHERE id=?",new String[]{ref})){
            if(!r.moveToFirst())throw new IOException("The confirmed transfer queue is missing. Stop and preview again.");
        }
        try(Cursor r=getReadableDatabase().rawQuery("SELECT count(*),coalesce(sum(bytes),0) FROM queue WHERE batch=?",new String[]{ref})){
            r.moveToFirst();return new Summary(r.getInt(0),r.getLong(1));
        }
    }
    static final class Row {final int position;final JSONObject item;Row(int pos,JSONObject item){position=pos;this.item=item;}}
    Row next(String ref)throws Exception{
        try(Cursor r=getReadableDatabase().rawQuery("SELECT position,manifest FROM queue WHERE batch=? ORDER BY position LIMIT 1",new String[]{ref})){
            return r.moveToFirst()?new Row(r.getInt(0),new JSONObject(r.getString(1))):null;
        }
    }
    void done(String ref,int pos)throws IOException{
        if(getWritableDatabase().delete("queue","batch=? AND position=?",new String[]{ref,Integer.toString(pos)})!=1)throw new IOException("Queue changed; check transfer status");
    }
    void retain(String manual,String automatic){
        SQLiteDatabase db=getWritableDatabase();
        String[] keep={reference(manual)?manual:"",reference(automatic)?automatic:""};
        db.beginTransaction();
        try{
            db.delete("queue","batch<>? AND batch<>?",keep);db.delete("batches","id<>? AND id<>?",keep);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
}
