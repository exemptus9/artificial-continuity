package org.totalrecall.listening;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Session recorder and its local library. Selected audio leaves the phone only on export. */
public final class LongSessionsActivity extends Activity {
    private LinearLayout page,items;
    private TextView status;
    private Button start,mark;
    private String lastActive=null,exportId="",exportTitle="";
    private boolean indexExport,visible;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private SessionPlayer playback;
    private long waiting;
    @Override public void onCreate(Bundle b){
        super.onCreate(b);setTitle("Long sessions");
        if(b!=null){exportId=b.getString("export","");exportTitle=b.getString("name","");indexExport=b.getBoolean("index");}
        ScrollView scroll=new ScrollView(this);page=new LinearLayout(this);page.setOrientation(1);page.setPadding(px(16),px(28),px(16),px(24));scroll.addView(page);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=30)scroll.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets bars=i.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return i;});
        text(page,"Long sessions",26).setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        text(page,"Record until you finish. An hour fits in one file. Quiet passages stay in the recording.",16);
        status=text(page,"",17);start=button(page,"Start long session",()->{if(LongRecording.active.isEmpty())begin();else RecallService.command(this,RecallService.FINISH_LONG);});
        mark=button(page,"Mark this point",()->RecallService.command(this,RecallService.MARK_LONG));
        text(page,"Saved sessions",21);items=new LinearLayout(this);items.setOrientation(1);page.addView(items);
    }
    private int px(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(LinearLayout host,String t,int size){TextView v=new TextView(this);v.setText(t);v.setTextSize(size);v.setPadding(0,px(6),0,px(4));host.addView(v);return v;}
    private Button button(LinearLayout host,String t,Runnable action){Button b=new Button(this);b.setAllCaps(false);b.setText(t);host.addView(b);b.setOnClickListener(v->action.run());return b;}
    private void note(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
    private void begin(){
        if(!org.continuity.recall.ListenPolicy.allowed(this)){note("Enable capture in Total Recall first");return;}
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=0){requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},1);return;}
        stopPlayer();waiting=SystemClock.elapsedRealtime()+20000;LongRecording.message="Starting microphone…";
        try{RecallService.command(this,RecallService.BEGIN_LONG);}catch(RuntimeException e){waiting=0;LongRecording.message="Could not start recording";}
        paint();
    }
    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] grants){super.onRequestPermissionsResult(req,permissions,grants);if(req==1 && grants.length>0 && grants[0]==0)begin();}
    private final Runnable pulse=new Runnable(){public void run(){if(visible){paint();main.postDelayed(this,400);}}};
    private void paint(){
        if(playback!=null)playback.refresh();
        String id=LongRecording.active;boolean on=!id.isEmpty();
        if(on||!LongRecording.message.equals("Starting microphone…"))waiting=0;
        boolean loading=waiting>SystemClock.elapsedRealtime();
        start.setEnabled(!loading);start.setText(on?"Finish & save session":"Start long session");
        mark.setVisibility(on?android.view.View.VISIBLE:android.view.View.GONE);
        status.setText(on?"Recording · "+LongRecording.time(LongRecording.duration)+" · "+LongRecording.marks+" markers":
            loading?"Starting microphone…":LongRecording.message.isEmpty()?"Ready":LongRecording.message.equals("Starting microphone…")?"Waiting for microphone input":LongRecording.message);
        if(!id.equals(lastActive)){lastActive=id;load();}
    }
    private static final class Row{
        String id,title,reason;long ms,bytes;
        Row(Cursor c){id=c.getString(0);title=c.getString(1);reason=c.getString(2);ms=c.getLong(3);bytes=c.getLong(4);}
    }
    private void load(){
        io.execute(()->{try(ArchiveStore db=new ArchiveStore(this)){
            LongRecording.recover(this,db);ArrayList<Row> rows=new ArrayList<>();
            try(Cursor c=db.getReadableDatabase().rawQuery("SELECT id,title,status,ms,bytes FROM long_sessions WHERE status<>'Recording' ORDER BY start DESC",null)){while(c.moveToNext())rows.add(new Row(c));}
            main.post(()->{if(!visible)return;items.removeAllViews();if(rows.isEmpty())text(items,"Finished recordings appear here.",15);
                for(Row r:rows)button(items,r.title+"\n"+LongRecording.time(r.ms)+" · "+r.reason,()->open(r));});
        }catch(Exception e){main.post(()->note("Could not read saved sessions"));}});
    }
    private void open(Row r){
        String[] options={"Play from start","Markers","Rename","Export audio (.aac)","Export marker index (.json)","Delete session"};
        new AlertDialog.Builder(this).setTitle(r.title).setItems(options,(dialog,i)->{
            if(i==0)play(r,0);if(i==1)markers(r);if(i==2)rename(r);if(i==3||i==4)export(r,i==4);
            if(i==5)new AlertDialog.Builder(this).setTitle("Delete this session and its markers?").setMessage(r.title).setNegativeButton("Cancel",null).setPositiveButton("Delete",(a,w)->remove(r)).show();
        }).show();
    }
    private JSONArray index(ArchiveStore db,String id)throws JSONException{
        JSONArray marks=new JSONArray();try(Cursor c=db.getReadableDatabase().rawQuery("SELECT ms,label FROM long_marks WHERE session=? ORDER BY ms,rowid",new String[]{id})){
            while(c.moveToNext()){
                String label=c.getString(1);
                String category=SelectiveEngine.FORGE.equals(label)?"Coined word":
                    SelectiveEngine.POETRY.equals(label)||SelectiveEngine.DECISION.equals(label)?label:"Moment";
                marks.put(new JSONObject().put("ms",c.getLong(0)).put("label",label).put("category",category));
            }
        }return marks;
    }
    private void markers(Row r){
        try(ArchiveStore db=new ArchiveStore(this)){JSONArray marks=index(db,r.id);
            if(marks.length()==0){note("No points were marked in this recording");return;}
            String[] labels=new String[marks.length()];for(int i=0;i<labels.length;i++){JSONObject m=marks.getJSONObject(i);labels[i]=LongRecording.time(m.getLong("ms"))+" · "+m.getString("label");}
            new AlertDialog.Builder(this).setTitle("Play from a marker").setItems(labels,(a,i)->play(r,marks.optJSONObject(i).optLong("ms"))).show();
        }catch(Exception e){note("Could not read markers");}
    }
    private void rename(Row r){EditText name=new EditText(this);name.setSingleLine(true);name.setText(r.title);name.selectAll();
        new AlertDialog.Builder(this).setTitle("Name this session").setView(name).setNegativeButton("Cancel",null).setPositiveButton("Save",(a,w)->{
            String title=name.getText().toString().trim();if(title.isEmpty())return;
            try(ArchiveStore db=new ArchiveStore(this)){db.getWritableDatabase().execSQL("UPDATE long_sessions SET title=? WHERE id=?",new Object[]{title.substring(0,Math.min(160,title.length())),r.id});}load();
        }).show();
    }
    private void remove(Row r){stopPlayer();File file=LongRecording.audio(this,r.id,false);
        if(file.exists()&&!file.delete()){note("Could not remove audio");return;}
        try(ArchiveStore db=new ArchiveStore(this)){
            android.database.sqlite.SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();
            try{sql.delete("long_marks","session=?",new String[]{r.id});sql.delete("long_sessions","id=?",new String[]{r.id});sql.setTransactionSuccessful();}finally{sql.endTransaction();}
        }load();
    }
    private void stopPlayer(){
        SessionPlayer current=playback;
        if(current!=null){playback=null;current.release();current.dialog.dismiss();}
    }
    private void play(Row row,long offsetMs){
        if(RecallService.isListening()){
            if(!LongRecording.active.isEmpty()){note("Finish the active session before playback");return;}
            new AlertDialog.Builder(this).setTitle("Pause listening to play audio?").setNegativeButton("Cancel",null)
            .setPositiveButton("Pause & play",(a,w)->{RecallService.command(this,RecallService.USER_PAUSE);awaitPlay(row,offsetMs,SystemClock.elapsedRealtime()+6000);}).show();
            return;
        }
        if(row.ms<=0){note("This session has no recorded audio");return;}
        stopPlayer();
        playback=new SessionPlayer(row);
        playback.open(offsetMs);
    }
    /** Seek by complete AAC frames. The source can have variable bitrate and an interrupted tail. */
    private final class SessionPlayer {
        final Row row;
        final File file;
        final AlertDialog dialog;
        final SeekBar seek;
        final TextView position;
        final Button toggle;
        MediaPlayer audio;
        Future<?> seeking;
        long from;
        int generation;
        boolean ready,dragging,playing=true,finished;
        SessionPlayer(Row row){
            this.row=row;
            file=LongRecording.audio(LongSessionsActivity.this,row.id,false);
            LinearLayout body=new LinearLayout(LongSessionsActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(px(20),px(8),px(20),0);
            position=text(body,"Opening recording…",17);
            seek=new SeekBar(LongSessionsActivity.this);
            seek.setContentDescription("Position in recording");
            seek.setMax(10000);body.addView(seek);
            LinearLayout transport=new LinearLayout(LongSessionsActivity.this);
            body.addView(transport);
            Button back=button(transport,"−10 s",()->jump(current()-10000));
            toggle=button(transport,"Pause",this::togglePlayback);
            Button ahead=button(transport,"+10 s",()->jump(current()+10000));
            back.setContentDescription("Go back ten seconds");
            ahead.setContentDescription("Go forward ten seconds");
            for(int i=0;i<transport.getChildCount();i++)
                transport.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onStartTrackingTouch(SeekBar view){dragging=true;}
                public void onProgressChanged(SeekBar view,int progress,boolean user){
                    if(!user)return;
                    long target=row.ms*progress/seek.getMax();
                    if(dragging)showPosition(target,"Seek to");
                    else jump(target); // keyboard or accessibility action
                }
                public void onStopTrackingTouch(SeekBar view){
                    dragging=false;jump(row.ms*view.getProgress()/seek.getMax());
                }
            });
            dialog=new AlertDialog.Builder(LongSessionsActivity.this).setTitle(row.title).setView(body)
                .setPositiveButton("Close",null).create();
            dialog.setOnDismissListener(d->{
                generation++;release();
                if(playback==this)playback=null;
            });
        }
        void open(long startMs){dialog.show();jump(startMs);}
        void release(){
            ready=false;
            if(seeking!=null){seeking.cancel(true);seeking=null;}
            if(audio!=null){audio.release();audio=null;}
        }
        long current(){
            if(ready && audio!=null){
                try{return Math.min(row.ms,from+audio.getCurrentPosition());}catch(IllegalStateException ignored){}
            }
            return from;
        }
        boolean valid(int request){return visible && playback==this && generation==request && dialog.isShowing();}
        void jump(long requested){
            int request=++generation;
            release();
            from=Math.max(0,Math.min(row.ms,requested));
            finished=from>=row.ms;
            if(finished)playing=false;
            refresh();
            if(finished || !valid(request))return;
            long target=from;
            seeking=io.submit(()->{
                try{
                    long fileOffset=LongRecording.offset(file,target);
                    main.post(()->{if(valid(request))prepare(request,fileOffset);});
                }catch(IOException error){
                    main.post(()->{if(valid(request))failed("Could not locate this point in the recording");});
                }
            });
        }
        void prepare(int request,long offset){
            try(FileInputStream in=new FileInputStream(file)){
                MediaPlayer p=new MediaPlayer();audio=p;
                p.setDataSource(in.getFD(),offset,file.length()-offset);
                p.setOnPreparedListener(v->{
                    if(!valid(request) || audio!=v)return;
                    if(RecallService.isListening()){failed("Playback stopped · microphone active");return;}
                    ready=true;
                    if(playing)startAudio();
                    refresh();
                });
                p.setOnCompletionListener(v->{
                    if(!valid(request) || audio!=v)return;
                    release();from=row.ms;finished=true;playing=false;refresh();
                });
                p.setOnErrorListener((v,w,e)->{
                    if(valid(request) && audio==v)failed("Could not play this recording");
                    return true;
                });
                p.prepareAsync();
            }catch(IOException|RuntimeException error){failed("Could not open this recording");}
        }
        void failed(String message){dialog.dismiss();note(message);}
        void startAudio(){
            try{audio.start();}catch(IllegalStateException e){failed("Playback could not resume");}
        }
        void togglePlayback(){
            if(finished){playing=true;jump(0);return;}
            playing=!playing;
            if(ready){
                if(playing)startAudio();
                else try{audio.pause();}catch(IllegalStateException e){failed("Playback could not pause");}
            }
            refresh();
        }
        void showPosition(long ms,String label){
            position.setText(label+" · "+LongRecording.time(ms)+" / "+LongRecording.time(row.ms));
        }
        void refresh(){
            if(!dialog.isShowing())return;
            if(RecallService.isListening()){failed("Playback stopped · microphone active");return;}
            toggle.setText(finished?"Replay":playing?"Pause":"Play");
            if(dragging)return;
            long ms=current();
            showPosition(ms,finished?"Finished":!ready?"Seeking":playing?"Playing":"Paused");
            seek.setProgress((int)(ms*seek.getMax()/row.ms));
        }
    }
    private void awaitPlay(Row r,long ms,long until){if(!visible)return;if(!RecallService.isListening())play(r,ms);else if(SystemClock.elapsedRealtime()<until)main.postDelayed(()->awaitPlay(r,ms,until),150);else note("Microphone still busy");}
    private void export(Row r,boolean index){exportId=r.id;exportTitle=r.title;indexExport=index;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(index?"application/json":"audio/aac").putExtra(Intent.EXTRA_TITLE,r.title.replaceAll("[^\\p{L}\\p{N} _-]","_")+(index?".markers.json":".aac")),2);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req!=2||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();String id=exportId,title=exportTitle;boolean idx=indexExport;
        io.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt");ArchiveStore db=new ArchiveStore(this)){
            if(out==null)throw new IOException();
            if(idx){
                File file=LongRecording.audio(this,id,false);
                JSONObject json=new JSONObject().put("format","total-recall-markers-1").put("title",title)
                    .put("audioBytes",file.length()).put("audioFingerprint",LongRecording.audioFingerprint(file))
                    .put("markers",index(db,id));
                out.write(json.toString(2).getBytes("UTF-8"));
            }
            else try(InputStream in=new FileInputStream(LongRecording.audio(this,id,false))){byte[] buf=new byte[65536];for(int n;(n=in.read(buf))>=0;)out.write(buf,0,n);}
        }catch(Exception e){main.post(()->note("Export failed"));return;}main.post(()->note("Export complete"));});
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("export",exportId);b.putString("name",exportTitle);b.putBoolean("index",indexExport);}
    @Override protected void onResume(){super.onResume();visible=true;lastActive=null;RecallOverlay.reviewVisible(this,true);pulse.run();}
    @Override protected void onPause(){visible=false;main.removeCallbacks(pulse);stopPlayer();RecallOverlay.reviewVisible(this,false);super.onPause();}
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
}
