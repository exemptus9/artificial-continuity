package org.totalrecall.listening;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

/** Record with the foreground service's existing microphone, then review the exact saved take. */
public final class VoiceTakeActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LinearLayout page;
    private TextView status,live;
    private Button primary;
    private EditText editor;
    private android.media.MediaPlayer player;
    private Button playback;
    private android.media.AudioFocusRequest focus;
    private boolean waitingForRelease;
    private long shown=-1,previous=-1,deadline,boundary;
    private boolean awaiting,starting,visible;
    private String restored,exportWords;
    private final Runnable poll=new Runnable(){public void run(){if(!visible)return;paint();main.postDelayed(this,200);}};
    private int dp(int n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private TextView line(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setPadding(0,dp(5),0,dp(5));page.addView(v);return v;}
    private Button button(String label,Runnable click){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setMinHeight(dp(48));page.addView(b);b.setOnClickListener(v->click.run());return b;}
    private void note(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setTitle("Record my words");ScrollView scroll=new ScrollView(this);page=new LinearLayout(this);page.setOrientation(1);
        page.setPadding(dp(18),dp(12),dp(18),dp(16));scroll.addView(page);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=35)scroll.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);return i;});
        form();
        if(state!=null){
            exportWords=state.getString("exportWords");starting=state.getBoolean("starting");deadline=state.getLong("deadline");previous=state.getLong("previous",-1);awaiting=state.getBoolean("awaiting");boundary=state.getLong("boundary");
            restored=state.getString("draft");long clip=state.getLong("clip",-1);if(clip>=0)open(clip);
        }else if(SpokenTake.status.recording){awaiting=true;boundary=LiveCapture.snapshot().finalSequence;if(getIntent().getBooleanExtra("finishTake",false))finishTake();}
        else if(getIntent().getBooleanExtra("finishTake",false)){if(SpokenTake.status.clip>=0)open(SpokenTake.status.clip);}
        else if(getIntent().getBooleanExtra("begin",false) && LiveCapture.snapshot().receiving)begin();
    }
    private void stopAudio(){
        waitingForRelease=false;
        if(player!=null){player.release();player=null;}
        if(focus!=null){getSystemService(android.media.AudioManager.class).abandonAudioFocusRequest(focus);focus=null;}
        if(playback!=null){playback.setEnabled(true);playback.setText("Listen to original");}
    }
    private void play(Store.Moment moment){
        if(player!=null){stopAudio();return;}
        if(RecallService.isListening()){
            new AlertDialog.Builder(this).setTitle("Pause listening to hear your take?")
                .setMessage("Your take is saved. The microphone will pause while you check the audio.")
                .setNegativeButton("Keep listening",null).setPositiveButton("Pause & play",(d,w)->{
                    RecallService.command(this,RecallService.USER_PAUSE);waitingForRelease=true;
                    playback.setEnabled(false);waitForRelease(moment,SystemClock.elapsedRealtime()+6000);
                }).show();return;
        }
        try{
            android.media.AudioAttributes attr=new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focus=new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attr).setOnAudioFocusChangeListener(change->{if(change<0)stopAudio();}).build();
            if(getSystemService(android.media.AudioManager.class).requestAudioFocus(focus)!=android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED){stopAudio();note("Audio is busy. Try again.");return;}
            android.media.MediaPlayer p=new android.media.MediaPlayer();player=p;p.setAudioAttributes(attr);p.setDataSource(moment.audio.getAbsolutePath());
            p.setOnPreparedListener(ready->{if(ready==player&&visible&&!RecallService.isListening()){ready.start();playback.setText("Stop playback");}else stopAudio();});
            p.setOnCompletionListener(done->stopAudio());p.setOnErrorListener((v,w,e)->{stopAudio();note("Could not play this file.");return true;});
            p.prepareAsync();
        }catch(Exception e){stopAudio();note("Could not open the original audio.");}
    }
    private void waitForRelease(Store.Moment m,long until){
        if(!waitingForRelease||!visible)return;
        if(!RecallService.isListening()){waitingForRelease=false;playback.setEnabled(true);play(m);}
        else if(SystemClock.elapsedRealtime()>=until){stopAudio();note("Microphone is still busy.");}
        else main.postDelayed(()->waitForRelease(m,until),100);
    }
    private void form(){
        stopAudio();
        page.removeAllViews();editor=null;shown=-1;line("Record my words",26);
        status=line("Ready",18);live=line("",17);
        primary=button("Record my words",()->{if(SpokenTake.status.recording)finishTake();else begin();});
        line("Speak after the recording timer starts. Finish & edit saves the original audio and opens your words for correction. Each take can run for one minute.",15);
    }
    private void begin(){
        if(!org.continuity.recall.ListenPolicy.allowed(this)){note("Enable capture in Total Recall first.");return;}
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},4);return;
        }
        previous=SpokenTake.status.clip;boundary=LiveCapture.snapshot().finalSequence;awaiting=true;starting=true;deadline=SystemClock.elapsedRealtime()+20000;
        if(!RecallService.isListening())RecallService.command(this,RecallService.START);paint();
    }
    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] result){
        super.onRequestPermissionsResult(req,permissions,result);
        if(req==4 && result.length>0 && result[0]==0)begin();else note("Microphone access is needed to record your words.");
    }
    private void finishTake(){
        primary.setEnabled(false);RecallService.command(this,RecallService.END_TAKE);
    }
    private void paint(){
        if(shown>=0){if(player!=null && RecallService.isListening())stopAudio();return;}
        SpokenTake.Status take=SpokenTake.status;LiveCapture.Snapshot s=LiveCapture.snapshot();
        if(starting && s.receiving && SystemClock.elapsedRealtime()<deadline){starting=false;RecallService.command(this,RecallService.BEGIN_TAKE);}
        if(take.recording){
            awaiting=true;deadline=0;primary.setEnabled(true);primary.setText("Finish & edit");
            status.setText("Recording · "+take.seconds+" / 60 s");live.setText(s.overlayWords(Math.max(boundary,SpokenTake.firstLine)));return;
        }
        if(awaiting && take.clip>=0 && take.clip!=previous){awaiting=false;open(take.clip);return;}
        boolean expired=deadline>0 && SystemClock.elapsedRealtime()>=deadline;
        if(awaiting && (expired || (!starting && deadline==0 && !take.message.startsWith("Saving")))){awaiting=false;starting=false;}
        primary.setText("Record my words");primary.setEnabled(!awaiting);
        status.setText(awaiting?(starting?"Waiting for microphone…":take.message.startsWith("Saving")?take.message:"Starting your take…"):
            expired?"Microphone did not become available. Try again.":take.clip<0&&!take.message.isEmpty()?take.message:"Ready");
    }
    private void open(long id){
        stopAudio();shown=id;
        io.execute(()->{try(Store db=new Store(this)){
            Store.Moment m=db.get(id);main.post(()->{
                if(isDestroyed()||isFinishing())return;
                if(m==null){form();note("This clip is no longer on this phone.");return;}
                page.removeAllViews();line("Review your take",26);line("Originally heard",16);
                TextView raw=line(m.transcript.isEmpty()?"No words recognized. The audio is saved.":m.transcript,18);raw.setTextIsSelectable(true);
                playback=button("Listen to original",()->play(m));
                line("What I actually said",16);editor=new EditText(this);editor.setMinLines(3);editor.setGravity(Gravity.TOP);
                editor.setContentDescription("What I actually said");editor.setText(restored!=null?restored:m.correction.isEmpty()?m.transcript:m.correction);
                restored=null;page.addView(editor);
                button("Save corrected words",()->save(m));
                button("Copy these words",()->PassageActions.copy(this,editor.getText().toString()));
                button("Share these words",()->PassageActions.share(this,editor.getText().toString()));
                button("Export words as .txt",()->exportText(m.id));
                button("Share original audio",()->PassageActions.audio(this,m,io));
                button("Open clip for trimming or ZIP export",()->startActivity(new Intent(this,RecallListenActivity.class).putExtra("moment_id",m.id)));
                button("Record another take",()->{
                    String saved=editor.getText().toString();
                    if(!saved.equals(m.correction.isEmpty()?m.transcript:m.correction))
                        new AlertDialog.Builder(this).setMessage("Start another take and leave these edits?").setNegativeButton("Keep editing",null).setPositiveButton("Start another",(d,w)->{form();begin();}).show();
                    else{form();begin();}
                });
            });
        }catch(RuntimeException e){main.post(()->{if(!isDestroyed()){form();note("Could not open this take.");}});}});
    }
    private void exportText(long id){
        exportWords=editor.getText().toString();
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain").putExtra(Intent.EXTRA_TITLE,"TotalRecall-take-"+id+".txt"),8);
    }
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);if(code!=8)return;
        String text=exportWords;exportWords=null;
        if(result!=RESULT_OK||data==null||data.getData()==null||text==null)return;
        android.net.Uri destination=data.getData();
        io.execute(()->{try(java.io.OutputStream out=getContentResolver().openOutputStream(destination,"wt")){
            if(out==null)throw new java.io.IOException();out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }catch(Exception e){main.post(()->note("Could not export the words."));return;}
        main.post(()->note("Text file saved"));});
    }
    private void save(Store.Moment m){
        String text=editor.getText().toString().trim();if(text.isEmpty()){editor.setError("Enter the words to save");return;}
        io.execute(()->{try(Store db=new Store(this)){db.correctWords(m.id,text.equals(m.transcript)?"":text);
            main.post(()->{if(!isDestroyed()){note("Correction saved. Original audio and words kept.");open(m.id);}});
        }catch(RuntimeException e){main.post(()->note("Could not save this correction."));}});
    }
    @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("exportWords",exportWords);out.putBoolean("starting",starting);out.putLong("deadline",deadline);out.putLong("clip",shown);out.putLong("previous",previous);out.putLong("boundary",boundary);out.putBoolean("awaiting",awaiting);if(editor!=null)out.putString("draft",editor.getText().toString());}
    @Override public void onResume(){super.onResume();visible=true;RecallOverlay.reviewVisible(this,true);main.post(poll);}
    @Override public void onPause(){visible=false;stopAudio();main.removeCallbacks(poll);RecallOverlay.reviewVisible(this,false);super.onPause();}
    @Override public void onDestroy(){io.shutdown();super.onDestroy();}
}
