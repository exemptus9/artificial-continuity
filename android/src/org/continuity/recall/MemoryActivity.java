package org.continuity.recall;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.*;

/** Native, source-first views over the existing encrypted vault. No second memory store. */
public final class MemoryActivity extends Activity {
  private static final int BG=0xff101c21, PANEL=0xff1b2b32, FG=0xffeef2ed,
      MUTED=0xffb3c4c8, ACCENT=0xffb5edae;
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private final Handler handler=new Handler();
  private LinearLayout body;
  private TextView liveStatus;
  private JSONObject state;
  private String section="timeline", query="", period="All time", project="", recordId="";
  private ArrayList<String> selected;
  private int limit=30, generation=0;
  private boolean saving=false, active=false;
  private final Runnable refreshStatus=new Runnable() {
    public void run() {
      if (!active) return;
      if (liveStatus!=null) liveStatus.setText(BackgroundVoiceService.statusText(MemoryActivity.this));
      handler.postDelayed(this,1000);
    }
  };
  interface Change { void run(JSONObject value) throws Exception; }
  private int dp(int n) { return (int)(getResources().getDisplayMetrics().density*n+.5f); }
  private LinearLayout column() { LinearLayout v=new LinearLayout(this); v.setOrientation(1); return v; }
  private TextView text(LinearLayout parent,String value,int size,int color) {
    TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
    t.setPadding(0,dp(6),0,dp(6)); t.setLineSpacing(dp(3),1f); parent.addView(t); return t;
  }
  private Button button(LinearLayout parent,String title,Runnable action) {
    Button b=new Button(this); b.setText(title); b.setAllCaps(false); b.setTextSize(16);
    b.setMinHeight(dp(52)); b.setPadding(dp(12),dp(8),dp(12),dp(8));
    parent.addView(b,new LinearLayout.LayoutParams(-1,-2)); b.setOnClickListener(v->action.run()); return b;
  }
  private LinearLayout card() {
    LinearLayout c=column(); c.setPadding(dp(16),dp(12),dp(16),dp(14));
    GradientDrawable shape=new GradientDrawable(); shape.setColor(PANEL); shape.setCornerRadius(dp(18));
    c.setBackground(shape); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
    p.setMargins(0,dp(8),0,dp(8)); body.addView(c,p); return c;
  }
  private void error(String message) {
    if (active && !isFinishing() && !isDestroyed()) new AlertDialog.Builder(this).setTitle("Couldn't complete that")
        .setMessage(message).setPositiveButton("OK",null).show();
  }
  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    section=getIntent().getStringExtra("section"); if(section==null)section="timeline";
    selected=getIntent().getStringArrayListExtra("source_ids");
    if(saved!=null){section=saved.getString("section",section);query=saved.getString("query","");
      period=saved.getString("period","All time");project=saved.getString("project","");recordId=saved.getString("record","");}
    shell("Opening your encrypted memory…");
  }
  @Override protected void onResume(){super.onResume();active=true;load();handler.post(refreshStatus);}
  @Override protected void onPause(){active=false;generation++;handler.removeCallbacks(refreshStatus);super.onPause();}
  @Override protected void onDestroy(){generation++;worker.shutdown();super.onDestroy();}
  @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);
    out.putString("section",section);out.putString("period",period);
    out.putString("project",project);out.putString("record",recordId);}
  private void shell(String subtitle) {
    liveStatus=null;
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
    body=column();body.setPadding(dp(20),dp(20),dp(20),dp(32));scroll.addView(body);setContentView(scroll);
    // API35+ edge-to-edge insets keep primary actions clear of system bars and the keyboard.
    body.setOnApplyWindowInsetsListener((v,insets)->{
      v.setPadding(dp(20)+insets.getSystemWindowInsetLeft(),dp(16)+insets.getSystemWindowInsetTop(),
          dp(20)+insets.getSystemWindowInsetRight(),dp(24)+insets.getSystemWindowInsetBottom());return insets;});
    body.requestApplyInsets();
    TextView brand=text(body,"TEMPORAL",13,ACCENT);brand.setLetterSpacing(.16f);
    text(body,section.equals("desk")?"Continuity desk":section.equals("privacy")?"Your privacy":"Your time, remembered",28,FG)
        .setTypeface(null,Typeface.BOLD);
    text(body,subtitle,15,MUTED);
    HorizontalScrollView nav=new HorizontalScrollView(this);nav.setHorizontalScrollBarEnabled(false);
    LinearLayout row=new LinearLayout(this);nav.addView(row);body.addView(nav);
    for(String tab:new String[]{"Now","Timeline","Desk","Privacy"}){
      Button b=new Button(this);b.setText(tab);b.setAllCaps(false);b.setMinHeight(dp(48));row.addView(b);
      b.setOnClickListener(v->{if(tab.equals("Now")){startActivity(new Intent(this,VoiceActivity.class));finish();return;}
        section=tab.equals("Desk")?"desk":tab.toLowerCase(Locale.ROOT);recordId="";selected=null;project="";load();});
    }
  }
  private void load() {
    final int ticket=++generation;
    worker.execute(()->{try{JSONObject snapshot=Vault.read(this);runOnUiThread(()->{
      if(ticket!=generation||!active||isFinishing()||isDestroyed())return;state=snapshot;render();});
      }catch(Exception ex){runOnUiThread(()->{if(ticket==generation&&!isDestroyed())error("The vault could not be read. It was not reset.");});}});
  }
  private void change(Change action,Runnable success) {
    if(saving)return;saving=true;
    worker.execute(()->{try{Vault.change(this,action::run);runOnUiThread(()->{saving=false;if(!isDestroyed()){
      try{success.run();}catch(RuntimeException ex){error("The change was saved. A follow-up control was unavailable; reopen this screen to check status.");}
      if(active)load();}});}
      catch(Exception ex){runOnUiThread(()->{saving=false;if(!isDestroyed())error(ex.getMessage()==null?"No change was saved.":ex.getMessage());});}});
  }
  private void render() {
    try{
      if(!recordId.isEmpty()){showRecord();return;}
      if(section.equals("privacy")){privacy();return;}
      if(section.equals("desk")){desk();return;}
      timeline();
    }catch(Exception ex){error(ex.getMessage()==null?"This view is unavailable. Original records were not changed.":ex.getMessage());}
  }
  private String when(long time){return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
      .format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a",Locale.getDefault()));}
  private String excerpt(String value){return value.length()>260?value.substring(0,260)+"…":value;}
  private void timeline() throws Exception {
    shell(selected==null?"Find a moment. Follow its sources. Keep the original.":"Original sources for your last answer.");
    if(state.getJSONObject("policy").getBoolean("paused"))text(body,"Capture is paused. Existing records remain available here.",15,ACCENT);
    if(!project.isEmpty())text(body,"Project "+project,17,ACCENT);
    EditText search=new EditText(this);search.setTextColor(FG);search.setHintTextColor(MUTED);
    search.setHint("Search original words");search.setText(query);search.setSingleLine(true);body.addView(search);
    button(body,"Find in timeline",()->{query=search.getText().toString();selected=null;limit=30;render();});
    HorizontalScrollView ribbon=new HorizontalScrollView(this);ribbon.setHorizontalScrollBarEnabled(false);
    LinearLayout dates=new LinearLayout(this);ribbon.addView(dates);body.addView(ribbon);
    for(String range:new String[]{"All time","Today","Yesterday","7 days","30 days"}){
      Button b=new Button(this);b.setAllCaps(false);b.setText(range.equals(period)?"● "+range:range);
      b.setMinHeight(dp(48));dates.addView(b);b.setOnClickListener(v->{period=range;selected=null;limit=30;render();});
    }
    LocalDate today=LocalDate.now();ZoneId zone=ZoneId.systemDefault();long since=0,until=Long.MAX_VALUE;
    if(!period.equals("All time")){
      int back=period.equals("Yesterday")?1:period.equals("7 days")?6:period.equals("30 days")?29:0;
      since=today.minusDays(back).atStartOfDay(zone).toInstant().toEpochMilli();
      until=(period.equals("Yesterday")?today:today.plusDays(1)).atStartOfDay(zone).toInstant().toEpochMilli();
    }
    List<JSONObject> rows=MemoryWorkbench.records(state,query,since,until,project);
    if(selected!=null)rows.removeIf(e->!selected.contains(e.optString("id")));
    text(body,rows.size()+" saved records · Times shown in "+zone.getId(),13,MUTED);
    if(rows.isEmpty()){
      LinearLayout c=card();text(c,"No matching moments",21,FG);
      text(c,"A gap in capture is a gap in evidence. It doesn't mean nothing happened.",16,MUTED);
      button(c,"Capture a thought",()->startActivity(new Intent(this,VoiceActivity.class)));return;
    }
    String lastDay="";long previous=-1;int shown=0;
    for(JSONObject e:rows){
      if(shown++>=limit)break;long time=e.getLong("event_ms");
      String day=Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toString();
      if(!day.equals(lastDay)){
        if(previous>0&&previous-time>86400000L)text(body,"Unrecorded interval · no activity inferred",12,MUTED);
        text(body,day,16,ACCENT).setTypeface(null,Typeface.BOLD);lastDay=day;
      }
      LinearLayout c=card();text(c,when(time)+" · "+e.getString("source_type"),13,MUTED);
      String value=e.optString("text");text(c,value.isEmpty()?"Audio or attachment · no searchable transcript":excerpt(value),18,FG);
      text(c,labelStatus(e)+" · "+e.optString("capture_method"),12,MUTED);
      String id=e.getString("id");button(c,"Open original & context",()->{recordId=id;render();});previous=time;
    }
    if(rows.size()>limit)button(body,"Show 30 more",()->{limit+=30;render();});
  }
  private String labelStatus(JSONObject e) throws Exception {
    if(e.optString("capture_method").equals("user-review"))return "Owner review · "+e.getJSONObject("metadata").optString("epistemic_status");
    return MemoryWorkbench.status(state,e.getString("id"));
  }
  private void showRecord() throws Exception {
    JSONObject e=state.getJSONObject("events").optJSONObject(recordId);
    if(e==null||!MemoryWorkbench.visible(state,e)){recordId="";timeline();return;}
    shell("Source evidence and your interpretation remain separate.");
    button(body,"Back to timeline",()->{recordId="";section="timeline";render();});
    LinearLayout original=card();text(original,when(e.getLong("event_ms")),14,ACCENT);
    text(original,e.optString("text").isEmpty()?"No text transcript. Open archive tools to inspect the attachment.":e.getString("text"),20,FG).setTextIsSelectable(true);
    text(original,"Original preserved · "+e.getString("capture_method"),13,MUTED);
    JSONObject meta=e.getJSONObject("metadata");
    if(meta.has("transcription"))text(original,"Transcript: "+meta.optString("transcription"),13,MUTED);
    text(original,"Review status: "+labelStatus(e),14,ACCENT);
    if(meta.has("source_id")){
      String source=meta.getString("source_id");button(original,"View linked source",()->{recordId=source;render();});
    }
    final String id=e.getString("id");
    button(body,"Add interpretation or next step",()->reviewDialog(id));
    List<JSONObject> reviews=MemoryWorkbench.reviews(state,id);
    for(JSONObject r:reviews){LinearLayout c=card();
      text(c,"OWNER REVIEW · "+r.getJSONObject("metadata").optString("epistemic_status"),12,ACCENT);
      text(c,r.getString("text"),17,FG);text(c,when(r.getLong("event_ms"))+" · Original unchanged",12,MUTED);
    }
    button(body,"Inspect provenance",()->new AlertDialog.Builder(this).setTitle("Source receipt")
        .setMessage("Record: "+id+"\nDevice: "+e.optString("device_id")+"\nSource timezone: "+e.optString("timezone")
            +"\nText SHA-256: "+e.optString("text_sha256")+"\n\n"+meta.toString()
            +"\n\nA valid hash verifies stored bytes, not whether a claim is true.")
        .setPositiveButton("Done",null).show());
    button(body,"Archive, share or delete this record",()->startActivity(new Intent(this,MainActivity.class).putExtra("record_id",id)));
  }
  private void reviewDialog(String id) {
    try{
      LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),dp(8));
      text(form,"Add your interpretation, decision or next step. The original words stay unchanged.",15,MUTED);
      EditText input=new EditText(this);input.setTextColor(FG);input.setHintTextColor(MUTED);input.setHint("What should future you know?");input.setMinLines(3);form.addView(input);
      Spinner kind=new Spinner(this);String[] kinds={"observation","inference","decision","intention","next_step","blocker","canonical"};
      ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,kinds);kind.setAdapter(adapter);kind.setSelection(1);form.addView(kind);
      JSONObject annotation=state.getJSONObject("annotations").getJSONObject(id);
      EditText pid=new EditText(this);pid.setTextColor(FG);pid.setHintTextColor(MUTED);pid.setSingleLine(true);
      pid.setHint("Project ID, e.g. P29 (optional)");pid.setText(annotation.optString("project_id"));form.addView(pid);
      CheckBox important=new CheckBox(this);important.setText("Keep this prominent");important.setTextColor(FG);important.setChecked(annotation.optBoolean("important"));form.addView(important);
      text(form,"“Canonical” records your designation; it does not rewrite a manuscript. A decision is established only because you explicitly label it.",12,MUTED);
      AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Keep the source. Add context.")
          .setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save review",null).create();
      dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
        String words=input.getText().toString();String projectId=pid.getText().toString().trim();
        if(words.trim().isEmpty()){input.setError("Add an interpretation first");return;}
        if(!projectId.isEmpty()&&!projectId.matches("P[0-9]{1,4}")){pid.setError("Use an established ID such as P29");return;}
        String status=kind.getSelectedItem().toString();boolean star=important.isChecked();
        change(s->MemoryWorkbench.review(s,id,projectId,status,star,words,System.currentTimeMillis()),dialog::dismiss);
      }));dialog.show();
    }catch(Exception ex){error("This source is no longer available.");}
  }
  private void desk() throws Exception {
    shell("Resume from recorded decisions and next steps.");
    JSONArray projects=MemoryWorkbench.desk(state);
    if(projects.length()==0){LinearLayout c=card();text(c,"Give an idea somewhere to return to",22,FG);
      text(c,"Open a source, add a review and assign an established project ID. Label a decision, next step or blocker to place it here.",16,MUTED);
      button(c,"Choose a source",()->{section="timeline";render();});return;}
    for(int i=0;i<projects.length();i++){
      JSONObject p=projects.getJSONObject(i);LinearLayout c=card();String pid=p.getString("project_id");
      text(c,pid,23,ACCENT);text(c,"Last recorded activity · "+when(p.getLong("last_activity_ms")),13,MUTED);
      for(String[] key:new String[][]{{"decision_id","Latest explicit decision"},{"next_step_id","Next step"},{"blocker_id","Recorded blocker"}}){
        String id=p.optString(key[0]);JSONObject r=state.getJSONObject("events").optJSONObject(id);
        text(c,key[1],13,ACCENT);text(c,r==null?"None explicitly recorded":excerpt(r.optString("text")),16,r==null?MUTED:FG);
        if(r!=null)button(c,"Inspect "+key[1].toLowerCase(Locale.ROOT),()->{recordId=id;render();});
      }
      button(c,"Return to "+pid,()->{project=pid;section="timeline";query="";period="All time";selected=null;render();});
    }
    text(body,"These are owner-labelled records, not an AI status report. A later update may replace a prior interpretation; original evidence is retained.",13,MUTED);
  }
  private void privacy() throws Exception {
    shell("Clear controls. Visible capture. You hold the switch.");
    JSONObject policy=state.getJSONObject("policy");boolean paused=policy.getBoolean("paused");
    LinearLayout c=card();liveStatus=text(c,BackgroundVoiceService.statusText(this),19,ACCENT);
    text(c,paused?"All new capture is paused.":"Capture is enabled only through your chosen controls.",16,FG);
    button(c,paused?"Resume capture":"Pause & discard active recording",()->{
      // Send the immediate control while this activity is visible. Persisted pause
      // remains the authoritative guard if a service starts concurrently.
      if(!paused&&BackgroundVoiceService.running)try{
        startService(new Intent(this,BackgroundVoiceService.class).setAction(BackgroundVoiceService.ACTION_CANCEL));
      }catch(RuntimeException ex){error("Immediate recorder control failed. The saved pause policy will stop capture.");}
      change(s->Core.pause(s,!paused,System.currentTimeMillis()),()->{
        try{UsageJob.schedule(this);}catch(Exception ex){error("Capture policy saved; metadata scheduling failed.");}
      });
    });
    button(c,"Stop & save background audio",()->{startService(new Intent(this,BackgroundVoiceService.class).setAction(BackgroundVoiceService.ACTION_STOP));
      liveStatus.setText("Stop requested. The recorder will report the result.");});
    LinearLayout flow=card();text(flow,"Where your words go",20,FG);
    text(flow,"Offline recall · No connected AI or automatic sync\n\nText and finalized audio → encrypted vault on this phone.\nRecognition → installed Android on-device recognizer only.\nSpeech output → installed local voice only.\nSharing → only after you choose the content and destination.",16,MUTED);
    text(flow,"Active and interrupted audio uses an app-private temporary file before encrypted saving. Device protection still matters. There is no separate app lock.",15,ACCENT);
    text(flow,"Captured records: "+state.getJSONObject("events").length()+"\nAutomatic app metadata: "+(policy.getBoolean("usage")?"enabled for selected apps":"off")
        +"\nNotification metadata: "+(policy.getBoolean("notifications")?"enabled for selected apps":"off"),14,MUTED);
    button(body,"Create an encrypted backup",()->startActivity(new Intent(this,MainActivity.class).putExtra("section","backup")));
    button(body,"Retention, restore & forget controls",()->startActivity(new Intent(this,MainActivity.class).putExtra("section","controls")));
    text(body,"Deletion affects this installation and future deletion-aware restores. Files already exported or shared elsewhere cannot be recalled automatically. Review related sources before sharing.",14,MUTED);
    text(body,"Experimental 0.9 preview · Real speech, lock-screen capture, Bluetooth and battery behavior still need testing on your phone.",13,MUTED);
  }
}
