package org.totalrecall.listening;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Live recognition and the local archive are available before any upload or pairing. */
public final class CaptureReviewActivity extends Activity {
    private static final int INK=0xff183631, MUTED=0xff44635b, PAPER=0xfff6f8f3, TEAL=0xff086448;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LinearLayout page,body,nav,results;
    private TextView status,words,draft,context,scope,voiceState;
    private CaptureCues preview;
    private Button liveTab,savedTab;
    private Switch journal;
    private boolean saved,visible,reading;
    private long nextRead;
    private String lastEvidence=null, catalogKey=null;
    private final Runnable pulse=new Runnable(){public void run(){
        if(!visible)return;
        if(saved){if(!reading && SystemClock.elapsedRealtime()>=nextRead)readCatalog();}
        else paintLive();
        main.postDelayed(this,saved?1000:220);
    }};
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);saved=state!=null&&state.getBoolean("saved");
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(PAPER);
        page=column();page.setPadding(dp(16),dp(12),dp(16),dp(20));scroll.addView(page);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=35)scroll.setOnApplyWindowInsetsListener((v,i)->{
            android.graphics.Insets bars=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return i;
        });
        TextView title=text(page,"Live & saved",28,true);if(Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);
        nav=row(page);liveTab=action(nav,"Live now",()->tab(false));savedTab=action(nav,"Saved on phone",()->tab(true));
        LinearLayout quick=row(page);
        action(quick,"Full journal",()->startActivity(new Intent(this,FullJournalActivity.class)));
        action(quick,"Voice commands",this::guide);
        action(quick,"Lock screen & sound",this::feedbackSettings);
        body=column();page.addView(body);tab(saved);
        if(getIntent().getBooleanExtra("commands",false)&&state==null)guide();
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putBoolean("saved",saved);}
    @Override protected void onStart(){super.onStart();RecallOverlay.reviewVisible(this,true);}
    @Override protected void onStop(){RecallOverlay.reviewVisible(this,false);super.onStop();}
    @Override protected void onResume(){super.onResume();visible=true;nextRead=0;main.removeCallbacks(pulse);pulse.run();}
    @Override protected void onPause(){visible=false;stopPreview();main.removeCallbacks(pulse);super.onPause();}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
    private void tab(boolean selectSaved){
        saved=selectSaved;liveTab.setSelected(!saved);savedTab.setSelected(saved);
        liveTab.setTextColor(saved?MUTED:TEAL);savedTab.setTextColor(saved?TEAL:MUTED);
        body.removeAllViews();lastEvidence=null;catalogKey=null;nextRead=0;
        if(saved){
            scope=text(body,"",15,false);LinearLayout buttons=row(body);
            action(buttons,"All journals",()->recorder("allJournals"));action(buttons,"All recordings",()->recordings(null));
            action(body,"All saved clips",()->recorder("clips"));
            results=column();body.addView(results);if(visible&&!reading)readCatalog();
        }else{
            status=text(body,"",16,true);
            LinearLayout captions=card("Live transcript");
            words=text(captions,"",17,false);words.setTextIsSelectable(true);
            draft=text(captions,"",16,false);draft.setTypeface(Typeface.DEFAULT,Typeface.ITALIC);
            action(captions,"Record my words & edit",()->startActivity(new Intent(this,VoiceTakeActivity.class).putExtra("begin",true)));
            LinearLayout situation=card("What the words suggest");
            context=text(situation,"",16,false);context.setTextIsSelectable(true);
            LinearLayout people=card("Voices & sources");
            action(people,"Remember voices",()->startActivity(new Intent(this,VoiceProfilesActivity.class)));
            voiceState=text(people,"",15,false);
            action(people,"Name voices or change a speaker",()->{LiveCapture.Snapshot live=LiveCapture.snapshot();new VoiceEditor(this,io,live.journalId,this::paintLive).open();});
            Switch media=new Switch(this);media.setText("Skip automatic clip suggestions from remembered media voices");
            media.setChecked(getSharedPreferences("recall-speech",0).getBoolean("skipMedia",true));people.addView(media);
            media.setOnCheckedChangeListener((v,on)->getSharedPreferences("recall-speech",0).edit().putBoolean("skipMedia",on).apply());
            Switch grouping=new Switch(this);grouping.setText("Group similar voices in new sessions");grouping.setMinHeight(dp(48));
            grouping.setChecked(getSharedPreferences("recall-speech",0).getBoolean("voices",true));people.addView(grouping);
            grouping.setOnCheckedChangeListener((v,on)->getSharedPreferences("recall-speech",0).edit().putBoolean("voices",on).apply());
            LinearLayout settings=card("Capture choices");
            Switch spelling=new Switch(this);spelling.setText("Use context to correct similar-sounding words");
            spelling.setChecked(getSharedPreferences("recall-speech",0).getBoolean("contextSpelling",true));settings.addView(spelling);
            spelling.setOnCheckedChangeListener((v,on)->getSharedPreferences("recall-speech",0).edit().putBoolean("contextSpelling",on).apply());
            action(settings,"My words & phrases",()->SpellingPreferences.open(this));
            journal=new Switch(this);journal.setText("Save a text journal on this phone");journal.setMinHeight(dp(48));
            journal.setChecked(JournalStore.enabled(this));settings.addView(journal);
            journal.setOnCheckedChangeListener((v,on)->{
                try{JournalStore.setEnabled(this,on);}catch(RuntimeException e){Toast.makeText(this,"Could not change journal saving.",Toast.LENGTH_SHORT).show();journal.post(()->journal.setChecked(JournalStore.enabled(this)));}
            });
            Switch quiet=new Switch(this);quiet.setText("Boost quiet speech for transcription");quiet.setMinHeight(dp(48));
            quiet.setChecked(getSharedPreferences("recall-speech",0).getBoolean("liftQuiet",true));settings.addView(quiet);
            quiet.setOnCheckedChangeListener((v,on)->getSharedPreferences("recall-speech",0).edit().putBoolean("liftQuiet",on).apply());
            action(settings,"Learn room noise · 2 seconds",()->{
                if(!LiveCapture.snapshot().receiving){Toast.makeText(this,"Start listening and wait for an active microphone first.",Toast.LENGTH_LONG).show();return;}
                getSharedPreferences("recall-speech",0).edit().putBoolean("reduceRoom",true).apply();
                startService(new Intent(this,RecallService.class).setAction(RecallService.LEARN_ROOM));
                Toast.makeText(this,"Stay quiet for two seconds while the room is sampled.",Toast.LENGTH_LONG).show();
            });
            action(settings,"Turn off room noise reduction",()->{
                getSharedPreferences("recall-speech",0).edit().putBoolean("reduceRoom",false).apply();
                Toast.makeText(this,"Live room filter off.",Toast.LENGTH_SHORT).show();
            });
            text(settings,"Sample a fan or steady hum again after a pause or microphone change. The saved audio keeps the original sound.",14,false);
            text(settings,"Recent words stay here for up to 90 seconds. Turn on the journal to keep future words. Quiet boost affects live English transcription only.",14,false);
            action(body,"Open recorder controls",()->recorder(null));paintLive();
        }
    }
    private void feedbackSettings(){
        android.content.SharedPreferences prefs=getSharedPreferences("recall-speech",0);
        LinearLayout options=column();options.setPadding(dp(18),dp(8),dp(18),dp(12));
        text(options,"Expand the recording notification to save recent audio, start or finish a one-minute take, or pause. Tap the notification itself to review your recordings.",15,false);
        Switch words=new Switch(this);words.setMinHeight(dp(48));words.setText("Show recognized words and situations while locked");words.setChecked(prefs.getBoolean("noticeWords",false));options.addView(words);
        words.setOnCheckedChangeListener((v,on)->{
            prefs.edit().putBoolean("noticeWords",on).apply();
            if(RecallService.isListening())RecallService.command(this,RecallService.REFRESH_NOTICE);
        });
        action(options,"Android lock-screen notification settings",()->{
            android.app.NotificationManager manager=getSystemService(android.app.NotificationManager.class);
            if(manager.getNotificationChannel("total_recall_listening")!=null)
                startActivity(new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID,"total_recall_listening"));
            else startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()));
        });
        text(options,"Sound feedback",18,true);
        Switch chime=new Switch(this);chime.setText("Play wake and save chimes");chime.setMinHeight(dp(48));chime.setChecked(prefs.getBoolean("chime",true));options.addView(chime);
        chime.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean("chime",on).apply());
        Switch speech=new Switch(this);speech.setText("Say “Clip stored” or “Take stored” after a requested save");speech.setMinHeight(dp(48));speech.setChecked(prefs.getBoolean("spokenReceipts",false));options.addView(speech);
        speech.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean("spokenReceipts",on).apply());
        String[] levels={"Soft","Clear","Prominent"};
        Button level=action(options,"Cue level · "+levels[cueLevel(prefs)],()->{});
        level.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setTitle("Cue level")
            .setSingleChoiceItems(levels,cueLevel(prefs),(dialog,i)->{prefs.edit().putInt("cueLevel",i).apply();level.setText("Cue level · "+levels[i]);dialog.dismiss();}).setNegativeButton("Cancel",null).show());
        text(options,"Two rising notes mean Recall heard the wake word. A falling chime confirms a save. Use your media volume buttons to adjust the actual loudness.",14,false);
        LinearLayout tests=row(options);
        action(tests,"Test wake",()->previewCue(CaptureCues.Cue.WAKE));action(tests,"Test save",()->previewCue(CaptureCues.Cue.CLIP));
        ScrollView scroll=new ScrollView(this);scroll.addView(options);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("Lock screen & sound").setView(scroll).setPositiveButton("Done",null).create();
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        dialog.setOnDismissListener(dialogInterface->{stopPreview();setVolumeControlStream(android.media.AudioManager.USE_DEFAULT_STREAM_TYPE);});dialog.show();
    }
    private int cueLevel(android.content.SharedPreferences p){return Math.max(0,Math.min(2,p.getInt("cueLevel",2)));}
    private void stopPreview(){if(preview!=null){preview.close();preview=null;}}
    private void previewCue(CaptureCues.Cue cue){
        android.content.SharedPreferences p=getSharedPreferences("recall-speech",0);
        if(!p.getBoolean("chime",true) && (cue==CaptureCues.Cue.WAKE||!p.getBoolean("spokenReceipts",false))){
            Toast.makeText(this,"Enable a sound above to hear this preview.",Toast.LENGTH_SHORT).show();return;
        }
        try{if(preview==null)preview=new CaptureCues(this);preview.play(cue);}
        catch(RuntimeException e){Toast.makeText(this,"Audio preview is unavailable.",Toast.LENGTH_SHORT).show();}
    }
    private void guide(){
        LinearLayout contents=column();contents.setPadding(dp(20),dp(12),dp(20),dp(12));
        action(contents,"Test or change recognition sounds",this::feedbackSettings);
        text(contents,SelectiveEngine.guide(),16,false);
        ScrollView scroll=new ScrollView(this);scroll.addView(contents);
        new android.app.AlertDialog.Builder(this).setTitle("Voice commands").setView(scroll).setPositiveButton("Close",null).show();
    }
    private void paintLive(){
        LiveCapture.Snapshot s=LiveCapture.snapshot();
        put(status,(s.waiting?s.state:!s.active?"Microphone off":s.receiving?"Receiving audio · level "+Math.round(s.level*100)+"% · "+LiveCapture.roomStatus:s.state)+" · "+s.words+" words · "+s.flags+" flagged · "+s.savedCount+" saved"+(s.event.isEmpty()?"":"\n"+s.event));
        put(voiceState,!s.active?"Saved voice profiles are available next time you listen.":s.waiting?"Waiting for microphone access.":!s.groupsReady?"Voice grouping is off or preparing.":s.names.isEmpty()?"Listening for a clear voice. Short or mixed phrases may stay uncertain.":String.join("  •  ",s.names.values()));
        StringBuilder lines=new StringBuilder();
        long wall=System.currentTimeMillis(),clock=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        for(int i=0;i<s.finals.size();i++){
            String stamp=DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(wall-(clock-s.recognizedAt.get(i))));
            if(i>0)lines.append('\n');lines.append(stamp).append("  ").append(s.attributed(i));
        }
        put(words,lines.length()>0?lines.toString():s.waiting?"Waiting for microphone access.":s.active?"Waiting for recognized speech…":"Start or resume listening to see words here.");
        put(draft,s.partial.isEmpty()?"":"In progress · "+s.partial);
        String key=s.situationLines(0)+"|"+s.finalSequence+"|"+s.finals.size()+"|"+s.aliasVersion+"|"+s.partial;
        if(key.equals(lastEvidence))return;lastEvidence=key;
        try{
            Situation analysis=new Situation(this);
            for(int i=0;i<s.finals.size();i++){
                String stamp=DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(wall-(clock-s.recognizedAt.get(i))));
                analysis.add(s.finals.get(i),stamp+(s.who(i).isEmpty()?"":" · "+s.who(i)));
            }
            String result=analysis.cue().isEmpty()?"Waiting for words that indicate a question, idea, plan or obstacle.":analysis.result();
            if(!s.partial.isEmpty()){
                Situation tentative=new Situation(this);tentative.add(s.partial,"");
                if(!tentative.cue().isEmpty())result+="\n\nTentative · "+tentative.cue()+" · “"+s.partial+"”";
            }
            String scene=s.scene(0);put(context,s.situationLines(0)+"\n\n"+(scene.isEmpty()?result:scene+"\n\n"+result));
        }catch(java.io.IOException e){put(context,"Could not load the situation cues.");}
    }
    private void readCatalog(){
        reading=true;nextRead=SystemClock.elapsedRealtime()+4500;
        io.execute(()->{
            try(JournalStore j=new JournalStore(this);ArchiveStore a=new ArchiveStore(this);Store c=new Store(this)){
                List<JournalStore.Session> journals=j.sessions(6);List<ArchiveStore.Segment> recordings=a.segments(6,0);List<Store.Moment> clips=c.search("",6);
                main.post(()->{reading=false;if(!visible||!saved||isDestroyed())return;
                    put(scope,"Text journal "+(JournalStore.enabled(this)?"on":"off")+" · Full audio "+(ArchiveStore.enabled(this)?"on":"off"));
                    StringBuilder key=new StringBuilder();
                    for(JournalStore.Session v:journals)key.append('j').append(v.id).append('/').append(v.count).append('/').append(v.endMs);
                    for(ArchiveStore.Segment v:recordings)key.append('r').append(v.id).append(v.status);
                    for(Store.Moment v:clips)key.append('c').append(v.id).append(v.title);
                    if(key.toString().equals(catalogKey))return;catalogKey=key.toString();results.removeAllViews();
                    section("Recent journals",journals.isEmpty()?"No journal entries yet. Enable journal saving in Live now.":"");
                    for(JournalStore.Session v:journals)action(results,when(v.startMs)+" · "+v.count+" entries"+(v.endMs==0?" · open":""),()->startActivity(new Intent(this,RecallListenActivity.class).putExtra("captureDestination","journal").putExtra("journalId",v.id)));
                    section("Recent recordings",recordings.isEmpty()?"Completed full-audio segments appear here. Open All recordings for full audio settings.":"");
                    for(ArchiveStore.Segment v:recordings)action(results,when(v.startMs)+" · "+Math.round(v.durationMs/1000d)+" s · "+v.status,()->recordings(v.id));
                    section("Recent saved clips",clips.isEmpty()?"Tap Save last minute while listening to keep a clip.":"");
                    for(Store.Moment v:clips)action(results,when(v.startMs)+" · "+v.title,()->startActivity(new Intent(this,RecallListenActivity.class).putExtra("moment_id",v.id)));
                });
            }catch(RuntimeException e){main.post(()->{reading=false;if(visible&&saved)put(scope,"Could not read the phone archive. Retrying…");});}
        });
    }
    private void recordings(String id){Intent i=new Intent(this,ArchiveSyncActivity.class).putExtra("recordings",true);if(id!=null)i.putExtra("recordingId",id);startActivity(i);}
    private void recorder(String dest){Intent i=new Intent(this,RecallListenActivity.class);if(dest!=null)i.putExtra("captureDestination",dest);startActivity(i);}
    private String when(long t){return DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(t));}
    private static void put(TextView v,String value){if(!android.text.TextUtils.equals(v.getText(),value))v.setText(value);}
    private void section(String title,String empty){text(results,title,19,true);if(!empty.isEmpty())text(results,empty,15,false);}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(LinearLayout parent){LinearLayout v=new LinearLayout(this);parent.addView(v);return v;}
    private LinearLayout card(String title){
        LinearLayout v=column();GradientDrawable bg=new GradientDrawable();bg.setColor(0xffffffff);bg.setCornerRadius(dp(12));v.setBackground(bg);v.setPadding(dp(12),dp(6),dp(12),dp(9));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(10);body.addView(v,p);text(v,title,18,true);return v;
    }
    private TextView text(LinearLayout parent,String s,int size,boolean bold){TextView v=new TextView(this);v.setTextColor(bold?INK:MUTED);v.setTextSize(size);v.setText(s);v.setPadding(0,dp(4),0,dp(4));if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);parent.addView(v);return v;}
    private Button action(LinearLayout parent,String s,Runnable r){Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setMinHeight(dp(48));b.setOnClickListener(v->r.run());parent.addView(b,parent.getOrientation()==LinearLayout.HORIZONTAL?new LinearLayout.LayoutParams(0,-2,1):new LinearLayout.LayoutParams(-1,-2));return b;}
}
