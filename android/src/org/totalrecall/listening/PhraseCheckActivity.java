package org.totalrecall.listening;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import android.text.InputFilter;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.continuity.recall.ListenPolicy;

/** Foreground-only personal dictation comparison. The written reference never enters Vosk. */
public final class PhraseCheckActivity extends Activity {
    private static final String[] LINES={"The silver river keeps the moon awake","Meet me at six fifteen with two copies","I call that feeling glimmershift"};
    private static final String[] DELIVERY={"Everyday pace","Quiet voice","Fast delivery"};
    private static final String[] GAIN={"Lift off","Standard lift","Extended reach"};
    private static final int MIC=72;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private final Runnable tick=this::paint;
    private LinearLayout page;
    private EditText expected;
    private Spinner delivery,gain;
    private Button finish;
    private TextView meter;
    private PhraseCheckInput input;
    private volatile long sequence;
    private long started,lastInputAt;
    private int lastSamples;
    private boolean visible,decoding,live;
    private Trial previous,review,shownComparison;
    private boolean showingScore;
    private String phrase=LINES[0];
    private int style,level;

    private static final class Trial {
        final String phrase,heard;
        final int style,level;
        Trial(String phrase,String heard,int style,int level){this.phrase=phrase;this.heard=heard;this.style=style;this.level=level;}
        String settings(){return DELIVERY[style]+" · "+GAIN[level];}
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setTitle("Check how Recall hears me");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll=new ScrollView(this);page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18),dp(12),dp(18),dp(18));scroll.addView(page);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=35)scroll.setOnApplyWindowInsetsListener((v,i)->{
            android.graphics.Insets bars=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return i;});
        android.content.SharedPreferences p=getSharedPreferences("recall-speech",MODE_PRIVATE);
        level=!p.getBoolean("liftQuiet",true)?0:p.getBoolean("extendedReach",false)?2:1;
        Draft restored=(Draft)getLastNonConfigurationInstance();
        if(restored!=null){phrase=restored.phrase;style=restored.style;level=restored.level;previous=restored.previous;}
        if(restored!=null && restored.review!=null)confirm(restored.review);
        else if(restored!=null && restored.scored){showScore(previous,restored.comparison);}
        else form("");
    }
    private static final class Draft {String phrase;int style,level;Trial previous,review,comparison;boolean scored;}
    @Override public Object onRetainNonConfigurationInstance(){
        readForm();Draft d=new Draft();d.phrase=phrase;d.style=style;d.level=level;d.previous=previous;
        d.review=review;d.scored=showingScore;d.comparison=shownComparison;return d;
    }
    @Override public void onResume(){super.onResume();visible=true;RecallOverlay.reviewVisible(this,true);}
    @Override public void onPause(){
        visible=false;
        if(input!=null || decoding){cancel();form("Take stopped when you left the check. Start a fresh take.");}
        RecallOverlay.reviewVisible(this,false);super.onPause();
    }
    @Override public void onDestroy(){cancel();work.shutdownNow();super.onDestroy();}
    private int dp(int n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private TextView line(String value,int size){
        TextView v=new TextView(this);v.setTextSize(size);v.setText(value);v.setPadding(0,dp(5),0,dp(5));page.addView(v);return v;
    }
    private Button button(String label,Runnable run){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setMinHeight(dp(48));page.addView(b);b.setOnClickListener(v->run.run());return b;
    }
    private Spinner choice(String label,String[] values,int selected){
        line(label,15);Spinner s=new Spinner(this);s.setContentDescription(label);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);s.setSelection(selected);page.addView(s);return s;
    }
    private void readForm(){if(expected!=null){
        phrase=expected.getText().toString().trim();style=delivery.getSelectedItemPosition();level=gain.getSelectedItemPosition();
    }}
    private void clear(){page.removeAllViews();expected=null;}
    private void form(String status){
        showingScore=false;shownComparison=null;review=null;
        clear();line("Check how Recall hears me",25);
        line("Read one line, check the words Recall heard, then try it again in a different voice or with another quiet lift. Each take lasts up to 12 seconds.",16);
        if(!status.isEmpty())line(status,17).setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        line("Line to read · up to 24 words",16);
        expected=new EditText(this);expected.setContentDescription("Line to read");expected.setHint("Your poem, coined word or everyday phrase");
        expected.setMinLines(2);expected.setMaxLines(4);expected.setText(phrase);expected.setSaveEnabled(false);
        expected.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);expected.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        expected.setFilters(new InputFilter[]{new InputFilter.LengthFilter(240)});page.addView(expected);
        button("Choose an example line",()->new AlertDialog.Builder(this).setTitle("Choose a line")
            .setItems(LINES,(dialog,i)->{expected.setText(LINES[i]);expected.setSelection(expected.length());})
            .setNegativeButton("Cancel",null).show());
        delivery=choice("How you plan to say it",DELIVERY,style);gain=choice("Quiet lift for this take",GAIN,level);
        line("Lift changes apply to this take only. The local dictation model starts fresh without room calibration. Saved spellings apply if this take matches your voice, so a live caption may differ.",15);
        button("Read this line",this::prepare);
        if(previous!=null)line("Last confirmed take · "+previous.settings()+"\n"+
            (previous.heard.isEmpty()?"No words recognized":"Heard “"+previous.heard+"”"),15);
    }
    private void prepare(){
        readForm();int n=PhraseCheckScore.words(phrase).size();
        if(n<1 || n>24){expected.setError("Enter between 1 and 24 spoken words.");return;}
        if(!ListenPolicy.allowed(this)){
            new AlertDialog.Builder(this).setTitle("Capture is paused in the phone vault")
                .setMessage("Enable capture in the vault to use the microphone for this check.")
                .setPositiveButton("Open vault",(d,w)->startActivity(new Intent(this,org.continuity.recall.MainActivity.class)))
                .setNegativeButton("Cancel",null).show();return;
        }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC);return;
        }
        if(RecallService.isListening()){
            if(!LiveCapture.snapshot().receiving){form("The running recorder is waiting for microphone input. Try again when input returns.");return;}
            new AlertDialog.Builder(this).setTitle("Use the running recorder?")
                .setMessage("The recorder will keep listening. Spoken commands still work, and your current journal, recording and clip settings can save what you say. For a private take, return after pausing the recorder.")
                .setPositiveButton("Use running recorder",(d,w)->start(true)).setNegativeButton("Go back",null).show();
        }else start(false);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);if(code!=MIC)return;
        form(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED?"Microphone access granted. Tap Read this line when ready.":
            "Microphone access is needed to read a line aloud.");
    }
    private void cancel(){
        sequence++;decoding=false;main.removeCallbacks(tick);
        if(input!=null){PhraseCheckInput.release(input);input.discard();input=null;}
    }
    private void start(boolean useLive){
        if(!visible || !ListenPolicy.allowed(this) || useLive!=RecallService.isListening()){
            form("Recording state changed. Tap Read this line again.");return;}
        cancel();review=null;live=useLive;input=new PhraseCheckInput();started=SystemClock.elapsedRealtime();
        lastInputAt=started;lastSamples=0;
        clear();line("Read your line",25);line(phrase,23);line(DELIVERY[style]+" · "+GAIN[level],16);
        meter=line("Preparing fresh microphone input…",17);
        finish=button("Finish take",this::finishTake);finish.setEnabled(false);
        button("Discard take",()->{cancel();form("Take discarded.");});
        line(useLive?"Your running recorder continues with its current settings.":"Private microphone take. The check keeps the audio in memory until the words are read.",15);
        PhraseCheckInput trial=input;Context app=getApplicationContext();
        if(useLive)PhraseCheckInput.borrow(trial);else work.execute(()->trial.recordPrivate(app));
        main.post(tick);
    }
    private void paint(){
        if(input==null || !visible)return;
        String problem=input.problem();long elapsed=SystemClock.elapsedRealtime()-started;
        int samples=input.size();
        boolean full=samples==PhraseCheckInput.RATE*PhraseCheckInput.SECONDS;
        LiveCapture.Snapshot snapshot=live?LiveCapture.snapshot():null;
        if(problem.isEmpty() && (!ListenPolicy.allowed(this) || (!full && live && (!snapshot.active || snapshot.waiting))))
            problem="Microphone input stopped. Start a new take.";
        if(lastSamples!=samples){lastSamples=samples;lastInputAt=SystemClock.elapsedRealtime();}
        if(problem.isEmpty() && input.isReady() && SystemClock.elapsedRealtime()-lastInputAt>4000)
            problem="Microphone input stalled during this take. Try again.";
        if(problem.isEmpty() && !input.isReady() && elapsed>8000)problem="No fresh microphone input arrived. Try again.";
        if(!problem.isEmpty()){cancel();form(problem);return;}
        if(full || elapsed>=21000){
            if(samples>0)finishTake();else{cancel();form("No microphone audio arrived.");}return;
        }
        if(input.isReady()){
            String message="Speak now · "+(samples/PhraseCheckInput.RATE)+" / "+PhraseCheckInput.SECONDS+" seconds";
            if(!message.contentEquals(meter.getText()))meter.setText(message);
            finish.setEnabled(samples>0);
        }
        main.postDelayed(tick,120);
    }
    private void finishTake(){
        if(input==null)return;
        PhraseCheckInput trial=input;PhraseCheckInput.release(trial);
        short[] samples=trial.finish();input=null;main.removeCallbacks(tick);
        if(samples.length==0){form("No microphone audio arrived. Start a new take.");return;}
        long ticket=++sequence;decoding=true;clear();line("Reading your take on this phone…",21);
        button("Discard take",()->{cancel();form("Take discarded.");});
        String reference=phrase;int voiceStyle=style,lift=level;
        Context app=getApplicationContext();
        work.execute(()->{
            StringBuilder words=new StringBuilder();boolean failed=false;
            try{
                if(sequence!=ticket)return;
                try(OfflineSpeech decoder=OfflineSpeech.forPhraseCheck(app)){
                    decoder.extendedReach=lift==2;
                    short[] frame=new short[1600];
                    try{
                        for(int i=0;i<samples.length;i+=frame.length){
                            if(sequence!=ticket || Thread.currentThread().isInterrupted())return;
                            int count=Math.min(frame.length,samples.length-i);
                            System.arraycopy(samples,i,frame,0,count);
                            append(words,decoder.accept(frame,count,lift>0,false));
                        }
                        append(words,decoder.finish());
                    }finally{Arrays.fill(frame,(short)0);}
                }
            }catch(IOException|RuntimeException|LinkageError e){failed=true;}
            finally{Arrays.fill(samples,(short)0);}
            boolean error=failed;String heard=words.toString();
            main.post(()->{if(!visible || sequence!=ticket)return;decoding=false;
                if(error){form("The offline speech model could not read this take. Try again.");return;}
                confirm(new Trial(reference,heard,voiceStyle,lift));
            });
        });
    }
    private static void append(StringBuilder result,String next){
        if(next==null || next.trim().isEmpty())return;if(result.length()>0)result.append(' ');result.append(next.trim());
    }
    private void confirm(Trial take){
        review=take;clear();line("Did you read this line?",24);line("Line to read",15);line(take.phrase,20);
        line("Recall heard",15);line(take.heard.isEmpty()?"No words recognized":take.heard,20);
        line("Only compare if you said the line as written. You can also retry a quiet take that returned no words.",15);
        button("I read this line · compare",()->score(take));
        button("Redo or change the line",()->{review=null;form("");});
    }
    private void score(Trial take){
        if(review!=take)return;review=null;
        Trial earlier=previous;previous=take;showScore(take,earlier);
    }
    private void showScore(Trial take,Trial baseline){
        showingScore=true;shownComparison=baseline;
        PhraseCheckScore result=PhraseCheckScore.compare(take.phrase,take.heard);clear();line("Your dictation check",25);
        line(take.settings(),16);line(result.matches+" of "+result.words+" words matched · "+editCount(result.edits()),22);
        line("Line you read\n"+take.phrase,17);line("Recall heard\n"+(take.heard.isEmpty()?"No words recognized":take.heard),17);
        if(!result.differences.isEmpty())line(String.join("\n",result.differences),16);
        if(baseline!=null && PhraseCheckScore.samePhrase(baseline.phrase,take.phrase)){
            PhraseCheckScore earlier=PhraseCheckScore.compare(baseline.phrase,baseline.heard);
            int difference=result.edits()-earlier.edits();
            line("Previous take · "+baseline.settings()+" · "+editCount(earlier.edits())+"\n"+
                (difference==0?"Both takes had the same number of edits.":Math.abs(difference)+" "+(difference<0?"fewer":"more")+" word "+(Math.abs(difference)==1?"edit":"edits")+" this time."),16);
            line("Previously heard\n"+(baseline.heard.isEmpty()?"No words recognized":baseline.heard),16);
        }
        line("Case and punctuation are ignored. Numbers compare as written. This result measures one take. It does not train a voice profile.",15);
        button("Try another delivery or lift",()->form("Keep the line to compare with this take."));
        button("Done",this::finish);
    }
    private static String editCount(int n){return n+" word "+(n==1?"edit":"edits");}
}
