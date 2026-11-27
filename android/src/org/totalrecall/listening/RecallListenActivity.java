package org.totalrecall.listening;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Private on-device capture and review. All audio/database work stays off the UI thread. */
public final class RecallListenActivity extends Activity {
    private static final int MIC=31, EXPORT=32, RESTORE=33, JOURNAL_EXPORT=34;
    private static final int PAPER=0xfff7f5ee, INK=0xff192c28, SECOND=0xff435955, TEAL=0xff1d5549,
        PALE=0xffe5ede6, WHITE=Color.WHITE, BORDER=0xffc9d2ca, RED=0xff963d31;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<Store.Moment> items=new ArrayList<>();
    private final MomentsAdapter adapter=new MomentsAdapter();
    private final Runnable queryLater=this::readMoments;
    private final BroadcastReceiver observer=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent){refresh();}
    };
    private LinearLayout controls, manualActions;
    private TextView state, summary, firstUse, stableText, partialText, latestCatch, micLabel;
    private ProgressBar micMeter;
    private Button journalButton;
    private long journalExportId=-1;
    private boolean meterLive;
    private final Runnable tick=new Runnable(){public void run(){if(registered&&alive()){paintLive();if(android.os.SystemClock.elapsedRealtime()-archiveRefresh>=30000)readArchiveStatus();main.postDelayed(this,200);}}};

    private Button toggle, ask;
    private ListView list;
    private EditText search;
    private boolean reviewToday;
    private volatile int speechGeneration;
    private boolean searchRecording;
    private TextView listHeading;
    private Button daily;
    private ClipStudio studio;
    private TextView archiveStatus;
    private long archiveRefresh;
    private int generation;
    private long exportId=-1, requestedMoment=-1;
    private boolean registered, voicePermissionRequest, commandPending;
    private boolean exporting, importing;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                                     WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(INK);
        LinearLayout page=column(); page.setBackgroundColor(PAPER);
        if(Build.VERSION.SDK_INT>=30){
            page.setOnApplyWindowInsetsListener((v,i)->{
                android.graphics.Insets inset=i.getInsets(android.view.WindowInsets.Type.systemBars() |
                    android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                v.setPadding(inset.left,inset.top,inset.right,inset.bottom);return i;
            });
        }
        setContentView(page);
        list=new ListView(this); list.setDivider(null); list.setCacheColorHint(Color.TRANSPARENT);
        list.setHeaderDividersEnabled(false); list.setFooterDividersEnabled(false);
        list.setClipToPadding(false); list.setPadding(dp(16),dp(8),dp(16),dp(24));
        LinearLayout header=column();
        LinearLayout top=row();
        ImageView emblem=new ImageView(this);emblem.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int emblemId=getResources().getIdentifier("recall_emblem_ink","drawable",getPackageName());
        emblem.setImageResource(emblemId==0?getApplicationInfo().icon:emblemId);
        LinearLayout.LayoutParams logoSize=new LinearLayout.LayoutParams(dp(38),dp(38));logoSize.setMargins(0,0,dp(8),0);top.addView(emblem,logoSize);
        TextView title=text("Total Recall",28,INK,true);title.setTypeface(Typeface.SERIF,Typeface.BOLD);
        title.setPadding(dp(4),dp(12),0,dp(10));top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button menu=link("More");menu.setMinWidth(dp(48));menu.setMinimumWidth(dp(48));menu.setPadding(dp(8),0,dp(8),0);menu.setContentDescription("More listening and archive options");
        menu.setOnClickListener(v->more(menu));top.addView(menu);header.addView(top);
        controls=column();controls.setBackground(card(INK,0,18));pad(controls,18,16);
        LinearLayout identity=row();
        TextView label=text("Selective listening",20,WHITE,true);identity.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        micLabel=text("",13,0xffb3e5c5,true);identity.addView(micLabel);controls.addView(identity);
        state=text("",14,0xffc1d7ca,false);state.setPadding(0,dp(5),0,dp(5));
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);controls.addView(state);
        micMeter=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        micMeter.setIndeterminate(false);micMeter.setMax(100);
        micMeter.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffb3e5c5));
        micMeter.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff4d665f));
        micMeter.setContentDescription("Microphone input level");controls.addView(micMeter,new LinearLayout.LayoutParams(-1,dp(8)));
        firstUse=text("Speak naturally after starting. Recall keeps selected moments with their lead-in.",16,0xffe0ece3,false);
        firstUse.setPadding(0,dp(12),0,dp(8));controls.addView(firstUse);
        stableText=text("",22,WHITE,false);stableText.setTypeface(Typeface.SERIF);stableText.setMaxLines(6);
        stableText.setEllipsize(TextUtils.TruncateAt.END);stableText.setPadding(0,dp(7),0,0);stableText.setLineSpacing(dp(2),1f);
        controls.addView(stableText);
        partialText=text("",18,0xffc1d7ca,false);partialText.setTypeface(Typeface.DEFAULT,Typeface.ITALIC);
        partialText.setMinLines(1);partialText.setMaxLines(2);partialText.setEllipsize(TextUtils.TruncateAt.END);
        partialText.setPadding(0,dp(2),0,dp(9));controls.addView(partialText);
        toggle=button("Start listening",false);toggle.setOnClickListener(v->tappedListen());
        Button keep=button("Keep last minute",false);
        keep.setOnClickListener(v->command(RecallService.KEEP));keep.setTag("live-keep");
        manualActions=row();split(manualActions,toggle,keep);controls.addView(manualActions);
        latestCatch=text("",14,0xffd3e8d7,false);latestCatch.setPadding(0,dp(8),0,0);latestCatch.setMaxLines(3);
        latestCatch.setEllipsize(TextUtils.TruncateAt.END);controls.addView(latestCatch);header.addView(controls);
        Button recordWords=button("Record my words & edit",true);recordWords.setOnClickListener(v->startActivity(new Intent(this,VoiceTakeActivity.class).putExtra("begin",true)));header.addView(recordWords);
        Button longSessions=button("Long sessions",true);longSessions.setOnClickListener(v->startActivity(new Intent(this,LongSessionsActivity.class)));header.addView(longSessions);
        Button reviewCapture=button("Live words & capture archive",true);
        reviewCapture.setOnClickListener(v->startActivity(new Intent(this,CaptureReviewActivity.class)));header.addView(reviewCapture);
        LinearLayout sessionTools=row();journalButton=link("Text journal");
        journalButton.setOnClickListener(v->startActivity(new Intent(this,FullJournalActivity.class)));Button floating=link("Floating controls");
        floating.setOnClickListener(v->{try{startActivity(new Intent().setClassName(this,"org.totalrecall.listening.OverlaySettingsActivity"));}
            catch(Exception ex){problem("Couldn’t open floating controls",ex);}});
        split(sessionTools,journalButton,floating);header.addView(sessionTools);
        LinearLayout archive=row();archive.setBackground(card(PALE,0,12));pad(archive,12,4);
        LinearLayout archiveCopy=column();archiveCopy.addView(text("Archive & sync",16,TEAL,true));
        archiveStatus=text("Reading archive status…",13,SECOND,false);archiveCopy.addView(archiveStatus);
        archive.addView(archiveCopy,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=text("›",25,TEAL,false);arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);archive.addView(arrow);
        archive.setMinimumHeight(dp(54));archive.setFocusable(true);
        archive.setOnClickListener(v->{try{startActivity(new Intent().setClassName(this,"org.totalrecall.listening.ArchiveSyncActivity"));}
            catch(Exception e){problem("Couldn’t open archive settings",e);}});header.addView(archive);
        LinearLayout headings=row();listHeading=text("Saved moments",23,INK,true);
        listHeading.setPadding(0,dp(8),0,dp(5));headings.addView(listHeading,new LinearLayout.LayoutParams(0,-2,1));
        daily=link("Review today");daily.setOnClickListener(v->{reviewToday=!reviewToday;search.setText("");refresh();});
        headings.addView(daily);header.addView(headings);
        search=new EditText(this);search.setSingleLine();search.setTextColor(INK);search.setHintTextColor(SECOND);
        search.setHint("Search moments");search.setContentDescription("Search saved moments");
        search.setTextSize(16);search.setBackground(card(WHITE,BORDER,10));pad(search,12,10);search.setMinHeight(dp(50));
        LinearLayout find=row();find.addView(search,new LinearLayout.LayoutParams(0,-2,1));
        ask=link("Voice search");ask.setMaxWidth(dp(118));ask.setOnClickListener(v->voiceSearch());find.addView(ask);
        header.addView(find);
        summary=text("Reading saved moments…",14,SECOND,false);summary.setPadding(0,0,0,dp(8));
        header.addView(summary);
        list.addHeaderView(header,null,false);list.setAdapter(adapter);page.addView(list,new LinearLayout.LayoutParams(-1,-1));
        list.setOnItemClickListener((p,v,index,id)->{int pos=index-list.getHeaderViewsCount();if(pos>=0&&pos<items.size()) showMoment(items.get(pos));});
        if(saved!=null){journalExportId=saved.getLong("journalExportId",-1);reviewToday=saved.getBoolean("reviewToday");search.setText(saved.getString("query",""));exportId=saved.getLong("exportId",-1);voicePermissionRequest=saved.getBoolean("voicePermission");}
        if(saved==null)applyIntentQuery(getIntent());
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){scheduleRead(180);}
            public void afterTextChanged(Editable e){}
        });
        header.setFocusableInTouchMode(true);header.requestFocus();
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);applyIntentQuery(intent);
    }
    private void applyIntentQuery(Intent intent){
        if(intent!=null && intent.hasExtra("captureDestination")){
            String target=intent.getStringExtra("captureDestination");intent.removeExtra("captureDestination");
            if("journalOptions".equals(target))main.post(this::showJournal);
            if("allJournals".equals(target))main.post(this::listJournals);
            if("journal".equals(target)){long id=intent.getLongExtra("journalId",-1);worker.execute(()->{
                try(JournalStore j=new JournalStore(this)){
                    for(JournalStore.Session session:j.sessions())if(session.id==id){main.post(()->{if(alive())openJournal(session);});return;}
                    main.post(()->{if(alive())Toast.makeText(this,"This journal is no longer on the phone.",Toast.LENGTH_SHORT).show();});
                }});
            }
            if("clips".equals(target)){reviewToday=false;search.setText("");scheduleRead(0);main.post(()->list.setSelection(1));}
        }
        if(intent!=null&&intent.getBooleanExtra("overlayStart",false)){
            intent.removeExtra("overlayStart");
            long expected=intent.getLongExtra("resumeAt",0);
            if(expected>0 && (expected!=RecallService.pausedAt(this)||!org.continuity.recall.ListenPolicy.allowed(this)))return;
            main.post(()->{if(alive()&&hasWindowFocus()&&!RecallService.isListening())tappedListen();
                else if(alive()&&!RecallService.isListening())scheduleOverlayStart();});
        }
        String q=intent==null?null:intent.getStringExtra("query");
        if(q!=null)search.setText(q);
        requestedMoment=intent==null?-1:intent.getLongExtra("moment_id",-1);
        if(requestedMoment>=0){reviewToday=false;search.setText("");scheduleRead(0);}
    }
    private boolean overlayRequested;
    private void scheduleOverlayStart(){overlayRequested=true;}
    @Override public void onWindowFocusChanged(boolean focus){
        super.onWindowFocusChanged(focus);
        if(focus&&overlayRequested){overlayRequested=false;if(alive()&&!RecallService.isListening())tappedListen();}
    }
    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);out.putBoolean("reviewToday",reviewToday);out.putString("query",search.getText().toString());
        out.putLong("exportId",exportId);out.putLong("journalExportId",journalExportId);out.putBoolean("voicePermission",voicePermissionRequest);
    }
    @Override protected void onStart(){super.onStart();IntentFilter filter=new IntentFilter(RecallService.ACTION_CHANGED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(observer,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(observer,filter);
        registered=true;refresh();main.removeCallbacks(tick);tick.run();
    }
    @Override protected void onResume(){super.onResume();if(state!=null){ArchiveSync.schedule(this);refresh();readArchiveStatus();}}
    private void readArchiveStatus(){
        archiveRefresh=android.os.SystemClock.elapsedRealtime();
        worker.execute(()->{
            try(ArchiveStore store=new ArchiveStore(getApplicationContext())){
                ArchiveStore.Summary stats=store.summary();
                String description=(ArchiveStore.enabled(this)?"Full audio on":"Full audio off")+" · "+stats.localCount+" local · "+stats.pendingCount+" pending\n"+ArchiveSync.backupStatus(this);
                main.post(()->{if(alive())archiveStatus.setText(description);});
            }catch(Exception e){main.post(()->{if(alive())archiveStatus.setText("Open archive settings to check status");});}
        });
    }
    @Override protected void onStop(){if(registered){unregisterReceiver(observer);registered=false;}
        main.removeCallbacks(tick);stableText.setText("");partialText.setText("");endSpeech();stopPlayer();super.onStop();}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);worker.shutdown();super.onDestroy();}

    private boolean alive(){return !isFinishing()&&!isDestroyed();}
    private void updateControls(){
        boolean active=RecallService.isListening();
        if(active&&!getPreferences(0).getBoolean("listened",false))getPreferences(0).edit().putBoolean("listened",true).apply();
        firstUse.setVisibility(active||getPreferences(0).getBoolean("listened",false)?View.GONE:View.VISIBLE);
        String current=RecallService.status(this);
        state.setText(current==null||current.trim().isEmpty()?(active?"Listening on this phone.":"Paused. Start a session when you want to listen."):current);
        toggle.setText(active?"Pause listening":RecallService.pausedAt(this)>0?"Resume listening":"Start listening");
        toggle.setEnabled(!commandPending&&!searchRecording);
        View keep=manualActions.findViewWithTag("live-keep");keep.setVisibility(active?View.VISIBLE:View.GONE);
        micLabel.setText(active?"MIC ON":"MIC OFF");meterLive=active;
        journalButton.setText(JournalStore.enabled(this)?"Full journal · on":"Full journal · off");
        paintLive();
        listHeading.setText(reviewToday?"Today’s catches":"Saved moments");
        daily.setText(reviewToday?"Show all":"Review today");
    }
    private void refresh(){updateControls();scheduleRead(70);}
    private void scheduleRead(long delay){
        generation++;main.removeCallbacks(queryLater);main.postDelayed(queryLater,delay);
    }
    private void readMoments(){
        int token=generation;final boolean today=reviewToday;String q=search.getText().toString().trim();
        worker.execute(()->{
            try(Store store=new Store(getApplicationContext())){
                List<Store.Moment> exact=store.search(q);
                boolean related=exact.isEmpty()&&!q.isEmpty();
                List<Store.Moment> rows=new ArrayList<>(related?relatedMoments(store,q):exact);
                if(today){
                    long[] day=localDay(System.currentTimeMillis(),java.time.ZoneId.systemDefault());
                    rows.removeIf(m->m.endMs<day[0]||m.endMs>=day[1]);
                }
                int pending=0;
                for(Store.Moment m:rows)if(!nonempty(m.feedback)||"none".equals(m.feedback))pending++;
                String dayLabel=rows.isEmpty()?(q.isEmpty()?"No clips saved today.":"No matching clips today."):
                    rows.size()+(rows.size()==1?" catch":" catches")+" · "+pending+" awaiting feedback";
                main.post(()->{if(!alive()||token!=generation)return;items.clear();items.addAll(rows);adapter.notifyDataSetChanged();
                    summary.setText(today?dayLabel:rows.isEmpty()?(q.isEmpty()?"Your saved clips will appear here.":"No matching moments."):
                        rows.size()+" "+(related?"related ":"")+(rows.size()==1?"moment":"moments"));

                    if(requestedMoment>=0){
                        long id=requestedMoment;requestedMoment=-1;boolean found=false;
                        for(Store.Moment row:rows)if(row.id==id){showMoment(row);found=true;break;}
                        if(!found)Toast.makeText(this,"This moment is no longer available.",Toast.LENGTH_SHORT).show();
                    }
                });
            }catch(Exception e){main.post(()->{if(alive()&&token==generation)summary.setText("Couldn’t read saved moments. "+message(e));});}
        });
    }
    /** End-of-clip time assigns an overnight capture to the day it finished. Handles DST. */
    static long[] localDay(long now,java.time.ZoneId zone){
        java.time.LocalDate date=java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        return new long[]{date.atStartOfDay(zone).toInstant().toEpochMilli(),
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()};
    }
    private static final Set<String> COMMON=new HashSet<>(Arrays.asList(
        "a","an","i","in","for","of","or","on","the","to","a","and","me","my","you","was","were",
        "is","it","at","which","what","where","did","say","saying","that","have","had","find","show","look",
        "search","line","word","clip","moment","about","please","this","when","with","some","saved","remember"));
    /** Only fallback to word matching when literal search has no hits. Recency breaks ties. */
    private static List<Store.Moment> relatedMoments(Store store,String query){
        Set<String> wanted=keywords(query);
        if(wanted.isEmpty())return Collections.emptyList();
        List<Store.Moment> result=new ArrayList<>();Map<Long,Integer> matches=new HashMap<>();
        for(Store.Moment m:store.search("")){
            Set<String> terms=keywords(m.title+" "+m.note+" "+m.correction+" "+m.transcript+" "+m.kind+" "+m.reason);
            terms.retainAll(wanted);
            if(!terms.isEmpty()){matches.put(m.id,terms.size());result.add(m);}
        }
        Collections.sort(result,(a,b)->Integer.compare(matches.get(b.id),matches.get(a.id)));return result;
    }
    private static Set<String> keywords(String value){
        Set<String> result=new HashSet<>();
        String[] words=value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        for(String word:words){
            if(word.isEmpty()||COMMON.contains(word))continue;
            for(String suffix:new String[]{"ions","ion","ing","ed","es","s"}){
                if(word.endsWith(suffix)&&word.length()-suffix.length()>=4){word=word.substring(0,word.length()-suffix.length());break;}
            }
            result.add(word);
        }
        return result;
    }
    private void more(View anchor){
        PopupMenu menu=new PopupMenu(this,anchor);
        String[] options={"Missed one","How listening works","Coverage gaps","Export all clips","Restore ZIP","Connect to Mint","Open phone vault","Capture sensitivity"};
        for(int i=0;i<options.length;i++)menu.getMenu().add(0,i,i==7?1:(i==0?0:i+1),options[i]);
        android.view.SubMenu labeled=menu.getMenu().addSubMenu(0,8,1,"Save recent audio as…");
        String[] kinds={SelectiveEngine.FORGE,SelectiveEngine.POETRY,SelectiveEngine.DECISION};
        for(int i=0;i<kinds.length;i++)labeled.add(0,9+i,i,kinds[i]);
        boolean canKeep=RecallService.isListening() && LiveCapture.snapshot().receiving;
        labeled.getItem().setEnabled(canKeep);
        menu.getMenu().findItem(0).setEnabled(canKeep);
        menu.getMenu().findItem(3).setEnabled(!exporting&&!importing);
        menu.getMenu().findItem(4).setEnabled(!exporting&&!importing);
        menu.setOnMenuItemClickListener(item->{switch(item.getItemId()){
            case 0: command(RecallService.MISSED);break;
            case 1: about();break;
            case 2: showGaps();break;
            case 3: chooseExport(-1);break;
            case 4: chooseRestore();break;
            case 5: try{startActivity(new Intent().setClassName(this,"org.totalrecall.listening.MintLinkActivity"));}
                    catch(Exception e){problem("Couldn’t open Mint connection",e);}break;
            case 6: startActivity(new Intent(this,org.continuity.recall.MainActivity.class)
                        .setAction("org.totalrecall.action.OPEN_VAULT").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));break;
            case 7: sensitivity();break;
            case 9: command(RecallService.KEEP_WORD);break;
            case 10: command(RecallService.KEEP_LINE);break;
            case 11: command(RecallService.KEEP_DECISION);break;
            default:return false;}return true;});menu.show();
    }
    private void sensitivity(){
        android.content.SharedPreferences prefs=getSharedPreferences("recall-detector",MODE_PRIVATE);
        int selected=Math.max(-1,Math.min(1,prefs.getInt("sensitivity",0)))+1;
        String[] choices={"Fewer saves","Balanced (default)","More saves"};
        new AlertDialog.Builder(this).setTitle("Capture sensitivity")
            .setSingleChoiceItems(choices,selected,(d,which)->{
                prefs.edit().putInt("sensitivity",which-1).apply();d.dismiss();
                Toast.makeText(this,"Capture sensitivity · "+choices[which],Toast.LENGTH_SHORT).show();
            }).setNegativeButton("Cancel",null).show();
    }
    private void updateText(TextView target,String value){if(!TextUtils.equals(target.getText(),value))target.setText(value);}
    private void paintLive(){
        LiveCapture.Snapshot snap=LiveCapture.snapshot();
        boolean armed=meterLive&&RecallService.isListening();
        boolean waiting=armed&&snap.state.startsWith("Waiting for microphone");
        boolean warming=armed&&(snap.state.startsWith("Prepar")||!snap.active);
        boolean listening=armed&&!waiting&&!warming;
        manualActions.findViewWithTag("live-keep").setEnabled(listening);
        updateText(micLabel,waiting?"WAITING":warming?"STARTING":listening?"MIC ON":"MIC OFF");
        String status=armed?snap.state:RecallService.status(this);
        if(nonempty(status))updateText(state,status);
        micMeter.setVisibility(listening?View.VISIBLE:View.GONE);
        micMeter.setProgress(listening?Math.round(100f*Math.max(0f,Math.min(1f,snap.level))):0);
        String committed="";
        if(listening&&!snap.finals.isEmpty()){
            int from=Math.max(0,snap.finals.size()-2);
            committed=TextUtils.join(" ",snap.finals.subList(from,snap.finals.size())).trim();
            if(committed.length()>165){int boundary=committed.indexOf(' ',committed.length()-165);
                committed="… "+committed.substring(boundary<0?committed.length()-165:boundary+1);}
        }
        String partial=listening&&snap.partial!=null?snap.partial:"";
        stableText.setVisibility(listening?View.VISIBLE:View.GONE);partialText.setVisibility(listening?View.VISIBLE:View.GONE);
        updateText(stableText,nonempty(committed)?committed:(!nonempty(partial)?"Listening for speech…":""));
        updateText(partialText,partial);
        String last=nonempty(snap.lastSave)?snap.lastSave:"";
        if(!last.isEmpty()&&nonempty(snap.reason))last+=" · "+snap.reason;
        updateText(latestCatch,last);latestCatch.setVisibility(nonempty(last)?View.VISIBLE:View.GONE);
    }
    private void showJournal(){
        boolean enabled=JournalStore.enabled(this);
        LinearLayout sheet=column();pad(sheet,18,12);ScrollView scroll=new ScrollView(this);scroll.addView(sheet);
        TextView intro=text("Keep recognized words from a whole listening session as local text. Audio retention is controlled separately in Archive & sync.",16,INK,false);
        sheet.addView(intro);
        Switch toggleJournal=new Switch(this);toggleJournal.setText("Save full-session text");toggleJournal.setTextColor(INK);toggleJournal.setTextSize(16);
        toggleJournal.setChecked(enabled);toggleJournal.setMinHeight(dp(54));sheet.addView(toggleJournal);
        toggleJournal.setOnClickListener(v->{
            boolean on=toggleJournal.isChecked();toggleJournal.setEnabled(false);
            worker.execute(()->{
                try {JournalStore.setEnabled(getApplicationContext(),on);}
                catch(RuntimeException e){main.post(()->{if(alive())problem("Couldn’t change journal settings",e);});}
                finally {main.post(()->{if(alive()){
                    toggleJournal.setChecked(JournalStore.enabled(this));toggleJournal.setEnabled(true);updateControls();
                }});}
            });
        });
        TextView retentionLabel=text("Keep journals for",14,SECOND,true);retentionLabel.setPadding(0,dp(6),0,0);sheet.addView(retentionLabel);
        final int[] days={1,7,30,90,0};String[] labels={"1 day","7 days","30 days","90 days","Until I delete them"};
        final int existing=JournalStore.retentionDays(this);int selected=0;for(int i=0;i<days.length;i++)if(days[i]==existing)selected=i;
        Button retention=button(labels[selected],false);sheet.addView(retention);
        retention.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Journal retention").setItems(labels,(d,w)->{
            if(days[w]==JournalStore.retentionDays(this))return;
            Runnable apply=()->{
                retention.setEnabled(false);
                worker.execute(()->{
                    try {JournalStore.setRetentionDays(this,days[w]);main.post(()->{if(alive())retention.setText(labels[w]);});}
                    catch(RuntimeException e){main.post(()->{if(alive())problem("Couldn’t change retention",e);});}
                    finally{main.post(()->{if(alive())retention.setEnabled(true);});}
                });
            };
            if(days[w]>0&&(JournalStore.retentionDays(this)==0||days[w]<JournalStore.retentionDays(this)))new AlertDialog.Builder(this)
                .setTitle("Shorten retention?").setMessage("Older session text outside the new window may be deleted.")
                .setNegativeButton("Cancel",null).setPositiveButton("Apply",(x,y)->apply.run()).show();
            else apply.run();
        }).show());
        Button browse=button("Browse session journals",true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.topMargin=dp(14);sheet.addView(browse,lp);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Text journal").setView(scroll).setPositiveButton("Close",null).create();
        browse.setOnClickListener(v->{dialog.dismiss();listJournals();});dialog.show();
    }
    private void listJournals(){
        worker.execute(()->{try(JournalStore store=new JournalStore(getApplicationContext())){
            List<JournalStore.Session> sessions=store.sessions();
            String[] rows=new String[sessions.size()];
            for(int i=0;i<rows.length;i++){JournalStore.Session ses=sessions.get(i);
                rows[i]=momentDate(ses.startMs)+(ses.endMs==0?" · Open":"")+"\n"+ses.count+" "+(ses.count==1?"entry":"entries");}
            main.post(()->{if(!alive())return;
                AlertDialog.Builder dlg=new AlertDialog.Builder(this).setTitle("Session journals").setNegativeButton("Close",null);
                if(rows.length==0)dlg.setMessage("No journals yet. Turn on full-session text, then start speaking during a listening session.");
                else dlg.setItems(rows,(di,pos)->openJournal(sessions.get(pos)));
                dlg.show();});
        }catch(Exception e){main.post(()->{if(alive())problem("Couldn’t list journals",e);});}});
    }
    private void openJournal(JournalStore.Session session){
        worker.execute(()->{try(JournalStore store=new JournalStore(getApplicationContext())){
            String transcript=store.text(session.id);String situation=Situation.read(this,transcript,true);String story=Situation.read(this,transcript,true,true);
            main.post(()->{if(!alive())return;ScrollView scroll=new ScrollView(this);LinearLayout column=column();
                pad(column,18,8);scroll.addView(column);
                if(nonempty(session.endReason)){
                    TextView coverage=text(session.endReason,14,SECOND,true);coverage.setPadding(0,0,0,dp(12));column.addView(coverage);
                }
                column.addView(StoryNotes.button(this,StoryNotes.Kind.JOURNAL,String.valueOf(session.id),story));
                TextView cues=labeled(column,"Situation from journal entries",situation);
                TextView body=text(nonempty(transcript)?transcript:"No recognized speech yet.",17,INK,false);
                Button names=link("Name voices or correct a line");names.setOnClickListener(v->new VoiceEditor(this,worker,session.id,()->worker.execute(()->{
                    try(JournalStore fresh=new JournalStore(this)){String updated=fresh.text(session.id),analysis=Situation.read(this,updated,true);main.post(()->{if(alive()){body.setText(updated);if(cues!=null)cues.setText(analysis);}});}
                })).open());column.addView(names);
                body.setTextIsSelectable(true);body.setLineSpacing(dp(4),1f);column.addView(body);
                new AlertDialog.Builder(this).setTitle(momentDate(session.startMs)).setView(scroll)
                    .setNegativeButton("Delete",(d,w)->new AlertDialog.Builder(this).setTitle("Delete this journal?")
                        .setMessage("Permanently remove this session’s saved text. Selected audio clips stay in your timeline.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Delete",(x,y)->deleteJournal(session.id)).show())
                    .setNeutralButton("Export text",(d,w)->chooseJournalExport(session.id)).setPositiveButton("Close",null).show();});
        }catch(Exception e){main.post(()->{if(alive())problem("Couldn’t open journal",e);});}});
    }
    private void deleteJournal(long id){
        worker.execute(()->{try(JournalStore store=new JournalStore(getApplicationContext())){
            store.delete(id);main.post(()->{if(alive()){Toast.makeText(this,"Journal deleted",Toast.LENGTH_SHORT).show();listJournals();}});
        }catch(Exception e){main.post(()->{if(alive())problem("Couldn’t delete journal",e);});}});
    }
    private void chooseJournalExport(long id){
        journalExportId=id;
        Intent create=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE,"Total-Recall-journal-"+id+".txt");
        try{startActivityForResult(create,JOURNAL_EXPORT);}catch(Exception e){problem("No document picker available",e);}
    }
    private void exportJournal(Uri destination){
        long id=journalExportId;journalExportId=-1;
        if(id<0){Toast.makeText(this,"Select the session again to export.",Toast.LENGTH_LONG).show();return;}
        worker.execute(()->{try(JournalStore store=new JournalStore(getApplicationContext());
                OutputStream stream=getContentResolver().openOutputStream(destination,"wt")){
            if(stream==null)throw new java.io.IOException("Document provider returned no output stream.");
            store.export(id,stream);
        }catch(Exception e){main.post(()->{if(alive())problem("Journal export incomplete",e);});return;}
        main.post(()->{if(alive())Toast.makeText(this,"Journal exported",Toast.LENGTH_SHORT).show();});});
    }
    private void tappedListen(){
        endSpeech();stopPlayer();
        if(RecallService.isListening()){command(RecallService.USER_PAUSE);return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED
            || (Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                &&!getPreferences(0).getBoolean("noticeAsked",false))){
            voicePermissionRequest=false;List<String> needed=new ArrayList<>();
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.RECORD_AUDIO);
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                needed.add(Manifest.permission.POST_NOTIFICATIONS);getPreferences(0).edit().putBoolean("noticeAsked",true).apply();}
            requestPermissions(needed.toArray(new String[0]),MIC);
        }else command(RecallService.START);
    }
    @Override public void onRequestPermissionsResult(int req,String[] perms,int[] results){
        super.onRequestPermissionsResult(req,perms,results);if(req!=MIC||results.length==0)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            voicePermissionRequest=false;
            new AlertDialog.Builder(this).setTitle("Microphone access needed")
                .setMessage("Allow microphone access for speech capture. Your saved clips remain available while listening is paused.")
                .setNegativeButton("Close",null).setPositiveButton("App settings",(d,w)->startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).show();return;}
        if(voicePermissionRequest){voicePermissionRequest=false;voiceSearch();}else command(RecallService.START);
    }
    private void command(String action){
        try{
            RecallService.command(this,action);
            if(action.equals(RecallService.START)||action.equals(RecallService.USER_PAUSE)){
                commandPending=true;toggle.setEnabled(false);state.setText(action.equals(RecallService.START)?"Starting microphone…":"Pausing…");
                main.postDelayed(()->{if(alive()){commandPending=false;refresh();}},800);
            }
        }catch(Exception e){problem("Listening command failed",e);}
    }
    private void about(){
        new AlertDialog.Builder(this).setTitle("Selective listening")
            .setMessage("Start a session while everyone nearby knows the microphone is active. Speak naturally as you work. The meter shows microphone input, and the live words are an on-device transcription.\n\n"
                +"Repeated lines, rhymes, words you define and project decisions can trigger a clip. Recall includes the lead-in. Under More → Capture sensitivity, choose More saves for freestyle or Fewer saves for a quieter timeline.\n\n"
                +"Say “Hey Recall, save that” to keep the preceding minute, or “Hey Recall, start a passage” before improvising. Find all commands under Live & saved → Voice commands. Rate clips to tune later selections.\n\n"
                +"With full audio off, ordinary audio expires from memory. Archive & sync can retain the complete audio, and Text journal can retain the recognized words. An interrupted microphone creates a coverage gap until input returns. Pause releases the microphone. Resume starts a new audio segment and marks the break.")
            .setPositiveButton("Got it",null).show();
    }

    private interface Work {void run(Store store) throws Exception;}
    private void write(String success,Work work){
        worker.execute(()->{try(Store store=new Store(getApplicationContext())){
            work.run(store);ArchiveSync.schedule(this);main.post(()->{if(alive()){Toast.makeText(this,success,Toast.LENGTH_SHORT).show();refresh();}});
        }catch(Exception e){main.post(()->{if(alive())problem("Couldn’t save the change",e);});}});
    }
    private void showGaps(){
        worker.execute(()->{try(Store store=new Store(getApplicationContext())){
            List<String> gaps=store.gaps();String result=gaps.isEmpty()?"No interruptions have been logged.":TextUtils.join("\n\n",gaps);
            main.post(()->{if(alive())new AlertDialog.Builder(this).setTitle("Coverage gaps").setMessage(result).setPositiveButton("Close",null).show();});
        }catch(Exception e){main.post(()->{if(alive())problem("Couldn’t read coverage",e);});}});
    }
    private void showMoment(Store.Moment m){
        stopPlayer();ScrollView scroll=new ScrollView(this);LinearLayout body=column();pad(body,18,14);scroll.addView(body);
        body.addView(text(momentDate(m.startMs)+"   ·   "+duration(m),15,SECOND,false));
        String evidence=nonempty(m.correction)?m.correction:m.transcript;
        if(nonempty(evidence)){
            labeled(body,nonempty(m.correction)?"Your wording":"Recognized words",evidence);
            LinearLayout sharing=row();Button copy=link("Copy words"),share=link("Share words");split(sharing,copy,share);body.addView(sharing);
            copy.setOnClickListener(v->PassageActions.copy(this,evidence));share.setOnClickListener(v->PassageActions.share(this,evidence));
        }
        Button fix=button("Correct what I said",true);body.addView(fix);
        LinearLayout soundActions=row();Button listen=link("Playback & trim"),audio=link("Share audio");split(soundActions,listen,audio);body.addView(soundActions);
        listen.setOnClickListener(v->{stopPlayer();studio=new ClipStudio(this,m,worker,this::refresh);studio.show();});
        audio.setOnClickListener(v->PassageActions.audio(this,m,worker));
        body.addView(StoryNotes.button(this,StoryNotes.Kind.CLIP,String.valueOf(m.id),nonempty(evidence)?Situation.read(this,evidence,false,true):""));
        if(nonempty(evidence))labeled(body,nonempty(m.correction)?"Situation from your correction":"Situation from recognized words",Situation.read(this,evidence,false));
        if(nonempty(m.note))labeled(body,"Situation from your note",Situation.read(this,m.note,false));
        if(m.audio!=null&&m.audio.isFile()){Button analyze=link("Analyze original recording");analyze.setOnClickListener(v->AudioSituation.show(this,StoryNotes.Kind.CLIP,String.valueOf(m.id),ex->ex.setDataSource(m.audio.getAbsolutePath())));body.addView(analyze);}
        labeled(body,"Why it was kept",m.reason);
        if(nonempty(m.correction))labeled(body,"Originally heard",m.transcript);
        if(nonempty(m.note))labeled(body,"Note",m.note);
        if(nonempty(m.feedback)&&!"none".equals(m.feedback))labeled(body,"Your feedback","keep".equals(m.feedback)?"Worth keeping":"Less like this");
        if(nonempty(m.parentId))labeled(body,"Excerpt source","Parent clip "+m.parentId+"\nSamples "+m.startSample+" through "+(m.endSample-1)+" at 16 kHz");
        boolean available=m.audio!=null&&m.audio.isFile();listen.setEnabled(available);audio.setEnabled(available);
        AlertDialog dlg=new AlertDialog.Builder(this).setTitle(m.title).setView(scroll).setPositiveButton("Close",null).create();
        fix.setOnClickListener(v->{dlg.dismiss();editMoment(m);});
        LinearLayout feedback=row();Button keep=button("Worth keeping",false),skip=button("Less like this",false);split(feedback,keep,skip);body.addView(feedback);
        keep.setOnClickListener(v->{dlg.dismiss();write("Feedback saved",s->s.feedback(m.id,"keep"));});
        skip.setOnClickListener(v->{dlg.dismiss();write("Feedback saved",s->s.feedback(m.id,"skip"));});
        LinearLayout tools=row();Button edit=link("Edit details"),send=link("Export clip");split(tools,edit,send);body.addView(tools);
        edit.setOnClickListener(v->{dlg.dismiss();editMoment(m);});
        send.setOnClickListener(v->{dlg.dismiss();chooseExport(m.id);});
        Button delete=link("Delete clip");delete.setTextColor(RED);delete.setOnClickListener(v->{dlg.dismiss();
            new AlertDialog.Builder(this).setTitle("Delete this moment?").setMessage("The original audio, transcript and notes for this clip will be removed from this phone.")
                .setNegativeButton("Keep clip",null).setPositiveButton("Delete",(d,w)->write("Moment deleted",s->s.delete(m.id))).show();});body.addView(delete);
        dlg.setOnDismissListener(d->stopPlayer());dlg.show();
    }
    private void editMoment(Store.Moment m){
        ScrollView scroll=new ScrollView(this);LinearLayout body=column();pad(body,16,8);scroll.addView(body);
        labeled(body,"Originally heard",nonempty(m.transcript)?m.transcript:"No words recognized");
        EditText correction=field(body,"What I actually said",nonempty(m.correction)?m.correction:m.transcript,false),title=field(body,"Title",m.title,true),
            note=field(body,"Note",m.note,false);
        AlertDialog dlg=new AlertDialog.Builder(this).setTitle("Edit details").setView(scroll)
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String t=title.getText().toString().trim(), n=note.getText().toString(), c=correction.getText().toString();
            if(t.isEmpty()){title.setError("Add a title");return;}
            String edited=Objects.equals(m.transcript,c)?"":c;dlg.dismiss();write("Details saved",s->s.edit(m.id,t,n,edited));
        });
    }
    private EditText field(LinearLayout body,String label,String value,boolean single){
        TextView name=text(label,14,SECOND,true);body.addView(name);
        EditText e=new EditText(this);e.setSingleLine(single);e.setContentDescription(label);e.setText(value==null?"":value);e.setMinHeight(dp(48));
        body.addView(e);return e;
    }
    private TextView labeled(LinearLayout body,String label,String value){
        if(!nonempty(value))return null;TextView name=text(label,14,SECOND,true);name.setPadding(0,dp(14),0,dp(3));
        body.addView(name);TextView text=text(value,17,INK,false);text.setTextIsSelectable(true);text.setPadding(0,0,0,dp(8));body.addView(text);return text;
    }
    private void stopPlayer(){
        if(studio!=null){studio.close();studio=null;}
    }
    private void chooseExport(long id){
        if(importing||exporting){Toast.makeText(this,"A transfer is in progress",Toast.LENGTH_SHORT).show();return;}
        new AlertDialog.Builder(this).setTitle(id<0?"Export all saved moments?":"Export this moment?")
            .setMessage("The ZIP includes original audio, transcripts and notes without encryption. Choose a private destination in the file picker.")
            .setNegativeButton("Cancel",null).setPositiveButton("Choose destination",(dialog,which)->openExportPicker(id)).show();
    }
    private void openExportPicker(long id){
        exportId=id;
        String suffix=id<0?"moments":"moment-"+id;
        String date=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date());
        Intent create=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE,"Total-Recall-"+suffix+"-"+date+".zip");
        try{startActivityForResult(create,EXPORT);}catch(Exception e){problem("No document picker available",e);}
    }
    private void chooseRestore(){
        if(importing||exporting){Toast.makeText(this,"A transfer is in progress",Toast.LENGTH_SHORT).show();return;}
        Intent choose=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/x-zip-compressed","application/octet-stream"});
        try{startActivityForResult(choose,RESTORE);}catch(Exception e){problem("No document picker available",e);}
    }
    private void restoreZip(Uri uri){
        importing=true;summary.setText("Checking listening archive…");
        worker.execute(()->{
            try(Store store=new Store(getApplicationContext());InputStream input=getContentResolver().openInputStream(uri)){
                if(input==null)throw new java.io.IOException("The selected document could not be opened.");
                String report=store.importZip(input);
                main.post(()->{if(alive()){importing=false;search.setText("");refresh();
                    new AlertDialog.Builder(this).setTitle("Restore complete").setMessage(report).setPositiveButton("Close",null).show();}});
            }catch(Exception e){main.post(()->{if(alive()){importing=false;refresh();problem("Couldn’t restore this archive",e);}});}
        });
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(req==JOURNAL_EXPORT){exportJournal(data.getData());return;}
        if(req==RESTORE){restoreZip(data.getData());return;}
        if(req!=EXPORT)return;
        final Uri uri=data.getData();final long id=exportId;exporting=true;summary.setText("Exporting audio and transcripts…");
        worker.execute(()->{try(Store store=new Store(getApplicationContext());OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
            if(out==null)throw new java.io.IOException("Document provider returned no output stream.");
            if(id<0)store.exportZip(out);else store.exportZip(out,Collections.singletonList(id));
        }catch(Exception e){main.post(()->{if(alive()){exporting=false;refresh();problem("Export incomplete",e);}});return;}
        main.post(()->{if(alive()){exporting=false;refresh();Toast.makeText(this,"Export saved",Toast.LENGTH_LONG).show();}});});
    }
    private void voiceSearch(){
        if(searchRecording){endSpeech();return;}
        if(RecallService.isListening()){Toast.makeText(this,"Pause listening to use voice search, or type a phrase.",Toast.LENGTH_LONG).show();return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            voicePermissionRequest=true;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC);return;}
        final int run=++speechGeneration;stopPlayer();searchRecording=true;toggle.setEnabled(false);
        ask.setText("Preparing · cancel");ask.setContentDescription("Preparing offline voice search. Tap to cancel.");
        new Thread(()->{
            android.media.AudioRecord mic=null;String phrase=null, failure=null;
            try(OfflineSpeech local=new OfflineSpeech(getApplicationContext())){
                if(run!=speechGeneration)return;
                int minimum=android.media.AudioRecord.getMinBufferSize(16000,android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT);
                if(minimum<=0)throw new java.io.IOException("This microphone does not offer 16 kHz input.");
                mic=new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,
                    android.media.AudioFormat.CHANNEL_IN_MONO,android.media.AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,8192));
                if(mic.getState()!=android.media.AudioRecord.STATE_INITIALIZED)throw new java.io.IOException("Microphone unavailable.");
                mic.startRecording();
                if(mic.getRecordingState()!=android.media.AudioRecord.RECORDSTATE_RECORDING)throw new java.io.IOException("Microphone is busy.");
                main.post(()->{if(alive()&&run==speechGeneration){ask.setText("Listening · cancel");ask.setContentDescription("Listening for a search phrase. Tap to cancel.");ask.announceForAccessibility("Speak your search phrase");}});
                short[] block=new short[1600];
                long deadline=android.os.SystemClock.elapsedRealtime()+12000;
                while(run==speechGeneration&&android.os.SystemClock.elapsedRealtime()<deadline){
                    int n=mic.read(block,0,block.length,android.media.AudioRecord.READ_NON_BLOCKING);
                    if(n<0)throw new java.io.IOException("Audio input stopped.");
                    if(n==0){android.os.SystemClock.sleep(30);continue;}
                    phrase=local.accept(block,n);if(nonempty(phrase))break;
                }
                if(run==speechGeneration&&!nonempty(phrase))phrase=local.finish();
            }catch(Exception|LinkageError e){failure=message(e);}
            finally{
                if(mic!=null){try{mic.stop();}catch(IllegalStateException ignored){}mic.release();}
                final String result=phrase, error=failure;
                main.post(()->{searchRecording=false;if(!alive())return;ask.setText("Voice search");ask.setContentDescription("Voice search");updateControls();
                    if(run!=speechGeneration)return;
                    if(error!=null)Toast.makeText(this,"Voice search: "+error,Toast.LENGTH_LONG).show();
                    else if(nonempty(result))search.setText(result);
                    else Toast.makeText(this,"No search phrase captured. Try again or type a phrase.",Toast.LENGTH_SHORT).show();
                });
            }
        },"Recall-Voice-Search").start();
    }
    private void endSpeech(){speechGeneration++;if(searchRecording&&ask!=null)ask.setText("Stopping…");}
    private static boolean nonempty(String s){return s!=null&&!s.trim().isEmpty();}
    private String momentDate(long ms){return DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(ms));}
    private String duration(Store.Moment m){long sec=Math.max(1,(m.endMs-m.startMs)/1000);return sec<60?sec+" sec":sec/60+" min "+sec%60+" sec";}
    private static String message(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private void problem(String what,Throwable e){new AlertDialog.Builder(this).setTitle(what).setMessage(message(e)).setPositiveButton("Close",null).show();}
    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    void pad(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    private GradientDrawable card(int fill,int edge,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(edge!=0)d.setStroke(dp(1),edge);return d;}
    TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextColor(color);v.setTextSize(size);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    Button button(String label,boolean primary){
        Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextSize(16);b.setTextColor(primary?WHITE:TEAL);
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x44888888),
            card(primary?TEAL:PALE,0,11),null));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));pad(b,9,8);return b;
    }
    Button link(String label){Button b=button(label,false);android.util.TypedValue value=new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground,value,true);b.setBackgroundResource(value.resourceId);return b;}
    void split(LinearLayout line,View left,View right){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(0,dp(5),dp(4),dp(3));line.addView(left,lp);
        lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(4),dp(5),0,dp(3));line.addView(right,lp);
    }
    private final class MomentsAdapter extends BaseAdapter {
        public int getCount(){return items.size();} public Store.Moment getItem(int i){return items.get(i);}
        public long getItemId(int i){return items.get(i).id;} public boolean hasStableIds(){return true;}
        public View getView(int i,View convert,ViewGroup parent){
            LinearLayout wrap,body;
            if(convert==null){
                wrap=column();wrap.setPadding(0,0,0,dp(8));body=column();body.setBackground(card(WHITE,BORDER,12));pad(body,14,12);
                TextView meta=text("",13,SECOND,false),name=text("",18,INK,true),excerpt=text("",16,SECOND,false);
                name.setPadding(0,dp(3),0,dp(3));excerpt.setMaxLines(2);excerpt.setEllipsize(TextUtils.TruncateAt.END);
                body.addView(meta);body.addView(name);body.addView(excerpt);wrap.addView(body);
            }else{wrap=(LinearLayout)convert;body=(LinearLayout)wrap.getChildAt(0);}
            Store.Moment m=getItem(i);String feedback="keep".equals(m.feedback)?" · Worth keeping":"skip".equals(m.feedback)?" · Less like this":" · Unreviewed";
            ((TextView)body.getChildAt(0)).setText(momentDate(m.startMs)+"  ·  "+duration(m)+(reviewToday?feedback:""));
            ((TextView)body.getChildAt(1)).setText(m.title);
            ((TextView)body.getChildAt(2)).setText(nonempty(m.correction)?m.correction:(nonempty(m.transcript)?m.transcript:m.reason));return wrap;
        }
    }
}
