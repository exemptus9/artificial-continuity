package org.continuity.recall;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.view.inputmethod.InputMethodManager;
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
  private LinearLayout body;
  private JSONObject state;
  private String section="timeline", query="", period="All time", project="", recordId="", kind="All captures", askText="";
  private TemporalEngine.Reply answer;
  private LinearLayout answerBox;
  private ArrayList<String> selected;
  private int limit=30, generation=0;
  private boolean saving=false, active=false;
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
    b.setTextColor(FG); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff30454b));
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
    recordId=getIntent().getStringExtra("record_id");if(recordId==null)recordId="";
    String initialKind=getIntent().getStringExtra("kind");if(initialKind!=null)kind=initialKind;
    selected=getIntent().getStringArrayListExtra("source_ids");
    if(saved!=null){section=saved.getString("section",section);query=saved.getString("query","");
      period=saved.getString("period","All time");project=saved.getString("project","");recordId=saved.getString("record","");kind=saved.getString("kind","All captures");askText=saved.getString("ask","");}
    shell("Opening your encrypted memory…");
  }
  @Override protected void onResume(){super.onResume();active=true;load();}
  @Override protected void onPause(){active=false;generation++;super.onPause();}
  @Override protected void onDestroy(){generation++;worker.shutdown();super.onDestroy();}
  @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);
    out.putString("section",section);out.putString("period",period);out.putString("query",query);out.putString("kind",kind);out.putString("ask",askText);
    out.putString("project",project);out.putString("record",recordId);}
  private void shell(String subtitle) {
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
    body=column();body.setPadding(dp(20),dp(20),dp(20),dp(32));scroll.addView(body);setContentView(scroll);
    // API35+ edge-to-edge insets keep primary actions clear of system bars and the keyboard.
    body.setOnApplyWindowInsetsListener((v,insets)->{
      v.setPadding(dp(20)+insets.getSystemWindowInsetLeft(),dp(16)+insets.getSystemWindowInsetTop(),
          dp(20)+insets.getSystemWindowInsetRight(),dp(24)+insets.getSystemWindowInsetBottom());return insets;});
    body.requestApplyInsets();
    TextView brand=text(body,"TOTAL RECALL",13,ACCENT);brand.setLetterSpacing(.16f);
    text(body,section.equals("desk")?"Resume My Thread":section.equals("ask")?"Ask your saved notes":"Find your captures",28,FG)
        .setTypeface(null,Typeface.BOLD);
    text(body,subtitle,15,MUTED);
    HorizontalScrollView nav=new HorizontalScrollView(this);nav.setHorizontalScrollBarEnabled(false);
    LinearLayout row=new LinearLayout(this);nav.addView(row);body.addView(nav);
    for(String tab:new String[]{"Capture","Find","Threads","Ask"}){
      Button b=new Button(this);b.setText(tab);b.setAllCaps(false);b.setMinHeight(dp(48));
      String target=tab.equals("Find")?"timeline":tab.equals("Threads")?"desk":tab.toLowerCase(Locale.ROOT);
      boolean chosen=target.equals(section);b.setTextColor(chosen?BG:FG);
      b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(chosen?ACCENT:0xff30454b));row.addView(b);
      b.setOnClickListener(v->{if(tab.equals("Capture")){startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();return;}
        section=target;recordId="";selected=null;project="";answer=null;load();});
    }
  }
  private void load() {
    final int ticket=++generation;
    worker.execute(()->{try{JSONObject snapshot=Vault.read(this);runOnUiThread(()->{
      if(ticket!=generation||!active||isFinishing()||isDestroyed())return;state=snapshot;answer=null;render();});
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
      if(section.equals("ask")){ask();return;}
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
      b.setMinHeight(dp(48));b.setTextColor(range.equals(period)?BG:FG);
      b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(range.equals(period)?ACCENT:0xff30454b));dates.addView(b);b.setOnClickListener(v->{period=range;selected=null;limit=30;render();});
    }
    HorizontalScrollView kinds=new HorizontalScrollView(this);kinds.setHorizontalScrollBarEnabled(false);
    LinearLayout choices=new LinearLayout(this);kinds.addView(choices);body.addView(kinds);
    for(String option:new String[]{"All captures","Inbox","Important","Links","Voice","Files"}){
      Button b=new Button(this);b.setText(option);b.setAllCaps(false);b.setMinHeight(dp(48));
      b.setTextColor(option.equals(kind)?BG:FG);b.setBackgroundTintList(ColorStateList.valueOf(option.equals(kind)?ACCENT:0xff30454b));
      choices.addView(b);b.setOnClickListener(v->{kind=option;limit=30;selected=null;render();});
    }
    LocalDate today=LocalDate.now();ZoneId zone=ZoneId.systemDefault();long since=0,until=Long.MAX_VALUE;
    if(!period.equals("All time")){
      int back=period.equals("Yesterday")?1:period.equals("7 days")?6:period.equals("30 days")?29:0;
      since=today.minusDays(back).atStartOfDay(zone).toInstant().toEpochMilli();
      until=(period.equals("Yesterday")?today:today.plusDays(1)).atStartOfDay(zone).toInstant().toEpochMilli();
    }
    List<JSONObject> rows=MemoryWorkbench.filteredRecords(state,query,since,until,project,kind);
    if(selected!=null)rows.removeIf(e->!selected.contains(e.optString("id")));
    text(body,rows.size()+(rows.size()==1?" saved record · Times shown in ":" saved records · Times shown in ")+zone.getId(),13,MUTED);
    text(body,"Intentional captures and your reviews. App activity is available separately in All events; it is not evidence of work or attention.",13,MUTED);
    if(!rows.isEmpty())button(body,"Share this view with ChatGPT (preview)",()->preview(rows));
    button(body,"All events & advanced filters",()->startActivity(new Intent(this,MainActivity.class)
        .putExtra("section","timeline").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)));
    if(rows.isEmpty()){
      LinearLayout c=card();text(c,"No matching moments",21,FG);
      text(c,"A gap in capture is a gap in evidence. It doesn't mean nothing happened.",16,MUTED);
      button(c,"Capture a thought",()->startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)));return;
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
      text(c,labelStatus(e)+" · "+captureLabel(e),12,MUTED);
      String id=e.getString("id");button(c,"Open original & context",()->{recordId=id;render();});previous=time;
    }
    if(rows.size()>limit)button(body,"Show 30 more",()->{limit+=30;render();});
  }
  private String labelStatus(JSONObject e) throws Exception {
    if(e.optString("capture_method").equals("user-review"))return "Owner review · "+e.getJSONObject("metadata").optString("epistemic_status").replace('_',' ');
    String status=MemoryWorkbench.status(state,e.getString("id"));
    return status.equals("unreviewed")?(state.getJSONObject("annotations").getJSONObject(e.getString("id")).optBoolean("reviewed")?"Reviewed":"Not yet reviewed"):status.replace('_',' ');
  }
  private String captureLabel(JSONObject e) {
    String method=e.optString("capture_method");
    if(method.equals("user-review"))return "Your interpretation";
    if(method.equals("android-on-device-speech"))return "Speech transcript";
    if(method.equals("temporal-typed-input")||method.equals("manual-text"))return "Typed note";
    if(method.contains("microphone"))return "Recorded audio";
    if(method.equals("android-share"))return "Shared to this app";
    if(method.equals("user-revision"))return "Linked revision";
    return "Preserved source";
  }
  private void showRecord() throws Exception {
    JSONObject e=state.getJSONObject("events").optJSONObject(recordId);
    if(e==null||!MemoryWorkbench.visible(state,e)){recordId="";timeline();return;}
    shell("Source evidence and your interpretation remain separate.");
    button(body,"Back to timeline",()->{recordId="";section="timeline";render();});
    LinearLayout original=card();text(original,when(e.getLong("event_ms")),14,ACCENT);
    text(original,e.optString("text").isEmpty()?"No text transcript. Open archive tools to inspect the attachment.":e.getString("text"),20,FG).setTextIsSelectable(true);
    text(original,"Original preserved · "+captureLabel(e),13,MUTED);
    JSONObject meta=e.getJSONObject("metadata");
    String transcript=meta.optString("transcription");
    if(transcript.equals("unavailable"))text(original,"No transcript is available.",13,MUTED);
    else if(!transcript.isEmpty()&&!transcript.equals("not-applicable"))
      text(original,"Machine transcript · received words preserved; accuracy unverified.",13,MUTED);
    text(original,"Review status: "+labelStatus(e),14,ACCENT);
    if(meta.has("source_id")){
      String source=meta.getString("source_id");button(original,"View linked source",()->{recordId=source;render();});
    }
    final String id=e.getString("id");
    if(!MemoryWorkbench.isReview(e)){
      JSONObject annotation=state.getJSONObject("annotations").getJSONObject(id);
      boolean reviewed=annotation.optBoolean("reviewed");
      button(body,reviewed?"Move back to inbox":"Mark reviewed",()->change(
          s->s.getJSONObject("annotations").getJSONObject(id).put("reviewed",!reviewed),()->{}));
      boolean important=annotation.optBoolean("important");
      button(body,important?"Remove important mark":"Mark important",()->change(
          s->s.getJSONObject("annotations").getJSONObject(id).put("important",!important),()->{}));
    }
    button(body,"Add interpretation or next step",()->reviewDialog(id));
    List<JSONObject> reviews=MemoryWorkbench.reviews(state,id);
    for(JSONObject r:reviews){LinearLayout c=card();
      text(c,"OWNER REVIEW · "+r.getJSONObject("metadata").optString("epistemic_status").replace('_',' '),12,ACCENT);
      text(c,r.getString("text"),17,FG);text(c,when(r.getLong("event_ms"))+" · Original unchanged",12,MUTED);
    }
    button(body,"Inspect provenance",()->new AlertDialog.Builder(this).setTitle("Source receipt")
        .setMessage("Record: "+id+"\nDevice: "+e.optString("device_id")+"\nSource timezone: "+e.optString("timezone")
            +"\nText SHA-256: "+e.optString("text_sha256")+"\n\n"+meta.toString()
            +"\n\nA valid hash verifies stored bytes, not whether a claim is true.")
        .setPositiveButton("Done",null).show());
    button(body,"Open audio, attachments & archive tools",()->startActivity(new Intent(this,MainActivity.class).putExtra("record_id",id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)));
  }
  private void reviewDialog(String id) {
    try{
      LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),dp(8));
      text(form,"Add your interpretation, decision or next step. The original words stay unchanged.",15,MUTED);
      EditText input=new EditText(this);input.setTextColor(FG);input.setHintTextColor(MUTED);input.setHint("What should future you know?");input.setContentDescription("Review text");input.setMinLines(3);form.addView(input);
      Spinner kind=new Spinner(this);String[] kinds={"observation","inference","decision","intention","next step","blocker","canonical"};
      ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,kinds);kind.setAdapter(adapter);kind.setSelection(1);kind.setContentDescription("Review kind");form.addView(kind);
      JSONObject annotation=state.getJSONObject("annotations").getJSONObject(id);
      EditText pid=new EditText(this);pid.setTextColor(FG);pid.setHintTextColor(MUTED);pid.setSingleLine(true);
      pid.setHint("Project ID, e.g. P29 (optional)");pid.setContentDescription("Existing project ID");pid.setText(annotation.optString("project_id"));form.addView(pid);
      CheckBox important=new CheckBox(this);important.setText("Keep this prominent");important.setTextColor(FG);important.setChecked(annotation.optBoolean("important"));form.addView(important);
      text(form,"“Canonical” records your designation; it does not rewrite a manuscript. A decision is established only because you explicitly label it.",12,MUTED);
      AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Keep the source. Add context.")
          .setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save review",null).create();
      dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
        String words=input.getText().toString();String projectId=pid.getText().toString().trim();
        if(words.trim().isEmpty()){input.setError("Add an interpretation first");return;}
        if(!projectId.isEmpty()&&!projectId.matches("P[0-9]{1,4}")){pid.setError("Use an established ID such as P29");return;}
        String status=kind.getSelectedItem().toString().replace(' ','_');boolean star=important.isChecked();
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
      JSONObject latest=state.getJSONObject("events").getJSONObject(p.getString("latest_source_id"));
      text(c,"Last saved source · "+p.getInt("record_count")+" captures",13,ACCENT);
      text(c,latest.optString("text").isEmpty()?"Audio or attachment; no transcript":excerpt(latest.getString("text")),17,FG);
      button(c,"Open last source",()->{recordId=latest.optString("id");render();});
      for(String[] key:new String[][]{{"decision_id","Latest explicit decision"},{"next_step_id","Next step"},{"blocker_id","Recorded blocker"}}){
        String id=p.optString(key[0]);JSONObject r=state.getJSONObject("events").optJSONObject(id);
        text(c,key[1],13,ACCENT);text(c,r==null?"None explicitly recorded":excerpt(r.optString("text")),16,r==null?MUTED:FG);
        if(r!=null)button(c,"Inspect "+key[1].toLowerCase(Locale.ROOT),()->{recordId=id;render();});
      }
      button(c,"Return to "+pid,()->{project=pid;section="timeline";query="";period="All time";selected=null;render();});
      button(c,"Preview context for "+pid,()->{
        try{preview(MemoryWorkbench.records(state,"",0,Long.MAX_VALUE,pid));}
        catch(Exception ex){error(ex.getMessage());}
      });
    }
    text(body,"These are your labelled records, not inferred progress. Each source’s latest review is its current classification. Earlier reviews remain accessible; a later label does not prove a task was completed.",13,MUTED);
  }

  private void preview(List<JSONObject> records){
    ArrayList<String> ids=new ArrayList<>();
    for(JSONObject e:records){if(ids.size()==30)break;ids.add(e.optString("id"));}
    startActivity(new Intent(this,MainActivity.class).putStringArrayListExtra("context_ids",ids)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
  }

  private void ask(){
    shell("Offline search with original sources. No model, API key or automatic upload.");
    text(body,"Try a topic and a day, or find the first saved mention. Questions here are not saved as notes.",15,MUTED);
    EditText input=new EditText(this);input.setTextColor(FG);input.setHintTextColor(MUTED);
    input.setHint("Find notes about a poem yesterday");input.setText(askText);input.setSingleLine(true);
    input.setContentDescription("Search question for saved notes");body.addView(input);
    button(body,"Search saved notes",()->runQuestion(input.getText().toString()));
    HorizontalScrollView ribbon=new HorizontalScrollView(this);LinearLayout row=new LinearLayout(this);
    ribbon.addView(row);body.addView(ribbon);
    for(String prompt:new String[]{"Today's notes","Yesterday's notes","Last note"}){
      Button b=new Button(this);b.setText(prompt);b.setAllCaps(false);b.setMinHeight(dp(48));row.addView(b);
      b.setOnClickListener(v->runQuestion(prompt));
    }
    text(body,"For an earliest mention: “first mention of lighthouse”. Matching is lexical, so use words you actually saved. Audio needs a transcript to be searched by its words.",13,MUTED);
    answerBox=card();
    if(answer==null){text(answerBox,"Ready when you are",21,FG);text(answerBox,"Results will include links to the saved sources. Nothing is inferred from app usage.",15,MUTED);return;}
    text(answerBox,"FROM YOUR SAVED NOTES",12,ACCENT);
    text(answerBox,answer.text,18,FG).setTextIsSelectable(true);
    if(!answer.scope.isEmpty())text(answerBox,answer.scope,13,ACCENT);
    if(!answer.uncertainty.isEmpty())text(answerBox,answer.uncertainty,13,MUTED);
    for(String id:answer.sourceIds){JSONObject source=state.optJSONObject("events").optJSONObject(id);
      if(source!=null)button(answerBox,"Open source · "+when(source.optLong("event_ms")),()->{recordId=id;render();});}
    if(!answer.sourceIds.isEmpty())button(answerBox,"Preview these sources for ChatGPT",()->{
      ArrayList<JSONObject> rows=new ArrayList<>();for(String id:answer.sourceIds){JSONObject e=state.optJSONObject("events").optJSONObject(id);if(e!=null)rows.add(e);}preview(rows);
    });
  }

  private void runQuestion(String question){
    askText=question;
    if(question.trim().isEmpty()){error("Enter a topic or choose one of the example questions.");return;}
    if(question.length()>1000){error("Keep the question under 1,000 characters.");return;}
    View focus=getCurrentFocus();if(focus!=null)((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);
    int ticket=++generation;answerBox.removeAllViews();text(answerBox,"Searching this phone…",18,ACCENT);
    worker.execute(()->{try{
      JSONObject snapshot=Vault.read(this);
      TemporalEngine.Reply found=TemporalEngine.recall(snapshot,question,System.currentTimeMillis(),ZoneId.systemDefault());
      runOnUiThread(()->{if(!active||isFinishing()||isDestroyed()||ticket!=generation)return;
        state=snapshot;answer=found;ask();answerBox.post(()->{
          if(answerBox==null||!active)return;Rect bounds=new Rect(0,0,answerBox.getWidth(),Math.min(answerBox.getHeight(),dp(260)));
          answerBox.requestRectangleOnScreen(bounds,false);answerBox.announceForAccessibility("Search complete. "+found.matchCount+" matching saved notes.");
        });
      });
    }catch(Exception ex){runOnUiThread(()->{if(ticket==generation)error("Search could not read the saved records. Your notes were not changed.");});}});
  }
}
