package org.totalrecall.listening;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Runs in an empty disposable emulator vault with generated tone/silence and independent Mint receiver. */
public final class ArchiveProbe extends Instrumentation {
    private static final int RATE = 16000;
    private String phase, pair;
    private Context context;
    private final Bundle result = new Bundle();

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        phase = args.getString("phase", "");
        pair = args.getString("pair", "");
        start();
    }

    @Override public void onStart() {
        try {
            context = getTargetContext();
            if ("codec".equals(phase)) codec();
            else if ("transfer".equals(phase)) transfer();
            else if ("bulk".equals(phase)) {
                try {
                    shell("dumpsys battery unplug");
                    BacklogProbe.verify(context,pair);
                    guided();
                    step("A metadata-only 3,200-segment fixture totalled exactly 800 MiB. The separate one-time approval included every ID, validated reported destination space, queued durably under charging constraints and left automation disabled.");
                } finally { shell("dumpsys battery reset"); }
            }
            else throw new IllegalArgumentException("Unknown archive phase");
            result.putString("outcome", "PASS_" + phase);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("outcome", "FAIL_" + phase);
            result.putString("detail", Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void step(String message) {
        Bundle status = new Bundle(); status.putString("step", message); sendStatus(0, status);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void until(BooleanSupplier condition, long ms, String message) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(120);
        check(condition.getAsBoolean(), message);
    }
    private String shell(String command) throws Exception {
        ParcelFileDescriptor pipe = getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] block = new byte[2048];
            for (int n; (n = in.read(block)) != -1;) bytes.write(block,0,n);
            return bytes.toString("UTF-8");
        }
    }
    private static short[] tone() {
        short[] pcm = new short[RATE / 10];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short)(9000 * Math.sin(i*2*Math.PI*440 / RATE));
        return pcm;
    }
    private static void feed(ArchiveRecorder recorder, short[] pcm, int blocks) {
        for (int n=0; n<blocks; n++) recorder.accept(pcm,pcm.length);
    }
    private static String hash(byte[] content) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(content)) value.append(String.format(Locale.ROOT,"%02x",b & 255));
        return value.toString();
    }
    private static ArchiveStore.Segment findDuration(ArchiveStore store,long ms) {
        for (ArchiveStore.Segment row : store.segments(16)) if (row.durationMs==ms) return row;
        throw new AssertionError("Missing "+ms+" ms segment, pending count "+store.summary().pendingCount
                +", issue "+store.summary().lastIssue);
    }
    private void codec() throws Exception {
        ArchivePairChecks.verify();
        ArchiveStore.reset(context);
        check(!ArchiveStore.enabled(context), "A fresh archive was enabled without explicit choice");
        short[] tone=tone(), silence=new short[tone.length];
        try (ArchiveStore store = new ArchiveStore(context); ArchiveRecorder recorder=new ArchiveRecorder(context,store)) {
            feed(recorder,tone,2);
            check(store.summary().localCount==0,"Archive retained PCM while turned off");
            ArchiveStore.setReserveMiB(context,16); ArchiveStore.setEnabled(context,true);
            feed(recorder,tone,20); feed(recorder,silence,1185);
            recorder.interrupted("Synthetic microphone interruption");
            feed(recorder,tone,26); recorder.boundary("User paused");
            check(store.summary().localCount==3,"Wrong number of sealed AAC segments: "+store.summary().localCount+" "+store.summary().lastIssue);
            ArchiveStore.Segment full=findDuration(store,120000);
            ArchiveStore.Segment silentTail=findDuration(store,500), resumed=findDuration(store,2600);
            check(store.latestGaps(3).size()==1 && !store.latestGaps(1).get(0).contains(" — open"),"Microphone interruption has no bounded gap");
            for (ArchiveStore.Segment row : store.segments(8)) {
                ByteArrayOutputStream data=new ByteArrayOutputStream();
                store.export(row.id,data);
                check(row.local && data.size()==row.bytes && hash(data.toByteArray()).equals(row.sha256),"Stored AAC failed independent hash");
                check(row.retainUntilMs==ArchiveStore.retainUntil(row.endMs),"Missing seventh-anniversary retention date");
            }
            check(ArchiveStore.retainUntil(Instant.parse("2024-02-29T12:00:00Z").toEpochMilli())
                    == Instant.parse("2031-02-28T12:00:00Z").toEpochMilli(),"Leap-day retention drifted");
            verifyToneAndSilence(store,full.id);
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");
            int journalPlayhead=JournalSegmentPlaybackProbe.verify(this,context,full);
            step("The ordinary journal passage opened retained AAC paused at 40.637 s. Android MediaPlayer reported "+journalPlayhead+" ms after Play. Back returned to the passage and released the player.");
            verifyInterruptedTail(store,silentTail);
            step("Android decoded the first two-minute AAC segment and measured both the original 440 Hz tone and later silence. A separate 500 ms tail survived the gap; pause sealed the 2,600 ms resumed segment. All original AAC hashes and seven-year UTC dates agree.");
            check(!store.markSynced(resumed.id, full.sha256,"wrong-checksum"),"Wrong receipt hash accepted");
            check(store.markSynced(resumed.id,resumed.sha256,"quota-test-ack"),"Could not set quota fixture receipt");
            ArchiveStore.setEvictSynced(context,true); ArchiveStore.setReserveMiB(context,1000000);
            check(!store.maintain(),"Impossible free-space reserve accepted");
            check(store.get(full.id).local && store.get(silentTail.id).local && !store.get(resumed.id).local,
                    "Low-storage manager removed pending audio or retained an eligible acknowledged copy");
            ArchiveStore.setReserveMiB(context,16); ArchiveStore.setEvictSynced(context,false);
            store.delete(silentTail.id);
            check(store.pending(4).size()==1,"Expected a single pending source for the real transfer");
            step("Insufficient free-space reserve suspended new work. Pending AAC stayed local, and only a matching acknowledged segment was evicted.");
        }
    }
    private static void verifyInterruptedTail(ArchiveStore store,ArchiveStore.Segment source)throws Exception {
        java.lang.reflect.Method stage=ArchiveStore.class.getDeclaredMethod("stage",long.class,int.class);
        stage.setAccessible(true);
        Object unfinished=stage.invoke(store,source.startMs,source.bitrate);
        java.lang.reflect.Field file=unfinished.getClass().getDeclaredField("partial"), id=unfinished.getClass().getDeclaredField("id");
        file.setAccessible(true);id.setAccessible(true);
        try(FileOutputStream out=new FileOutputStream((File)file.get(unfinished))){
            store.export(source.id,out);
            out.write(new byte[]{(byte)0xff,(byte)0xf1,0x60});out.getFD().sync();
        }
        java.lang.reflect.Method recover=ArchiveStore.class.getDeclaredMethod("recover"); recover.setAccessible(true);
        recover.invoke(store);recover.invoke(store);
        ArchiveStore.Segment repaired=store.get((String)id.get(unfinished));
        check(repaired!=null && repaired.local && repaired.bytes==source.bytes && repaired.sha256.equals(source.sha256),
                "Recovery lost or duplicated encoded frames around a torn ADTS header");
        store.delete(repaired.id);
    }
    private static void verifyToneAndSilence(ArchiveStore store,String id) throws Exception {
        MediaExtractor read=new MediaExtractor(); MediaCodec codec=null;
        try (FileInputStream in=store.openForPlayback(id)) {
            read.setDataSource(in.getFD()); read.selectTrack(0);
            MediaFormat f=read.getTrackFormat(0);
            check("audio/mp4a-latm".equals(f.getString(MediaFormat.KEY_MIME)) && f.getInteger(MediaFormat.KEY_SAMPLE_RATE)==RATE,"Unexpected archive encoding");
            codec=MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)); codec.configure(f,null,null,0); codec.start();
            long frames=0, deadline=SystemClock.elapsedRealtime()+18000; int loud=0, quiet=0;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while (frames<4L*RATE && SystemClock.elapsedRealtime()<deadline) {
                int ix=codec.dequeueInputBuffer(3000);
                if (ix>=0) {
                    ByteBuffer b=codec.getInputBuffer(ix); b.clear(); int n=read.readSampleData(b,0);
                    check(n>0,"AAC ended before 4 seconds");
                    codec.queueInputBuffer(ix,0,n,read.getSampleTime(),0); read.advance();
                }
                int out=codec.dequeueOutputBuffer(info,3000);
                if(out>=0) {
                    ByteBuffer b=codec.getOutputBuffer(out).order(ByteOrder.LITTLE_ENDIAN);
                    b.position(info.offset);b.limit(info.offset+info.size);
                    while(b.remaining()>=2) {
                        int sample=Math.abs((int)b.getShort());
                        if(frames>=RATE/2 && frames<RATE && sample>1500) loud++;
                        if(frames>=3L*RATE && frames<4L*RATE && sample<180) quiet++;
                        frames++;
                    }
                    codec.releaseOutputBuffer(out,false);
                }
            }
            check(loud>RATE/4 && quiet>RATE*9/10,"Decoded tone/silence mismatch: "+loud+"/"+quiet+", frames="+frames);
        } finally {if(codec!=null)codec.release();read.release();}
    }
    private JobInfo scheduled() {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        for(JobInfo j:scheduler.getAllPendingJobs()) if(j.getService().getClassName().equals(ArchiveSync.class.getName()))return j;
        return null;
    }
    private void guided() throws Exception {
        SharedPreferences prefs=ArchiveSync.prefs(context);
        prefs.edit().putBoolean("segment",false).putBoolean("auto",false).commit();
        ArchiveStore.setEnabled(context,false);
        Activity page=startActivitySync(new android.content.Intent(context,ArchiveSyncActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            android.view.View[] action={null};
            runOnMainSync(()->action[0]=viewByText(page.getWindow().getDecorView(),"Set up continuous backup"));
            check(action[0]!=null,"Setup action missing from paired archive");
            runOnMainSync(action[0]::performClick);
            android.view.accessibility.AccessibilityNodeInfo[] positive={null};
            until(()->{
                android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
                if(root==null)return false;
                for(android.view.accessibility.AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText("Enable continuous backup"))
                    if(n.isClickable() && n.getText()!=null && "Enable continuous backup".equalsIgnoreCase(n.getText().toString())){
                        positive[0]=n;return true;
                    }
                return false;
            },12000,"Guided consent did not show the confirmation action");
            check(!ArchiveStore.enabled(context) && !prefs.getBoolean("auto",false),"Opening setup enabled recording or transmission");
            check(positive[0].performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"Unable to confirm guided opt-in");
            until(()->ArchiveStore.enabled(context) && prefs.getBoolean("segment",false) && prefs.getBoolean("auto",false),12000,
                    "One explicit guided confirmation did not enable full-session backup");
            check(!RecallService.isListening() && !ArchiveStore.evictSynced(context),
                    "Guided setup started the microphone or evicted files without that choice");
            step("Opened the paired setup dialog and verified capture/sync remained off until the confirmation button. One explicit tap enabled full-session backup without starting a stopped microphone or enabling phone-copy eviction.");
        }finally {ArchiveSync.stop(context);ArchiveStore.setEnabled(context,false);runOnMainSync(page::finish);waitForIdleSync();}
    }
    private static android.view.View viewByText(android.view.View v,String label){
        if(v instanceof android.widget.TextView && label.contentEquals(((android.widget.TextView)v).getText()))return v;
        if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++){android.view.View item=viewByText(g.getChildAt(i),label);if(item!=null)return item;}
        }return null;
    }
    private void verifyOtherCategories(SharedPreferences sync,String excluded) throws Exception {
        String marker = "Synthetic cross-device journal and clip " + UUID.randomUUID();
        try(Store clips=new Store(context); JournalStore journals=new JournalStore(context)) {
            java.lang.reflect.Constructor<SelectiveEngine.Clip> ctor=SelectiveEngine.Clip.class.getDeclaredConstructor(
                    short[].class,String.class,String.class,String.class,long.class);
            ctor.setAccessible(true);
            java.lang.reflect.Method save=Store.class.getDeclaredMethod("save",SelectiveEngine.Clip.class);save.setAccessible(true);
            short[] pcm=new short[600000];
            for(int i=0;i<pcm.length;i++) pcm[i]=(short)((i*7919)^(i>>>3));
            long clip=(Long)save.invoke(clips,ctor.newInstance(pcm,marker,SelectiveEngine.DECISION,"Integration fixture",System.currentTimeMillis()));
            JournalStore.setEnabled(context,true);
            java.lang.reflect.Method getEpoch=JournalStore.class.getDeclaredMethod("epoch",Context.class);
            java.lang.reflect.Method begin=JournalStore.class.getDeclaredMethod("begin",long.class);
            java.lang.reflect.Method append=JournalStore.class.getDeclaredMethod("append",long.class,long.class,String.class,boolean.class);
            java.lang.reflect.Method finish=JournalStore.class.getDeclaredMethod("finish",long.class,String.class);
            getEpoch.setAccessible(true);begin.setAccessible(true);append.setAccessible(true);finish.setAccessible(true);
            long epoch=(Long)getEpoch.invoke(null,context);long journal=(Long)begin.invoke(journals,epoch);
            try{
                check((Boolean)append.invoke(journals,journal,epoch,marker,false),"Unable to seed synthetic journal");
                finish.invoke(journals,journal,"Synthetic journal ended");
                ArchiveSync.stop(context);
                sync.edit().putBoolean("segment",false).putBoolean("clip",true).putBoolean("journal",true).commit();
                ArchiveSync.Plan next=ArchiveSync.preview(context);
                boolean hasClip=false,hasJournal=false;
                for(org.json.JSONObject item:next.batch) {
                    check(!"segment".equals(item.getString("kind")),"Disabled category in preview");
                    if(item.getString("kind").equals("clip") && item.getLong("source")==clip)hasClip=true;
                    if(item.getString("kind").equals("journal") && item.getLong("source")==journal)hasJournal=true;
                }
                check(hasClip&&hasJournal,"Selected original WAV/journal absent from their new scope");
                ArchiveSync.approve(context,next);
                JobInfo current=scheduled();
                if(current!=null)shell("cmd jobscheduler run -f "+context.getPackageName()+" "+current.getId());
                until(()->{
                    try{return ArchiveSync.preview(context).totalCount==0;}
                    catch(Exception e){throw new IllegalStateException(e);}
                },90000,"Selected clip and journal did not receive both acknowledgements: "+sync.getString("status",""));
                try(ArchiveStore archives=new ArchiveStore(context)){
                    check(archives.get(excluded).receipt.isEmpty(),"Unselected AAC was uploaded in the clip/journal batch");
                }
                step("A separately approved clip-and-transcript-only batch reached the real receiver, acknowledged both object kinds, and left unselected continuous audio pending.");
            }finally{clips.delete(clip);journals.delete(journal);JournalStore.setEnabled(context,false);}
        }
    }
    private void transfer() throws Exception {
        check(pair.startsWith("TR1."),"Missing disposable paired receiver");
        SharedPreferences sync=ArchiveSync.prefs(context);
        context.getSharedPreferences("recall-mint-link-v1",Context.MODE_PRIVATE).edit().putString("pair",pair).putBoolean("legacyUploads",false).commit();
        ArchiveSync.reset(context);
        sync.edit().putBoolean("segment",true).putBoolean("clip",false).putBoolean("journal",false).commit();
        try (ArchiveStore store = new ArchiveStore(context)) {
            ArchiveStore.Segment source=findDuration(store,120000);
            ArchiveSync.Plan first=ArchiveSync.preview(context);
            check(first.totalCount==1 && first.totalBytes==source.bytes && first.batchBytes==source.bytes && first.batch.get(0).getString("sha256").equals(source.sha256),"Queue size/hash incorrect");
            ArchiveSync.schedule(context);
            check(scheduled()==null && !sync.contains("manual"),"Preview scheduled an unauthorized transfer");
            shell("svc wifi disable");
            until(()->!ArchiveSync.constraints(context).isEmpty(),12000,"Wi-Fi constraint did not block");
            shell("svc wifi enable");
            sync.edit().putBoolean("wifi",false).commit();
            boolean stale=false;try{ArchiveSync.approve(context,first);}catch(IOException expected){stale=true;}
            check(stale,"Stale preview survived a policy change");
            shell("dumpsys battery unplug");
            until(()->ArchiveSync.constraints(context).contains("charging"),20000,"Charging constraint did not block");
            ArchiveSync.approve(context,ArchiveSync.preview(context));
            check(scheduled()!=null && scheduled().isRequireCharging() && store.get(source.id).receipt.isEmpty(),"Charging-restricted approval did not queue");
            try(ArchiveRecorder late=new ArchiveRecorder(context,store)){feed(late,tone(),13);}
            ArchiveStore.Segment excluded=findDuration(store,1300);
            step("Preview exposed the exact queued bytes/hash. Disabled automation stayed idle; Wi-Fi loss and charging policy blocked the job, and a changed policy invalidated the earlier approval.");
            int job=scheduled().getId();
            // Supply the plugged-in half of the fixture explicitly. A headless AVD can
            // legitimately restore a discharging battery when its override is reset.
            shell("dumpsys battery set ac 1");
            shell("dumpsys battery set level 100");
            shell("dumpsys battery set status 5");
            try{
                until(()->ArchiveSync.constraints(context).isEmpty(),90000,"Constraint still active");
            }catch(AssertionError failed){
                throw new AssertionError("Archive transfer is waiting: "+ArchiveSync.constraints(context)+"; "+shell("dumpsys battery"),failed);
            }
            shell("cmd jobscheduler run -f "+context.getPackageName()+" "+job);
            until(()->!store.get(source.id).receipt.isEmpty(),90000,
                    "No final matching receipt returned by real Mint receiver");
            check(store.get(source.id).local && store.get(excluded.id).receipt.isEmpty(),"Earlier confirmation uploaded a later segment or deleted its source");
            check(ArchiveSync.preview(context).totalCount==1,"Acknowledged source was queued again");
            verifyOtherCategories(sync,excluded.id);
            result.putString("segment",source.id);result.putString("sha256",source.sha256);
            result.putString("excluded",excluded.id);result.putString("bytes",String.valueOf(source.bytes));
            step("An actual pinned HTTPS receiver verified the complete AAC. Android recorded its receipt and left the segment created after confirmation pending.");
        } finally {shell("dumpsys battery reset");shell("svc wifi enable");ArchiveSync.stop(context);}
    }
}
