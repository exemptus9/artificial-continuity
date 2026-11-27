package org.totalrecall.listening;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;

/** The same foreground recorder supplies enrollment audio. Saving a sample requires a reviewed phrase. */
public final class VoiceProfilesActivity extends Activity {
    private VoiceProfiles profiles;
    private LinearLayout page;
    private final Handler main=new Handler(Looper.getMainLooper());
    private VoiceProfiles.Profile target;
    private String name,source,practiceTerm;
    private int practiceTurn;
    private TextView sampleText;
    private EditText intended;
    private Button confirm;
    private long deadline;
    private boolean training,pending,visible,permissionPending,startAfterPermission,startIssued,deniedMic;
    private long readinessDeadline;
    private TextView readiness;
    private static final int ENROLL_MIC=51;
    private final Runnable awaitReady=new Runnable(){public void run(){
        if(!visible || !pending || training)return;
        LiveCapture.Snapshot live=LiveCapture.snapshot();
        if(live.active && !live.waiting && live.groupsReady){begin(target,name,source);return;}
        if(SystemClock.elapsedRealtime()>=readinessDeadline){
            startIssued=false;
            showReadiness("Voice input is still unavailable. Check microphone access and voice grouping, then try again.",false);
            return;
        }
        String detail=live.waiting?live.state:live.active?"Preparing voice recognition…":
            startIssued?"Starting microphone…":"Listening is paused.";
        if(!detail.contentEquals(readiness.getText()))readiness.setText(detail);
        main.postDelayed(this,400);
    }};
    private String suggestedName,suggestedSource;
    private final Runnable poll=new Runnable(){public void run(){
        if(!training)return;
        VoiceProfiles.Example example=VoiceProfiles.sample();
        if(example!=null){
            VoiceProfiles.Match match=VoiceProfiles.identify(profiles.list(),example.voice);
            String status=match.profile!=null?"Matched "+match.profile.description():
                match.uncertain?"Voice match uncertain":"No remembered voice matched";
            if(practiceTerm==null)sampleText.setText("Did "+name+" say this?\n\n“"+example.text+"”\n\n"+status);
            else showPracticeReading(example.text,status);
            if(intended!=null){
                intended.setText(practiceTerm==null?example.text:VoiceWording.practiceFragment(example.text));
                intended.setVisibility(View.VISIBLE);
            }
            confirm.setEnabled(true);return;
        }
        String attempt=VoiceProfiles.attempt();
        if(!attempt.isEmpty() && !attempt.contentEquals(sampleText.getText()))sampleText.setText(attempt);
        if(!RecallService.isListening() || SystemClock.elapsedRealtime()>deadline){
            endSample();showReadiness("No usable sample arrived. Try the phrase again when the microphone is ready.",false);return;
        }
        main.postDelayed(this,300);
    }};
    private void showPracticeReading(String heard,String status){
        String prefix="Did "+name+" say this?\n\n“";
        android.text.SpannableString display=new android.text.SpannableString(prefix+heard+"”\n\n"+status);
        java.util.regex.Matcher words=java.util.regex.Pattern.compile("\\S+").matcher(heard);
        int[] anchor={-1,-1};
        while(words.find()){
            final int from=words.start(),until=words.end();
            display.setSpan(new android.text.style.ClickableSpan(){
                @Override public void onClick(View view){
                    if(anchor[0]<0){anchor[0]=from;anchor[1]=until;intended.setText(heard.substring(from,until));}
                    else{
                        intended.setText(heard.substring(Math.min(anchor[0],from),Math.max(anchor[1],until)));
                        anchor[0]=-1;
                    }
                }
            },prefix.length()+from,prefix.length()+until,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sampleText.setText(display);
        sampleText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);if(state==null){suggestedName=getIntent().getStringExtra("suggestName");suggestedSource=getIntent().getStringExtra("suggestSource");}profiles=new VoiceProfiles(this);setTitle("Remember voices");
        if(state!=null && state.getBoolean("pendingVoice")){
            String id=state.getString("profileId");
            if(id!=null)for(VoiceProfiles.Profile p:profiles.list())if(id.equals(p.id))target=p;
            pending=id==null || target!=null;
            name=state.getString("profileName");source=state.getString("profileSource");
            practiceTerm=state.getString("practiceTerm");practiceTurn=state.getInt("practiceTurn");
            deniedMic=state.getBoolean("deniedMic");
        }
        ScrollView scroll=new ScrollView(this);page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        int pad=dp(16);page.setPadding(pad,pad,pad,pad);scroll.addView(page);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=35)scroll.setOnApplyWindowInsetsListener((v,i)->{
            android.graphics.Insets bars=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return i;
        });
    }
    @Override protected void onResume(){super.onResume();visible=true;RecallOverlay.reviewVisible(this,true);
        if(pending){
            if(startAfterPermission){startAfterPermission=false;startForEnrollment();}
            else begin(target,name,source);
            return;
        }
        home();
        if(suggestedSource!=null){VoiceProfiles.Profile existing=null;
            for(VoiceProfiles.Profile p:profiles.list())if(p.source.equals(suggestedSource) && (p.source.equals("Me")||p.name.equalsIgnoreCase(suggestedName)))existing=p;
            if(existing!=null)begin(existing,existing.name,existing.source);else create(suggestedSource.equals("Me"));
            suggestedName=null;suggestedSource=null;
        }
    }
    @Override public void onWindowFocusChanged(boolean focus){
        super.onWindowFocusChanged(focus);
        if(focus && visible && pending && startAfterPermission){startAfterPermission=false;startForEnrollment();}
    }
    @Override protected void onPause(){visible=false;endSample();main.removeCallbacks(awaitReady);RecallOverlay.reviewVisible(this,false);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);out.putBoolean("pendingVoice",pending);
        if(pending){
            if(target!=null)out.putString("profileId",target.id);
            out.putString("profileName",name);out.putString("profileSource",source);
            out.putString("practiceTerm",practiceTerm);out.putInt("practiceTurn",practiceTurn);
            out.putBoolean("deniedMic",deniedMic);
        }
    }
    private void endSample(){training=false;main.removeCallbacks(poll);VoiceProfiles.cancel();}
    private int dp(int n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private TextView line(String words,int size){TextView t=new TextView(this);t.setText(words);t.setTextSize(size);t.setPadding(0,dp(6),0,dp(6));page.addView(t);return t;}
    private Button action(String label,Runnable r){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setMinHeight(dp(48));page.addView(b);b.setOnClickListener(v->r.run());return b;}
    private void home(){
        endSample();pending=false;startIssued=false;startAfterPermission=false;main.removeCallbacks(awaitReady);practiceTerm=null;page.removeAllViews();line("Remember voices",26);
        line("Teach the phone with a few clear phrases. Confirm only the speaker you want to remember. Saved voiceprints stay on this phone.",16);
        VoiceProfiles.Profile self=null;for(VoiceProfiles.Profile p:profiles.list())if(p.source.equals("Me"))self=p;
        final VoiceProfiles.Profile me=self;
        action(me==null?"Teach my voice":"Add a sample of my voice",()->{if(me==null)create(true);else begin(me,me.name,me.source);});
        if(me!=null)action("Practice a name or coined word",()->chooseTerm(me));
        if(me!=null && !me.wordings.isEmpty())action("My voice corrections · "+me.wordings.size(),()->wordings(me));
        action("Add another person or media voice",()->create(false));
        for(VoiceProfiles.Profile p:profiles.list())action(p.description()+" · "+p.examples.size()+" samples",()->options(p));
    }
    private void chooseTerm(VoiceProfiles.Profile p){
        EditText term=new EditText(this);
        term.setSingleLine(true);term.setHint("Preferred spelling");term.setContentDescription("Preferred spelling");
        term.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Which word should Recall learn?")
            .setMessage("Use the spelling you want in your transcript. You can teach several ways the phone hears it.")
            .setView(term).setPositiveButton("Hear me say it",null).setNegativeButton("Cancel",null).create();
        dialog.show();dialog.getButton(-1).setOnClickListener(v->{
            String value=term.getText().toString().trim();
            if(!SpeechContext.valid(value)){term.setError("Enter the word or short phrase.");return;}
            dialog.dismiss();practiceTerm=value;practiceTurn=0;begin(p,p.name,p.source);
        });
    }
    private void wordings(VoiceProfiles.Profile p){
        java.util.ArrayList<String> keys=new java.util.ArrayList<>(p.wordings.keySet());
        String[] labels=new String[keys.size()];
        for(int i=0;i<labels.length;i++)labels[i]=keys.get(i)+" → "+p.wordings.get(keys.get(i));
        new AlertDialog.Builder(this).setTitle("My voice corrections").setItems(labels,(dialog,i)->
            new AlertDialog.Builder(this).setMessage("Forget “"+labels[i]+"” for future transcripts?")
            .setPositiveButton("Forget",(d,w)->{profiles.forgetWording(p.id,keys.get(i));home();}).setNegativeButton("Keep",null).show()).setNegativeButton("Close",null).show();
    }
    private void options(VoiceProfiles.Profile p){
        new AlertDialog.Builder(this).setTitle(p.description()).setItems(new String[]{"Add a voice sample","Edit name or source","Forget this voiceprint"},(d,i)->{
            if(i==0)begin(p,p.name,p.source);
            else if(i==1)form(p,false);
            else new AlertDialog.Builder(this).setMessage("Forget "+p.name+" for future recognition? Earlier journal labels stay in their sessions.").setPositiveButton("Forget",(q,w)->{profiles.forget(p.id);home();}).setNegativeButton("Cancel",null).show();
        }).setNegativeButton("Close",null).show();
    }
    private void create(boolean me){form(null,me);}
    private void form(VoiceProfiles.Profile p,boolean me){
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(18),dp(8),dp(18),0);
        EditText title=new EditText(this);title.setSingleLine();title.setHint("Name or role");title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        title.setText(p!=null?p.name:suggestedName!=null&&!suggestedName.isEmpty()?suggestedName:me?"Me":"");fields.addView(title);
        String[] choices=p!=null?new String[]{"Me","Another person","Media"}:me?new String[]{"Me"}:new String[]{"Another person","Media"};
        Spinner kind=new Spinner(this);kind.setContentDescription("Voice source");kind.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices));fields.addView(kind);
        if(p==null && suggestedSource!=null)kind.setSelection(Math.max(0,java.util.Arrays.asList(choices).indexOf(suggestedSource)));
        if(p!=null)kind.setSelection(java.util.Arrays.asList(choices).indexOf(p.source));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(p==null?"New voice profile":"Edit voice profile").setView(fields).setPositiveButton(p==null?"Learn a phrase":"Save",null).setNegativeButton("Cancel",null).create();
        dialog.show();dialog.getButton(-1).setOnClickListener(v->{String n=title.getText().toString().trim(),s=(String)kind.getSelectedItem();
            try{VoiceProfiles.checkName(n);if(p!=null)profiles.rename(p.id,n,s);dialog.dismiss();if(p==null)begin(null,n,s);else home();}
            catch(RuntimeException e){toast(e.getMessage());}
        });
    }
    private void begin(VoiceProfiles.Profile p,String n,String s){
        endSample();main.removeCallbacks(awaitReady);target=p;name=n;source=s;pending=true;
        LiveCapture.Snapshot live=LiveCapture.snapshot();
        if(!live.active || live.waiting || !live.groupsReady){
            showReadiness(live.waiting?live.state:live.active?"Preparing voice recognition…":"Listening is paused.",true);return;
        }
        startIssued=false;training=true;deadline=SystemClock.elapsedRealtime()+90000;
        page.removeAllViews();line(practiceTerm==null?"Teach "+name:"Practice “"+practiceTerm+"”",25);
        line(practiceTerm==null?"Speak a sentence for about five seconds, then pause. Try your usual voice, then a softer or faster delivery on later samples.":
            "Say “"+VoiceWording.practicePrompt(practiceTerm,practiceTurn)+"” at a pace you normally use, then pause.",16);
        sampleText=line("Listening for a clear phrase…",19);intended=null;
        if(s.equals("Me")){
            line(practiceTerm==null?"Say a phrase you use often, including a name or coined word. If the phone mishears it, edit the words below before saving. Future lines matched to your voice can use that correction.":
                "Tap the first and last words of the misheard phrase, or edit it below. This spelling applies when your voice matches.",15);
            intended=new EditText(this);String hint=practiceTerm==null?"What I actually said":"What Recall heard for this word";
            intended.setHint(hint);intended.setContentDescription(hint);
            intended.setVisibility(View.GONE);page.addView(intended);
        }
        confirm=action("Use this sample",()->{
            VoiceProfiles.Example ex=VoiceProfiles.sample();if(ex==null)return;
            try{
                VoiceWording correction;
                if(practiceTerm!=null){
                    String heard=intended.getText().toString().trim().replaceAll("\\s+"," ");
                    if(!SpeechContext.valid(heard) || !VoiceWording.containsFragment(ex.text,heard)){
                        intended.setError("Choose the word or short phrase from the reading above.");return;
                    }
                    correction=heard.equals(practiceTerm)?null:new VoiceWording(heard,practiceTerm);
                }else correction=intended==null?null:VoiceWording.difference(ex.text,intended.getText().toString());
                String existing=target==null || correction==null?null:target.wordings.get(correction.heard.toLowerCase(java.util.Locale.ROOT));
                if(existing!=null && !existing.equals(correction.wanted)){
                    new AlertDialog.Builder(this).setTitle("Replace the earlier spelling?")
                        .setMessage("“"+correction.heard+"” currently uses “"+existing+"”. Use “"+correction.wanted+"” instead?")
                        .setPositiveButton("Replace",(d,w)->save(ex,correction)).setNegativeButton("Go back",null).show();
                }else save(ex,correction);
            }catch(IllegalArgumentException err){toast(err.getMessage());}
        });confirm.setEnabled(false);
        action("Try another phrase",()->begin(target,name,source));action("Cancel",this::home);VoiceProfiles.listen();main.post(poll);
    }
    private void showReadiness(String message,boolean watch){
        main.removeCallbacks(awaitReady);page.removeAllViews();
        line(practiceTerm==null?"Teach "+name:"Practice “"+practiceTerm+"”",25);
        readiness=line(message,17);
        if(!org.continuity.recall.ListenPolicy.allowed(this)){
            line("Enable capture in your phone vault, then return to this phrase.",15);
            action("Open phone vault",()->startActivity(new Intent(this,org.continuity.recall.MainActivity.class)));
        }else if(!getSharedPreferences("recall-speech",0).getBoolean("voices",true)){
            line("Voice grouping must be on to compare an example with your saved voice.",15);
            action("Enable voice grouping",()->{
                getSharedPreferences("recall-speech",0).edit().putBoolean("voices",true).apply();
                if(RecallService.isListening())
                    showReadiness("Voice grouping enabled. Pause and restart from the recorder when your current recording is finished.",false);
                else begin(target,name,source);
            });
        }else if(!RecallService.isListening() && !startIssued){
            line("Starting uses your usual listening and archive settings. This phrase stays ready here.",15);
            action("Start listening and continue",this::startForEnrollment);
        }else if(!watch)action("Check again",()->begin(target,name,source));
        if(deniedMic && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            action("App permissions",()->startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:"+getPackageName()))));
        if(!watch || RecallService.isListening())action("Open recorder",()->startActivity(new Intent(this,RecallListenActivity.class)));
        action("Cancel",this::home);
        if(watch && (startIssued || RecallService.isListening())){
            readinessDeadline=SystemClock.elapsedRealtime()+30000;main.postDelayed(awaitReady,400);
        }
    }
    private void startForEnrollment(){
        if(!visible || !pending || permissionPending)return;
        if(!org.continuity.recall.ListenPolicy.allowed(this)){showReadiness("Capture is paused in your phone vault.",false);return;}
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            permissionPending=true;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},ENROLL_MIC);
            return;
        }
        if(RecallService.isListening()){begin(target,name,source);return;}
        try{
            RecallService.command(this,RecallService.START);startIssued=true;
            showReadiness("Starting microphone…",true);
        }catch(RuntimeException failed){startIssued=false;showReadiness("The microphone could not start. Check access and retry.",false);}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=ENROLL_MIC)return;
        permissionPending=false;
        if(!pending)return;
        deniedMic=results.length==0 || results[0]!=android.content.pm.PackageManager.PERMISSION_GRANTED;
        if(!deniedMic){
            if(visible && hasWindowFocus())startForEnrollment();else startAfterPermission=true;
        }else{
            showReadiness("Microphone permission is required to hear this phrase.",false);
        }
    }
    private void save(VoiceProfiles.Example example,VoiceWording correction){
        try{
            profiles.enroll(target==null?null:target.id,name,source,example.voice,correction);
        }catch(RuntimeException err){toast(err.getMessage());return;}
        if(practiceTerm==null){
            home();toast(correction==null?"Voice remembered on this phone.":"Learned for your voice: "+correction.heard+" → "+correction.wanted);
            return;
        }
        String spelling=practiceTerm;
        VoiceProfiles.Profile fresh=null;
        for(VoiceProfiles.Profile p:profiles.list())if(p.id.equals(target.id))fresh=p;
        endSample();pending=false;page.removeAllViews();
        line(correction==null?"This reading already matches":"Spelling saved for your voice",22);
        line(correction==null?spelling:correction.heard+" → "+spelling,19);
        if(!getSharedPreferences("recall-speech",0).getBoolean("contextSpelling",true))
            line("Context spelling is off. Enable it under Capture choices to use your saved spellings.",15);
        if(fresh!=null){
            final VoiceProfiles.Profile updated=fresh;
            action("Try another delivery",()->{practiceTurn++;begin(updated,updated.name,updated.source);});
        }
        action("Done",this::home);
    }
}
