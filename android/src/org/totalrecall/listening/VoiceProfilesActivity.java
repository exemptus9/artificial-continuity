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
    private String name,source;
    private TextView sampleText;
    private Button confirm;
    private long deadline;
    private boolean training;
    private String suggestedName,suggestedSource;
    private final Runnable poll=new Runnable(){public void run(){
        if(!training)return;
        VoiceProfiles.Example example=VoiceProfiles.sample();
        if(example!=null){sampleText.setText("Did "+name+" say this?\n\n“"+example.text+"”");confirm.setEnabled(true);return;}
        if(!RecallService.isListening() || SystemClock.elapsedRealtime()>deadline){
            VoiceProfiles.cancel();training=false;home();toast("Sample timed out. Start listening and try again.");return;
        }
        main.postDelayed(this,300);
    }};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);if(state==null){suggestedName=getIntent().getStringExtra("suggestName");suggestedSource=getIntent().getStringExtra("suggestSource");}profiles=new VoiceProfiles(this);setTitle("Remember voices");
        ScrollView scroll=new ScrollView(this);page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        int pad=dp(16);page.setPadding(pad,pad,pad,pad);scroll.addView(page);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=35)scroll.setOnApplyWindowInsetsListener((v,i)->{
            android.graphics.Insets bars=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return i;
        });
    }
    @Override protected void onResume(){super.onResume();RecallOverlay.reviewVisible(this,true);home();
        if(suggestedSource!=null){VoiceProfiles.Profile existing=null;
            for(VoiceProfiles.Profile p:profiles.list())if(p.source.equals(suggestedSource) && (p.source.equals("Me")||p.name.equalsIgnoreCase(suggestedName)))existing=p;
            if(existing!=null)begin(existing,existing.name,existing.source);else create(suggestedSource.equals("Me"));
            suggestedName=null;suggestedSource=null;
        }
    }
    @Override protected void onPause(){endSample();RecallOverlay.reviewVisible(this,false);super.onPause();}
    private void endSample(){training=false;main.removeCallbacks(poll);VoiceProfiles.cancel();}
    private int dp(int n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private TextView line(String words,int size){TextView t=new TextView(this);t.setText(words);t.setTextSize(size);t.setPadding(0,dp(6),0,dp(6));page.addView(t);return t;}
    private Button action(String label,Runnable r){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setMinHeight(dp(48));page.addView(b);b.setOnClickListener(v->r.run());return b;}
    private void home(){
        endSample();page.removeAllViews();line("Remember voices",26);
        line("Teach the phone with a few clear phrases. Confirm only the speaker you want to remember. Saved voiceprints stay on this phone.",16);
        VoiceProfiles.Profile self=null;for(VoiceProfiles.Profile p:profiles.list())if(p.source.equals("Me"))self=p;
        final VoiceProfiles.Profile me=self;
        action(me==null?"Teach my voice":"Add a sample of my voice",()->{if(me==null)create(true);else begin(me,me.name,me.source);});
        action("Add another person or media voice",()->create(false));
        for(VoiceProfiles.Profile p:profiles.list())action(p.description()+" · "+p.examples.size()+" samples",()->options(p));
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
        LiveCapture.Snapshot live=LiveCapture.snapshot();
        if(!live.active || live.waiting || !live.groupsReady){
            String message=!live.active?"Start listening, then return here to teach a voice.":live.waiting?"The microphone is busy. Try again when listening resumes.":"Voice grouping is off or loading. Enable it under Live now and start a new listening session if needed.";
            new AlertDialog.Builder(this).setMessage(message).setPositiveButton("Open recorder",(d,w)->startActivity(new Intent(this,RecallListenActivity.class))).setNegativeButton("Close",null).show();return;
        }
        endSample();target=p;name=n;source=s;training=true;deadline=SystemClock.elapsedRealtime()+90000;
        page.removeAllViews();line("Teach "+name,25);line("Have one person speak a sentence for about five seconds, then pause. For better coverage, add two more phrases at your usual volume and distance.",16);
        sampleText=line("Listening for a clear phrase…",19);confirm=action("Use this sample",()->{
            VoiceProfiles.Example ex=VoiceProfiles.sample();if(ex==null)return;
            try{profiles.enroll(target==null?null:target.id,name,source,ex.voice);home();toast("Voice remembered on this phone.");}
            catch(RuntimeException err){toast(err.getMessage());}
        });confirm.setEnabled(false);
        action("Try another phrase",()->begin(target,name,source));action("Cancel",this::home);VoiceProfiles.listen();main.post(poll);
    }
}
