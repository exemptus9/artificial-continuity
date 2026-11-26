package org.totalrecall.listening;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.media.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Controls local capture independently of authenticated, consented transfers. */
public final class ArchiveSyncActivity extends Activity {
    private static final int PAPER=0xfff7f5ee, INK=0xff192c28, TEAL=0xff1d5549;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LinearLayout page;
    private int screen,revision,offset;
    private String selectedRecording;
    private boolean busy,advanced;
    private String exportId;
    private MediaPlayer player;
    private TextView transferStatus;
    private final SharedPreferences.OnSharedPreferenceChangeListener change=(p,k)->{
        if("status".equals(k)||"lastReceiptAt".equals(k))runOnUiThread(()->{if(transferStatus!=null)transferStatus.setText(status());});
    };
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        if(state==null)selectedRecording=getIntent().getStringExtra("recordingId");
        if(state==null && getIntent().getBooleanExtra("recordings",false))screen=3;
        if(state!=null){screen=state.getInt("screen",0);offset=state.getInt("offset",0);exportId=state.getString("exportId");}
        if(android.os.Build.VERSION.SDK_INT>=35)getWindow().getDecorView().setOnApplyWindowInsetsListener((v,i)->{
            android.graphics.Insets bar=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(bar.left,bar.top,bar.right,bar.bottom);return i;
        });
    }
    @Override protected void onResume(){super.onResume();ArchiveSync.prefs(this).registerOnSharedPreferenceChangeListener(change);ArchiveSync.schedule(this);render();}
    @Override protected void onPause(){revision++;ArchiveSync.prefs(this).unregisterOnSharedPreferenceChangeListener(change);releasePlayer();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putInt("screen",screen);b.putInt("offset",offset);b.putString("exportId",exportId);}
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
    @Override public void onBackPressed(){if(screen!=0)go(0);else super.onBackPressed();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void go(int n){screen=n;releasePlayer();render();}
    private TextView label(String s,int size){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(INK);v.setPadding(0,dp(6),0,dp(6));page.addView(v);return v;
    }
    private void title(String s){TextView t=label(s,29);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));}
    private Button button(String s,Runnable r){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(TEAL);b.setOnClickListener(v->r.run());page.addView(b);return b;
    }
    private void check(String s,boolean value,android.widget.CompoundButton.OnCheckedChangeListener listener){
        CheckBox b=new CheckBox(this);b.setTextColor(INK);b.setText(s);b.setChecked(value);b.setOnCheckedChangeListener(listener);page.addView(b);
    }
    private void fail(Exception e){Toast.makeText(this,e.getMessage()==null?"Could not complete request.":e.getMessage(),Toast.LENGTH_LONG).show();}
    private void say(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private String status(){return ArchiveSync.backupStatus(this)+"\n"+ArchiveSync.prefs(this).getString("status","No transfer queued");}
    private static String num(long n){return java.text.NumberFormat.getIntegerInstance().format(n);}
    private static String size(long n){
        if(n<1024)return n+" B";if(n<1048576)return String.format(Locale.getDefault(),"%.1f KiB",n/1024d);
        if(n<(1L<<30))return String.format(Locale.getDefault(),"%.1f MiB",n/1048576d);
        return String.format(Locale.getDefault(),"%.1f GiB",n/(double)(1L<<30));
    }
    private static String exact(long n){return num(n)+" bytes ("+size(n)+")";}
    private static String date(long ms){return DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(ms));}
    private void render(){
        final int tag=++revision;busy=false;transferStatus=null;
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(PAPER);page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(20),dp(16),dp(20),dp(30));scroll.addView(page);setContentView(scroll);
        if(screen!=0)button("‹  Archive & sync",()->go(0));
        if(screen==1){local();return;}if(screen==2){choices();return;}if(screen==3){browse(tag);return;}
        title("Archive & sync");
        String warning=LimitAlerts.message(this);
        if(!warning.isEmpty()){label(warning,18);button("Review phone storage",()->go(1));if(!host().isEmpty())button("Check server capacity",()->serverSpace(tag));}
        String host=host();
        label(host.isEmpty()?"Phone only":"Private archive · "+host,17);
        String local=ArchiveStore.enabled(this)?"Recording all audio during listening sessions":"Selective moments only";
        TextView usage=label(local+" · Checking phone storage…",16);
        io.execute(()->{
            try(ArchiveStore a=new ArchiveStore(this)){
                ArchiveStore.Summary s=a.summary();
                String message=local+"\n"+num(s.localCount)+" segments on phone · "+size(s.localBytes)+" used · "+size(getFilesDir().getUsableSpace())+" free"+
                    "\n"+num(s.pendingCount)+" full-audio segments awaiting a receipt"+(s.gapCount>0?" · "+num(s.gapCount)+" capture gaps":"")+
                    (s.lastIssue==null||s.lastIssue.isEmpty()?"":"\n"+s.lastIssue);
                runOnUiThread(()->{if(tag==revision)usage.setText(message);});
            }catch(Exception e){runOnUiThread(()->{if(tag==revision)fail(e);});}
        });
        transferStatus=label(status(),16);
        button("Browse recordings & analysis",()->{offset=0;go(3);});
        if(host.isEmpty())button("Connect website or Mint",()->startActivity(new Intent(this,MintLinkActivity.class).putExtra("archiveOnly",true).putExtra("fromArchive",true)));
        else {
            if(!ArchiveSync.auto(this))button("Set up automatic backup",()->review(true,false));
            button("Open archive website",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(ArchiveDestination.WEBSITE))));
            button("Set up continuous backup",this::guided);
        }
        button("Transfer choices",()->go(2));
        button(advanced?"Hide more options":"More backup options",()->{advanced=!advanced;render();});
        if(!advanced){button("Done",this::finish);return;}
        button("Full audio & phone storage",()->go(1));
        if(!host.isEmpty()){
            button("Change archive · "+host,()->startActivity(new Intent(this,MintLinkActivity.class).putExtra("archiveOnly",true).putExtra("fromArchive",true)));
            button("Disconnect archive",()->new AlertDialog.Builder(this).setTitle("Disconnect archive?").setMessage("Queued transfers will stop. Existing archive copies remain accessible on the server.").setNegativeButton("Cancel",null).setPositiveButton("Disconnect",(d,w)->{ArchiveSync.disconnect(this);render();}).show());
        }
        if(MintSync.prefs(this).getBoolean("legacyUploads",true)&&!MintSync.prefs(this).getString("pair","").isEmpty()){
            label("Earlier saved-clip sync is active separately from these limits.",15);
            button("Move saved clips to these controls",()->new AlertDialog.Builder(this).setTitle("Use archive transfer controls?")
                .setMessage("Stop the earlier automatic clip sender. Use Preview transfer or enable automatic archive batches to send clips.")
                .setNegativeButton("Cancel",null).setPositiveButton("Switch",(d,w)->{
                    MintSync.prefs(this).edit().putBoolean("legacyUploads",false).commit();MintSync.cancel(this);render();}).show());
        }
        button("Preview transfer",()->review(false,false));
        button("Preview all pending",()->review(false,true));
        button(ArchiveSync.auto(this)?"Review automatic transfers":"Set up automatic transfers",()->review(true,false));
        if(ArchiveSync.auto(this)||ArchiveSync.prefs(this).contains("manual"))button("Stop queued and automatic transfers",()->{
            ArchiveSync.stop(this);render();
        });
        if(!host.isEmpty())button("Check server space",()->serverSpace(tag));
        button("Done",this::finish);
    }
    private String host(){try{return new ArchiveDestination(ArchiveSync.pair(this)).host;}catch(Exception e){return "";}}
    private String limits(){return ArchiveSync.networkLabel(this)+" · "+
        (ArchiveSync.charging(this)?"charging required":"battery power allowed")+" · "+size(ArchiveSync.cap(this))+" and "+num(ArchiveSync.maxItems(this))+" items per batch";}
    private String selected(){
        List<String> a=new ArrayList<>();if(ArchiveSync.selected(this,"clip"))a.add("selected clips and edits");
        if(ArchiveSync.selected(this,"segment"))a.add("full audio");if(ArchiveSync.selected(this,"journal"))a.add("closed text journals");
        return a.isEmpty()?"No content selected":android.text.TextUtils.join(", ",a);
    }
    private void opt(String key,boolean value){
        ArchiveSync.changed(this);ArchiveSync.prefs(this).edit().putBoolean(key,value).commit();
    }
    private void choices(){
        title("Transfer choices");
        label("Changing a choice stops queued and automatic transfers. Confirm a new batch after changing limits.",15);
        check("Selected clips, timestamps, transcripts and edits",ArchiveSync.selected(this,"clip"),(b,on)->opt("clip",on));
        check("Full audio segments",ArchiveSync.selected(this,"segment"),(b,on)->opt("segment",on));
        check("Include closed text journals",ArchiveSync.selected(this,"journal"),(b,on)->{
            if(!on){opt("journal",false);return;}
            new AlertDialog.Builder(this).setTitle("Share transcript journals with your archive?")
                .setMessage("This includes recognized words outside selected clips. Only completed journal sessions within the batch limit are eligible.")
                .setPositiveButton("Include journals",(d,w)->opt("journal",true)).setNegativeButton("Cancel",(d,w)->render()).setOnCancelListener(d->render()).show();
        });
        button("Upload network · "+ArchiveSync.networkLabel(this),()->pick("Upload network",
            new String[]{"Wi-Fi only","Prefer Wi-Fi · mobile after 1 hour","Any network"},i->{
                ArchiveSync.changed(this);ArchiveSync.prefs(this).edit().putBoolean("wifi",i==0).putBoolean("preferWifi",i==1).remove("wifiWait").commit();
            }));
        check("Require charging",ArchiveSync.charging(this),(b,on)->opt("charging",on));
        button("Batch limit · "+size(ArchiveSync.cap(this)),()->pick("Maximum per transfer batch",new String[]{"8 MiB","32 MiB","128 MiB","256 MiB","512 MiB","1 GiB"},i->{
            ArchiveSync.changed(this);ArchiveSync.prefs(this).edit().putInt("capMiB",new int[]{8,32,128,256,512,1024}[i]).commit();
        }));
        button("Items per batch · "+num(ArchiveSync.maxItems(this)),()->pick("Maximum items per transfer batch",
            new String[]{"64","256","512","1,024","4,096"},i->{
                ArchiveSync.changed(this);ArchiveSync.prefs(this).edit().putInt("maxItems",new int[]{64,256,512,1024,4096}[i]).commit();
            }));
        button("Automatic batch spacing · "+ArchiveSync.minutes(this)+" min",()->pick("Minimum time between automatic batches",new String[]{"5 minutes","15 minutes","60 minutes"},i->{
            ArchiveSync.changed(this);ArchiveSync.prefs(this).edit().putInt("minutes",new int[]{5,15,60}[i]).remove("next").commit();
        }));
        label("Each batch uses the limits above. Shorter intervals can send more data per hour. Android may defer background work.",15);
    }
    private void pick(String title,String[] values,java.util.function.IntConsumer chosen){
        new AlertDialog.Builder(this).setTitle(title).setItems(values,(d,i)->{chosen.accept(i);render();}).setNegativeButton("Cancel",null).show();
    }
    private void local(){
        title("Full audio & phone storage");
        boolean on=ArchiveStore.enabled(this);int rate=ArchiveStore.bitrate(this);
        label("Full audio keeps continuous AAC while listening, alongside selected moments. Each segment has a seven-year retention target. Your phone budget limits local recording; the private archive needs enough disk space to keep seven years.",16);
        button(on?"Turn off full audio":"Enable full audio on this phone",()->{
            if(on){ArchiveStore.setEnabled(this,false);render();return;}
            new AlertDialog.Builder(this).setTitle("Keep full audio during listening sessions?")
                .setMessage("Everyone within microphone range may be recorded. Audio stays on your phone until you choose to transfer it. You can turn this off at any time.")
                .setNegativeButton("Cancel",null).setPositiveButton("Enable full audio",(d,w)->{ArchiveStore.setEnabled(this,true);render();}).show();
        });
        check("Skip near-silent blocks in new full audio",ArchiveStore.prune(this),(b,v)->ArchiveStore.setPrune(this,v));
        try(ArchiveStore store=new ArchiveStore(this)){label(store.savings(),15);}
        double perDay=rate*86400d/8;label(String.format(Locale.getDefault(),"%d kbps · %.2f GB per 24 hours · about %.2f TB for seven years of 24/7 audio before overhead.",
            rate/1000,perDay/1e9,perDay*365.2425*7/1e12),15);
        button("Audio quality · "+rate/1000+" kbps",()->pick("AAC bitrate",new String[]{"24 kbps · smaller","32 kbps · standard","48 kbps · higher quality"},i->ArchiveStore.setBitrate(this,new int[]{24000,32000,48000}[i])));
        button("Phone archive budget · "+ArchiveStore.budgetMiB(this)+" MiB",()->pick("Max space for full audio",new String[]{"512 MiB","2 GiB","8 GiB","32 GiB","64 GiB"},i->ArchiveStore.setBudgetMiB(this,new int[]{512,2048,8192,32768,65536}[i])));
        button("Keep free on phone · "+ArchiveStore.reserveMiB(this)+" MiB",()->pick("Minimum free space on phone",new String[]{"256 MiB","512 MiB","1 GiB","2 GiB","8 GiB"},i->ArchiveStore.setReserveMiB(this,new int[]{256,512,1024,2048,8192}[i])));
        check("Free space from oldest phone copies after a verified server receipt",ArchiveStore.evictSynced(this),(b,value)->ArchiveStore.setEvictSynced(this,value));
        button("Apply phone space limits now",()->io.execute(()->{
            try(ArchiveStore a=new ArchiveStore(this)){boolean room=a.maintain();runOnUiThread(()->say(room?"Room available for the next segment.":"Space limit reached. Pending segments stay on your phone."));}
            catch(Exception e){runOnUiThread(()->fail(e));}
        }));
    }
    private String server(JSONObject j)throws Exception{
        long quota=j.getLong("quotaBytes");
        return "Server · "+size(j.getLong("usedBytes"))+" used · "+size(j.getLong("availableBytes"))+" available\n"+
            "Disk free "+size(j.getLong("freeBytes"))+" · floor "+size(j.optLong("floorBytes",0))+" · reserved for pending transfers "+size(j.getLong("reservedBytes"))+
            "\nQuota "+(quota==0?"limited by disk":size(quota))+" · retention "+j.getInt("retentionYears")+" years";
    }
    private void serverSpace(int tag){
        if(busy)return;busy=true;say("Checking server space…");io.execute(()->{
            try{String result=server(ArchiveSync.capacity(this));runOnUiThread(()->{if(tag==revision)new AlertDialog.Builder(this).setTitle("Server storage").setMessage(result).setPositiveButton("Done",null).show();});}
            catch(Exception e){runOnUiThread(()->{if(tag==revision)fail(e);});}finally{runOnUiThread(()->{if(tag==revision)busy=false;});}
        });
    }
    private void guided(){
        final String destination,policy;
        try{destination=ArchiveSync.binding(this);policy=ArchiveSync.settings(this);}catch(Exception e){fail(e);return;}
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),0);
        TextView info=new TextView(this);info.setTextColor(INK);info.setText("Destination · "+host()+"\n\nRecord full microphone sessions as "+ArchiveStore.bitrate(this)/1000+
            " kbps AAC while listening, including people nearby. Keep selected moments as usual. Automatically transfer new and pending full audio.\n\nPer batch · "+limits()+
            "\nMinimum interval · "+ArchiveSync.minutes(this)+" minutes\nPhone budget · "+ArchiveStore.budgetMiB(this)+" MiB · keep "+ArchiveStore.reserveMiB(this)+" MiB free"+
            "\nSelected-clip transfers · "+(ArchiveSync.selected(this,"clip")?"on":"off")+
            "\nClosed text-journal transfers · "+(ArchiveSync.selected(this,"journal")?"on":"off")+
            "\n\nPending transfer approvals are replaced by these choices. Capture starts when you tap Start listening, or immediately during an active session.");
        box.addView(info);CheckBox free=new CheckBox(this);free.setText("Free oldest phone copies when space is tight, only after a verified server receipt");
        free.setChecked(ArchiveStore.evictSynced(this));box.addView(free);ScrollView scroll=new ScrollView(this);scroll.addView(box);
        new AlertDialog.Builder(this).setTitle("Set up continuous backup").setView(scroll).setNegativeButton("Cancel",null)
            .setPositiveButton("Enable continuous backup",(d,w)->{boolean evict=free.isChecked();submit(()->ArchiveSync.enableContinuous(this,destination,policy,evict));}).show();
    }
    private interface Change {void perform()throws Exception;}
    private void submit(Change change){
        if(busy)return;busy=true;int tag=revision;if(transferStatus!=null)transferStatus.setText("Saving transfer choices…");
        io.execute(()->{
            try{change.perform();runOnUiThread(()->{if(tag==revision)render();});}
            catch(Exception e){runOnUiThread(()->{if(tag==revision){busy=false;if(transferStatus!=null)transferStatus.setText(status());fail(e);}});}
        });
    }
    private void review(boolean automatic,boolean backlog){
        if(host().isEmpty()){say("Pair your private archive first.");return;}if(busy)return;
        final int tag=revision;busy=true;transferStatus.setText("Preparing an exact preview…");
        io.execute(()->{
            try{
                ArchiveSync.Plan plan=ArchiveSync.preview(this,backlog);String remote;
                boolean space=false;
                try{JSONObject response=ArchiveSync.capacity(this);space=plan.checkSpace(response.getLong("availableBytes"));remote=server(response);}
                catch(Exception e){remote="Server capacity unavailable · restore connection to check capacity.";}
                final boolean enough=space;
                int clips=0,full=0,journals=0;for(JSONObject item:plan.batch){switch(item.getString("kind")){case "clip": clips++;break;case "segment":full++;break;case "journal":journals++;break;}}
                String gate=ArchiveSync.constraints(this);
                String message="To "+host()+" · "+selected()+"\n\nPending: "+num(plan.totalCount)+" items · "+exact(plan.totalBytes)+
                    "\nThis batch: "+plan.batch.size()+" items · "+exact(plan.batchBytes)+"\n"+clips+" clips · "+full+" full-audio segments · "+journals+" journals"+
                    (plan.oversized==0?"":"\n"+plan.oversized+" items exceed the batch or server item limit.")+"\n\n"+(backlog?"One-time backlog limit · 1 GiB / 4,096 items. Wi-Fi and charging choices still apply.\nRegular limits · ":"")+limits()+
                    (gate.isEmpty()?"":"\n"+gate)+"\nPhone free "+size(getFilesDir().getUsableSpace())+" · keep "+ArchiveStore.reserveMiB(this)+" MiB free\n"+remote+
                    (backlog&&!enough?"\nLarge-batch confirmation requires enough available server space for the exact reviewed bytes.":"")+
                    (automatic?"\n\nFuture eligible items can transfer at most once every "+ArchiveSync.minutes(this)+" minutes under these per-batch limits.":"\n\nOnly these reviewed revisions can transfer.");
                runOnUiThread(()->{
                    if(tag!=revision||isFinishing()||isDestroyed())return;
                    busy=false;transferStatus.setText(status());
                    AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(automatic?"Automatic archive transfers":backlog?"Confirm one-time backlog":"Confirm archive transfer")
                        .setMessage(message).setNegativeButton("Cancel",null);
                    if(automatic||(!plan.batch.isEmpty()&&(!backlog||enough)))dialog.setPositiveButton(automatic?"Enable automatic transfers":backlog?"Queue this backlog":"Queue this batch",(d,w)->submit(()->{
                            if(automatic){
                                if(!ArchiveSync.selected(this,"clip")&&!ArchiveSync.selected(this,"segment")&&!ArchiveSync.selected(this,"journal"))throw new IOException("Select content first.");
                                if(!plan.paired.equals(ArchiveSync.binding(this))||!plan.settings.equals(ArchiveSync.settings(this)))throw new IOException("Archive changed. Preview again.");
                                if(!ArchiveSync.prefs(this).edit().putBoolean("auto",true).commit())throw new IOException("Could not save consent");
                                ArchiveSync.schedule(this);
                            }else ArchiveSync.approve(this,plan);
                    }));dialog.show();
                });
            }catch(Exception e){runOnUiThread(()->{if(tag==revision){busy=false;transferStatus.setText(status());fail(e);}});}
        });
    }
    private void browse(int tag){
        title("Recordings on this phone");button("Full audio settings",()->go(1));TextView note=label("Reading segments…",16);
        io.execute(()->{
            try(ArchiveStore db=new ArchiveStore(this)){
                List<ArchiveStore.Segment> items=db.segments(26,offset);List<String> gaps=db.latestGaps(5);
                runOnUiThread(()->{
                    if(tag!=revision)return;
                    note.setText(items.isEmpty()?(ArchiveStore.enabled(this)?"No finished recordings on this page yet. A recording appears when its segment closes.":"Full audio is off. Open Full audio settings to keep continuous recordings."):"Select a recording to play, analyze or export.");
                    if(selectedRecording!=null){
                        String id=selectedRecording;selectedRecording=null;
                        io.execute(()->{try(ArchiveStore local=new ArchiveStore(this)){ArchiveStore.Segment selected=local.get(id);
                            runOnUiThread(()->{if(tag==revision && selected!=null)detail(selected);});}});
                    }
                    for(int i=0;i<Math.min(25,items.size());i++){ArchiveStore.Segment s=items.get(i);
                        button(date(s.startMs)+" · "+Math.round(s.durationMs/1000d)+" s · "+size(s.bytes)+"\n"+s.status,()->detail(s));}
                    if(offset>0)button("Previous 25",()->{offset=Math.max(0,offset-25);render();});
                    if(items.size()>25)button("Next 25",()->{offset+=25;render();});
                    if(!gaps.isEmpty()){label("Recent capture gaps",18);for(String gap:gaps)label(gap,14);}
                });
            }catch(Exception e){runOnUiThread(()->{if(tag==revision)fail(e);});}
        });
    }
    private void detail(ArchiveStore.Segment s){
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(date(s.startMs))
            .setMessage(s.bitrate/1000+" kbps AAC · "+exact(s.bytes)+"\nRetain through "+date(s.retainUntilMs)+"\n"+s.status)
            .setNegativeButton("Close",null);
        if(s.local){
            LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.VERTICAL);
            TextView facts=new TextView(this);facts.setPadding(dp(20),dp(10),dp(20),dp(8));facts.setText(s.bitrate/1000+" kbps AAC · "+exact(s.bytes)+"\nRetain through "+date(s.retainUntilMs)+"\n"+s.status);actions.addView(facts);actions.addView(StoryNotes.button(this,StoryNotes.Kind.SEGMENT,s.id,""));
            Button analyze=new Button(this);analyze.setAllCaps(false);analyze.setText("Analyze recording");actions.addView(analyze);
            dialog.setMessage(null).setView(actions);
            analyze.setOnClickListener(v->AudioSituation.show(this,StoryNotes.Kind.SEGMENT,s.id,ex->{try(ArchiveStore db=new ArchiveStore(this);FileInputStream in=db.openForPlayback(s.id)){ex.setDataSource(in.getFD());}}));
            dialog.setPositiveButton("Play",(d,w)->play(s.id));
            dialog.setNeutralButton("Export AAC",(d,w)->{
                exportId=s.id;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/aac")
                    .putExtra(Intent.EXTRA_TITLE,"total-recall-"+s.startMs+".aac");startActivityForResult(i,81);
            });
        }dialog.show();
    }
    private void releasePlayer(){if(player!=null){player.release();player=null;}}
    private void play(String id){
        releasePlayer();final int tag=revision;
        io.execute(()->{
            try(ArchiveStore db=new ArchiveStore(this);FileInputStream in=db.openForPlayback(id)){
                MediaPlayer p=new MediaPlayer();
                try{p.setDataSource(in.getFD());p.prepare();}catch(Exception e){p.release();throw e;}
                runOnUiThread(()->{
                    if(tag!=revision||isDestroyed()||!hasWindowFocus()){p.release();return;}releasePlayer();player=p;
                    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Playing phone audio").setPositiveButton("Stop",null).create();
                    dialog.setOnDismissListener(d->{if(player==p)releasePlayer();});p.setOnCompletionListener(m->dialog.dismiss());
                    try{p.start();dialog.show();}catch(Exception e){releasePlayer();fail(e);}
                });
            }catch(Exception e){runOnUiThread(()->fail(e));}
        });
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);if(req!=81||result!=RESULT_OK||data==null||data.getData()==null||exportId==null)return;
        final Uri uri=data.getData();final String id=exportId;exportId=null;
        io.execute(()->{
            try(ArchiveStore db=new ArchiveStore(this);OutputStream out=getContentResolver().openOutputStream(uri,"w")){
                if(out==null)throw new IOException("Cannot open export destination");db.export(id,out);runOnUiThread(()->say("AAC exported."));
            }catch(Exception e){runOnUiThread(()->fail(e));}
        });
    }
}
