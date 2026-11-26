package org.totalrecall.listening;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Audio is private to this application. A clip enters the timeline only after its WAV is durable. */
public final class Store extends SQLiteOpenHelper {
    private static final Object FILE_LOCK = new Object();
    private static long resetGeneration;
    private final File directory;
    private final Context app;

    public Store(Context context) {
        super(context.getApplicationContext(),"recall-listening.db",null,3);
        app=context.getApplicationContext();
        directory = new File(app.getFilesDir(),"listening-audio");
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE clips(id INTEGER PRIMARY KEY AUTOINCREMENT,uid TEXT NOT NULL UNIQUE,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,title TEXT NOT NULL,reason TEXT NOT NULL,transcript TEXT NOT NULL,kind TEXT NOT NULL,feedback TEXT NOT NULL DEFAULT 'none',note TEXT NOT NULL DEFAULT '',correction TEXT NOT NULL DEFAULT '',sha256 TEXT NOT NULL,parent_id TEXT NOT NULL DEFAULT '',start_sample INTEGER NOT NULL DEFAULT 0,end_sample INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX clips_start ON clips(start_ms DESC)");
        db.execSQL("CREATE TABLE deletions(uid TEXT PRIMARY KEY)");
        db.execSQL("CREATE TABLE gaps(id INTEGER PRIMARY KEY,start_ms INTEGER NOT NULL,detail TEXT NOT NULL)");
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        db.execSQL("INSERT INTO metadata VALUES('device',?)",new Object[]{UUID.randomUUID().toString()});
    }
    @Override public void onUpgrade(SQLiteDatabase db,int before,int after) {
        if(before>=1&&before<=2&&after==3){
            if(before==1)db.execSQL("CREATE TABLE IF NOT EXISTS deletions(uid TEXT PRIMARY KEY)");
            db.execSQL("ALTER TABLE clips ADD COLUMN parent_id TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE clips ADD COLUMN start_sample INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE clips ADD COLUMN end_sample INTEGER NOT NULL DEFAULT 0");return;
        }
        throw new IllegalStateException("Unsupported listening archive migration");
    }
    public static final class Moment {
        public final long id,startMs,endMs;
        public final String uid,title,reason,transcript,kind,feedback,note,correction,sha256,parentId;
        public final int startSample,endSample;
        public final File audio;
        Moment(Cursor c,File directory) {
            id=c.getLong(c.getColumnIndexOrThrow("id")); startMs=c.getLong(c.getColumnIndexOrThrow("start_ms"));
            endMs=c.getLong(c.getColumnIndexOrThrow("end_ms")); uid=field(c,"uid");title=field(c,"title");
            reason=field(c,"reason");transcript=field(c,"transcript");kind=field(c,"kind");feedback=field(c,"feedback");
            note=field(c,"note");correction=field(c,"correction");sha256=field(c,"sha256");
            parentId=field(c,"parent_id");startSample=c.getInt(c.getColumnIndexOrThrow("start_sample"));
            endSample=c.getInt(c.getColumnIndexOrThrow("end_sample"));audio=new File(directory,uid+".wav");
        }
        private static String field(Cursor c,String k){ return c.getString(c.getColumnIndexOrThrow(k)); }
    }
    static String hex(byte[] bytes) {
        char[] out=new char[bytes.length*2];
        char[] alphabet="0123456789abcdef".toCharArray();
        for(int i=0;i<bytes.length;i++){int n=bytes[i]&255; out[i*2]=alphabet[n>>>4];out[i*2+1]=alphabet[n&15];}
        return new String(out);
    }
    private static MessageDigest digest() {
        try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static void le(OutputStream out,int n,int width)throws IOException{
        for(int i=0;i<width;i++)out.write(n>>>(i*8));
    }
    static String writeWav(File file,short[] audio)throws IOException{
        MessageDigest md=digest();
        try(FileOutputStream bytes=new FileOutputStream(file);
            BufferedOutputStream out=new BufferedOutputStream(new DigestOutputStream(bytes,md))){
            int size=Math.multiplyExact(audio.length,2);
            out.write("RIFF".getBytes(StandardCharsets.US_ASCII));le(out,36+size,4);
            out.write("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));le(out,16,4);le(out,1,2);le(out,1,2);
            le(out,16000,4);le(out,32000,4);le(out,2,2);le(out,16,2);
            out.write("data".getBytes(StandardCharsets.US_ASCII));le(out,size,4);
            byte[] block=new byte[8192];
            for(int base=0;base<audio.length;){
                int n=Math.min(block.length/2,audio.length-base);
                for(int j=0;j<n;j++){short v=audio[base+j];block[2*j]=(byte)v;block[2*j+1]=(byte)(v>>>8);}
                out.write(block,0,2*n);base+=n;
            }
            out.flush();bytes.getFD().sync();
        }
        return hex(md.digest());
    }
    long save(SelectiveEngine.Clip clip)throws IOException{
        if(clip.audio.length==0)throw new IOException("No audio frames available");
        synchronized(FILE_LOCK) {
            if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create audio directory");
            SQLiteDatabase db=getWritableDatabase();
            File staged=File.createTempFile("writing-", ".tmp",directory);
            try {
                String hash=writeWav(staged,clip.audio);
                if(!org.continuity.recall.ListenPolicy.allowed(app))throw new IOException("Capture was paused");
                db.beginTransaction();
                try {
                    ContentValues cv=new ContentValues();
                    cv.put("uid",UUID.randomUUID().toString());
                    cv.put("start_ms",clip.startedAtMillis);
                    cv.put("end_ms",clip.startedAtMillis+(1000L*clip.audio.length/16000));
                    String transcript=clip.transcript==null?"":clip.transcript.trim();
                    String title=transcript.replaceAll("\\s+"," ");
                    cv.put("title",title.isEmpty()?clip.category:title.length()>85?title.substring(0,82)+"…":title);
                    cv.put("kind",clip.category);cv.put("reason",clip.reason);cv.put("transcript",transcript);cv.put("sha256",hash);
                    long id=db.insertOrThrow("clips",null,cv);
                    String device;
                    try(Cursor c=db.rawQuery("SELECT value FROM metadata WHERE key='device'",null)){
                        if(!c.moveToFirst())throw new IOException("Missing archive identifier");device=c.getString(0);
                    }
                    String uid=device+"-"+id;
                    // Imported captures can already occupy this installation's original identifier.
                    try(Cursor used=db.rawQuery("SELECT uid FROM clips WHERE uid=? UNION SELECT uid FROM deletions WHERE uid=?",new String[]{uid,uid})){
                        if(used.moveToFirst())uid=UUID.randomUUID().toString()+"-"+id;
                    }
                    File target=new File(directory,uid+".wav");
                    // If a previous process died before committing its row, replacement is safe.
                    if(!staged.renameTo(target))throw new IOException("Cannot commit clip audio");
                    cv.clear();cv.put("uid",uid);
                    db.update("clips",cv,"id=?",new String[]{String.valueOf(id)});
                    db.setTransactionSuccessful();
                    return id;
                }finally{db.endTransaction();}
            }finally{if(staged.exists())staged.delete();}
        }
    }
    /** Reset only after the active service has released its archive handles. */
    public static void reset(Context app){
        RecallService.clearResume(app);
        if(RecallService.isListening()){
            RecallService.command(app,RecallService.PAUSE);
            throw new IllegalStateException("Listening is stopping. Retry reset once paused.");
        }
        synchronized(FILE_LOCK){
            resetGeneration++;
            File dir=new File(app.getFilesDir(),"listening-audio");
            File[] files=dir.listFiles();
            if(files!=null)for(File f:files)if(f.isFile()&&!f.delete())throw new IllegalStateException("Unable to remove saved audio");
            if(dir.exists()&&!dir.delete())throw new IllegalStateException("Unable to clear listening audio directory");
            File database=app.getDatabasePath("recall-listening.db");
            if(database.exists()&&!app.deleteDatabase("recall-listening.db"))throw new IllegalStateException("Unable to clear listening archive");
            app.getSharedPreferences("total_recall_session",Context.MODE_PRIVATE).edit().clear().commit();
        }
    }
    /** Remove staged files left by process death; never remove a committed clip. */
    void recover(){
        synchronized(FILE_LOCK){
            File[] media=directory.listFiles();
            if(media==null)return;
            SQLiteDatabase db=getReadableDatabase();
            for(File file:media){
                String name=file.getName();
                if(name.startsWith("writing-")&&name.endsWith(".tmp")){file.delete();continue;}
                if(!name.matches("[a-f0-9-]+\\.wav"))continue;
                try(Cursor c=db.rawQuery("SELECT 1 FROM clips WHERE uid=?",new String[]{name.substring(0,name.length()-4)})){
                    if(!c.moveToFirst())file.delete();
                }
            }
        }
    }
    private static String escaped(String term){return term.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");}
    public List<Moment> search(String term){
        String query=term==null?"":term.trim();
        String[] words=query.isEmpty()?new String[0]:query.split("\\s+",8);
        StringBuilder sql=new StringBuilder("SELECT * FROM clips");
        String[] args=new String[words.length];
        for(int i=0;i<words.length;i++){
            sql.append(i==0?" WHERE ":" AND ").append("(title||' '||transcript||' '||kind||' '||reason||' '||note||' '||correction) LIKE ? ESCAPE '\\'");
            args[i]="%"+escaped(words[i])+"%";
        }
        sql.append(" ORDER BY start_ms DESC,id DESC");
        ArrayList<Moment> rows=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(sql.toString(),args)){
            while(c.moveToNext())rows.add(new Moment(c,directory));
        }return rows;
    }
    public Moment get(long id){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM clips WHERE id=?",new String[]{String.valueOf(id)})){
            return c.moveToFirst()?new Moment(c,directory):null;
        }
    }
    public long saveExcerpt(long parentRow,int from,int to,String name)throws IOException{
        if(name==null||name.trim().isEmpty()||name.length()>240)throw new IOException("Choose a name up to 240 characters");
        long row;
        synchronized(FILE_LOCK){
            SQLiteDatabase db=getWritableDatabase();Moment parent=get(parentRow);
            if(parent==null)throw new IOException("The original clip is unavailable");
            if(!directory.isDirectory())throw new IOException("Clip directory unavailable");
            File stage=File.createTempFile("writing-",".tmp",directory);File target=null;
            boolean committed=false,moved=false;
            try{
                String hash=PcmExcerpt.trim(parent.audio,stage,from,to);
                String uid=UUID.randomUUID().toString()+"-1";target=new File(directory,uid+".wav");
                if(target.exists())throw new IOException("Couldn’t allocate a new clip identifier");
                ContentValues cv=new ContentValues();cv.put("uid",uid);cv.put("title",name.trim());
                cv.put("kind","Excerpt");cv.put("reason","Selected from "+parent.title);cv.put("transcript","");
                cv.put("parent_id",parent.uid);cv.put("start_sample",from);cv.put("end_sample",to);
                long start=parent.startMs+(long)from*1000/PcmExcerpt.RATE;
                cv.put("start_ms",start);cv.put("end_ms",start+(long)(to-from)*1000/PcmExcerpt.RATE);
                cv.put("sha256",hash);db.beginTransaction();
                try{
                    row=db.insertOrThrow("clips",null,cv);if(!stage.renameTo(target))throw new IOException("Couldn’t commit excerpt audio");
                    moved=true;db.setTransactionSuccessful();
                }finally{db.endTransaction();}
                committed=true;
            }finally{stage.delete();if(moved&&!committed)target.delete();}
        }
        scheduleTransfer();return row;
    }
    public void edit(long id,String title,String note,String correction){
        ContentValues cv=new ContentValues();
        cv.put("title",title==null?"":title);cv.put("note",note==null?"":note);cv.put("correction",correction==null?"":correction);
        if(getWritableDatabase().update("clips",cv,"id=?",new String[]{String.valueOf(id)})!=1)throw new IllegalArgumentException("Clip no longer exists");
        scheduleTransfer();
    }
    public void feedback(long id,String choice){
        if(!Arrays.asList("keep","skip","none").contains(choice))throw new IllegalArgumentException("Unknown feedback");
        ContentValues cv=new ContentValues();cv.put("feedback",choice);
        if(getWritableDatabase().update("clips",cv,"id=?",new String[]{String.valueOf(id)})!=1)throw new IllegalArgumentException("Clip no longer exists");
        scheduleTransfer();
    }
    private void scheduleTransfer(){
        try {MintSync.schedule(app);}catch(RuntimeException ignored){/* Local edit already committed; next sync retries. */}
    }
    Map<String,Integer> weights(){
        Map<String,Integer> result=new HashMap<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT kind,SUM(CASE feedback WHEN 'keep' THEN 1 WHEN 'skip' THEN -1 ELSE 0 END) FROM clips GROUP BY kind",null)){
            while(c.moveToNext())result.put(c.getString(0),c.getInt(1));
        }return result;
    }
    List<SelectiveEngine.Example> examples(){
        ArrayList<SelectiveEngine.Example> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT transcript,correction,kind,feedback FROM clips WHERE feedback IN ('keep','skip') OR kind LIKE '%Missed moment%' OR kind LIKE '%Manual keep%' ORDER BY start_ms DESC,id DESC LIMIT 32",null)){
            while(c.moveToNext()){
                String text=c.getString(1).trim().isEmpty()?c.getString(0):c.getString(1);
                if(!text.trim().isEmpty())result.add(new SelectiveEngine.Example(text,c.getString(2),!"skip".equals(c.getString(3))));
            }
        }return result;
    }
    public void delete(long id){
        synchronized(FILE_LOCK){
            SQLiteDatabase db=getWritableDatabase();
            String uid;
            try(Cursor c=db.rawQuery("SELECT uid FROM clips WHERE id=?",new String[]{String.valueOf(id)})){
                if(!c.moveToFirst())return;uid=c.getString(0);
            }
            db.beginTransaction();
            try{
                db.execSQL("INSERT OR IGNORE INTO deletions(uid) VALUES(?)",new Object[]{uid});
                db.delete("clips","id=?",new String[]{String.valueOf(id)});db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            File wav=new File(directory,uid+".wav");
            if(wav.exists()&&!wav.delete())throw new IllegalStateException("Audio cleanup pending");
        }
    }
    void gap(String text){
        ContentValues values=new ContentValues();values.put("start_ms",System.currentTimeMillis());values.put("detail",text);
        getWritableDatabase().insertOrThrow("gaps",null,values);
    }
    public List<String> gaps(){
        ArrayList<String> rows=new ArrayList<>();
        java.text.DateFormat fmt=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT start_ms,detail FROM gaps ORDER BY id DESC LIMIT 100",null)){
            while(c.moveToNext())rows.add(fmt.format(new Date(c.getLong(0)))+" · "+c.getString(1));
        }return rows;
    }
    public String importZip(InputStream source)throws IOException{
        long generation;
        synchronized(FILE_LOCK){generation=resetGeneration;}
        File stage=new File(app.getCacheDir(),"restore-listening-"+UUID.randomUUID());
        if(!stage.mkdirs())throw new IOException("Unable to prepare private restore staging");
        try {
            ClipArchive archive=ClipArchive.stage(source,stage);
            synchronized(FILE_LOCK){
                if(resetGeneration!=generation)throw new IOException("Restore cancelled because the archive was reset");
                if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create audio directory");
                SQLiteDatabase db=getWritableDatabase(); ArrayList<File> moved=new ArrayList<>();
                boolean committed=false;int existing=0,deleted=0;
                try{
                    db.beginTransaction();
                    try {
                        for(JSONObject item:archive.clips){
                            String uid=item.getString("id");String[] bind={uid};
                            try(Cursor tomb=db.rawQuery("SELECT uid FROM deletions WHERE uid=?",bind)){
                                if(tomb.moveToFirst()){deleted++;continue;}
                            }
                            long start=item.getLong("startedAtMillis"),end=start+item.getLong("durationMs");
                            try(Cursor c=db.rawQuery("SELECT * FROM clips WHERE uid=?",bind)){
                                if(c.moveToFirst()){
                                    Moment local=new Moment(c,directory);
                                    if(!local.sha256.equals(item.getString("sha256")) ||local.startMs!=start ||local.endMs!=end ||
                                       !local.transcript.equals(item.getString("transcript")) || !local.kind.equals(item.getString("category")) ||
                                       !local.reason.equals(item.getString("reason"))||!local.parentId.equals(item.optString("parentId",""))||
                                       local.startSample!=item.optInt("startSample",0)||local.endSample!=item.optInt("endSample",0))throw new IOException("Original conflicts with local clip "+uid);
                                    MessageDigest verify=digest();
                                    try(InputStream in=new FileInputStream(local.audio)){
                                        byte[] block=new byte[32768];
                                        for(int n;(n=in.read(block))!=-1;)verify.update(block,0,n);
                                    }
                                    if(!local.sha256.equals(hex(verify.digest())))throw new IOException("Existing audio failed integrity check: "+uid);
                                    existing++;continue;
                                }
                            }
                            ContentValues cv=new ContentValues();cv.put("uid",uid);cv.put("start_ms",start);cv.put("end_ms",end);
                            for(String key:new String[]{"title","reason","feedback","transcript","sha256","note","correction"})cv.put(key,item.optString(key,""));
                            cv.put("kind",item.getString("category"));
                            cv.put("parent_id",item.optString("parentId",""));cv.put("start_sample",item.optInt("startSample",0));cv.put("end_sample",item.optInt("endSample",0));
                            File target=new File(directory,uid+".wav");File staged=archive.audio.get(item.getString("audio"));
                            if(!staged.renameTo(target))throw new IOException("Cannot restore clip audio");
                            moved.add(target);db.insertOrThrow("clips",null,cv);
                        }
                        db.setTransactionSuccessful();
                    }finally{db.endTransaction();}
                    committed=true;
                    return moved.size()+" restored · "+existing+" already present · "+deleted+" previously deleted";
                }catch(JSONException e){throw new IOException("Invalid clip index",e);}
                finally{if(!committed)for(File f:moved)f.delete();}
            }
        }finally{
            File[] temporary=stage.listFiles();if(temporary!=null)for(File f:temporary)f.delete();stage.delete();
        }
    }
    public void exportZip(OutputStream target)throws IOException{exportZip(target,null);}
    public void exportZip(OutputStream target,List<Long> selected)throws IOException {
        Set<Long> ids=selected==null?null:new HashSet<>(selected);
        {
            List<Moment> rows;
            if(ids!=null&&ids.size()==1){
                Moment only=get(ids.iterator().next());
                if(only==null)throw new IOException("Selected clip no longer exists");
                rows=Collections.singletonList(only);
            }else rows=search("");
            JSONArray clips=new JSONArray();
            try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(target))){
                byte[] buffer=new byte[32768];
                for(Moment m:rows){
                    if(ids!=null&&!ids.contains(m.id))continue;
                    String path="audio/"+m.uid+".wav";
                    ZipEntry audioEntry=new ZipEntry(path);audioEntry.setTime(315532800000L);zip.putNextEntry(audioEntry);
                    MessageDigest check=digest();
                    try(InputStream in=new FileInputStream(m.audio)){
                        int n;while((n=in.read(buffer))!=-1){zip.write(buffer,0,n);check.update(buffer,0,n);}
                    }
                    zip.closeEntry();
                    if(!m.sha256.equals(hex(check.digest())))throw new IOException("Clip integrity mismatch: "+m.uid);
                    JSONObject row=new JSONObject();
                    row.put("id",m.uid);
                    if(!m.parentId.isEmpty()){row.put("parentId",m.parentId);row.put("startSample",m.startSample);row.put("endSample",m.endSample);}
                    row.put("startedAtMillis",m.startMs);row.put("durationMs",m.endMs-m.startMs);
                    row.put("title",m.title);row.put("transcript",m.transcript);row.put("category",m.kind);
                    row.put("reason",m.reason);row.put("feedback",m.feedback);row.put("note",m.note);
                    row.put("correction",m.correction);row.put("audio",path);row.put("sha256",m.sha256);clips.put(row);
                }
                JSONObject index=new JSONObject();index.put("schema","total-recall-listening/v1");index.put("clips",clips);
                if(ids!=null&&clips.length()!=ids.size())throw new IOException("A selected clip no longer exists");
                ZipEntry indexEntry=new ZipEntry("index.json");indexEntry.setTime(315532800000L);zip.putNextEntry(indexEntry);zip.write(index.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }catch(JSONException e){throw new IOException("Cannot encode clip index",e);}
        }
    }
}
