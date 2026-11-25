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
    private boolean busy;
    private String exportId;
    private MediaPlayer player;
    private TextView transferStatus;
    private final SharedPreferences.OnSharedPreferenceChangeListener change=(p,k)->{
        if("status".equals(k))runOnUiThread(()->{if(transferStatus!=null)transferStatus.setText(status());});
    };
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(state!=null){screen=state.getInt("screen",0);offset=state.getInt("offset",0);exportId=state.getString("exportId");}
        if(android.os.Build.VERSION.SDK_INT>=35)getWindow().getDecorView().setOnApplyWindowInsetsListener((v,i)->{
            android.graphics.Insets bar=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(bar.left,bar.top,bar.right,bar.bottom);return i;
        });
    }
    @Override protected void onResume(){super.onResume();ArchiveSync.prefs(this).registerOnSharedPreferenceChangeListener(change);render();}
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
    private String status(){return ArchiveSync.prefs(this).getString("status","No transfer queued");}
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
        button("Open archive website",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(ArchiveDestination.WEBSITE))));
        button("Full audio & phone storage",()->go(1));button("Browse phone audio",()->{offset=0;go(3);});
        button(host.isEmpty()?"Connect website or Mint":"Change archive · "+host,()->startActivity(new Intent(this,MintLinkActivity.class).putExtra("archiveOnly",true).putExtra("fromArchive",true)));
        if(!host.isEmpty())button("Disconnect archive",()->new AlertDialog.Builder(this).setTitle("Disconnect archive?").setMessage("Queued transfers will stop. Existing archive copies remain accessible on the server.").setNegativeButton("Cancel",null).setPositiveButton("Disconnect",(d,w)->{ArchiveSync.disconnect(this);render();}).show());
        button("Transfer choices",()->go(2));
        transferStatus=label(status(),16);
        if(MintSync.prefs(this).getBoolean("legacyUploads",true)&&!MintSync.prefs(this).getString("pair","").isEmpty()){
            label("Earlier saved-clip sync is active separately from these limits.",15);
            button("Move saved clips to these controls",()->new AlertDialog.Builder(this).setTitle("Use archive transfer controls?")
                .setMessage("Stop the earlier automatic clip sender. Use Preview transfer or enable automatic archive batches to send clips.")
                .setNegativeButton("Cancel",null).setPositiveButton("Switch",(d,w)->{
                    MintSync.prefs(this).edit().putBoolean("legacyUploads",false).commit();MintSync.cancel(this);render();}).show());
        }
        button("Preview transfer",()->review(false));
        button(ArchiveSync.auto(this)?"Review automatic transfers":"Set up automatic transfers",()->review(true));
        if(ArchiveSync.auto(this)||ArchiveSync.prefs(this).contains("manual"))button("Stop queued and automatic transfers",()->{
            ArchiveSync.stop(this);render();
        });
        if(!host.isEmpty())button("Check server space",()->serverSpace(tag));
        button("Done",this::finish);
    }
    private String host(){try{return new ArchiveDestination(ArchiveSync.pair(this)).host;}catch(Exception e){return "";}}
    private String limits(){return (ArchiveSync.wifi(this)?"Wi-Fi required":"Any available network")+" · "+
        (ArchiveSync.charging(this)?"charging required":"battery power allowed")+" · "+size(ArchiveSync.cap(this))+" per batch";}
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
        check("Require Wi-Fi",ArchiveSync.wifi(this),(b,on)->opt("wifi",on));
        check("Require charging",ArchiveSync.charging(this),(b,on)->opt("charging",on));
        button("Batch limit · "+size(ArchiveSync.cap(this)),()->pick("Maximum per transfer batch",new String[]{"8 MiB","32 MiB","128 MiB","256 MiB"},i->{
            ArchiveSync.changed(this);ArchiveSync.prefs(this).edit().putInt("capMiB",new int[]{8,32,128,256}[i]).commit();
        }));
        label("Automatic transfers run at most once per hour after a completed batch. Android may defer them. Up to 64 items fit in each batch.",15);
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
    private void review(boolean automatic){
        if(host().isEmpty()){say("Pair your private archive first.");return;}if(busy)return;
        final int tag=revision;busy=true;transferStatus.setText("Preparing an exact preview…");
        io.execute(()->{
            try{
                ArchiveSync.Plan plan=ArchiveSync.preview(this);String remote;
                try{remote=server(ArchiveSync.capacity(this));}catch(Exception e){remote="Server capacity unavailable · transfer waits for a verified connection and available space.";}
                int clips=0,full=0,journals=0;for(JSONObject item:plan.batch){switch(item.getString("kind")){case "clip": clips++;break;case "segment":full++;break;case "journal":journals++;break;}}
                String gate=ArchiveSync.constraints(this);
                String message="To "+host()+" · "+selected()+"\n\nPending: "+num(plan.totalCount)+" items · "+exact(plan.totalBytes)+
                    "\nThis batch: "+plan.batch.size()+" items · "+exact(plan.batchBytes)+"\n"+clips+" clips · "+full+" full-audio segments · "+journals+" journals"+
                    (plan.oversized==0?"":"\n"+plan.oversized+" items exceed the batch or server item limit.")+"\n\n"+limits()+
                    (gate.isEmpty()?"":"\n"+gate)+"\nPhone free "+size(getFilesDir().getUsableSpace())+" · keep "+ArchiveStore.reserveMiB(this)+" MiB free\n"+remote+
                    (automatic?"\n\nFuture eligible items of these types can transfer once per hour under these limits.":"\n\nOnly these reviewed revisions can transfer.");
                runOnUiThread(()->{
                    if(tag!=revision||isFinishing()||isDestroyed())return;
                    busy=false;transferStatus.setText(status());
                    AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(automatic?"Automatic archive transfers":"Confirm archive transfer")
                        .setMessage(message).setNegativeButton("Cancel",null);
                    if(automatic||!plan.batch.isEmpty())dialog.setPositiveButton(automatic?"Enable automatic transfers":"Queue this batch",(d,w)->{
                        try{
                            if(automatic){
                                if(!ArchiveSync.selected(this,"clip")&&!ArchiveSync.selected(this,"segment")&&!ArchiveSync.selected(this,"journal"))throw new IOException("Select content first.");
                                if(!plan.paired.equals(ArchiveSync.binding(this)))throw new IOException("Archive changed. Preview again.");
                                if(!ArchiveSync.prefs(this).edit().putBoolean("auto",true).commit())throw new IOException("Could not save consent");
                                ArchiveSync.schedule(this);
                            }else ArchiveSync.approve(this,plan);
                            render();
                        }catch(Exception e){fail(e);}
                    });dialog.show();
                });
            }catch(Exception e){runOnUiThread(()->{if(tag==revision){busy=false;transferStatus.setText(status());fail(e);}});}
        });
    }
    private void browse(int tag){
        title("Phone audio");TextView note=label("Reading segments…",16);
        io.execute(()->{
            try(ArchiveStore db=new ArchiveStore(this)){
                List<ArchiveStore.Segment> items=db.segments(26,offset);List<String> gaps=db.latestGaps(5);
                runOnUiThread(()->{
                    if(tag!=revision)return;
                    note.setText(items.isEmpty()?"No full-audio segments in this page.":"Select a segment for playback or export.");
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
