package org.totalrecall.listening;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import org.continuity.recall.ListenPolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.HttpsURLConnection;

/** Bounded, resumable transfers to the owner's pinned archive. Approvals name exact content hashes. */
public final class ArchiveSync extends JobService {
    static final long MIB=1048576L, INTERVAL=3600000L;
    static final int JOB=0x54524132, MAX_ITEMS=64;
    private static final ReentrantLock WORK=new ReentrantLock();
    private static volatile Transfer running;
    private Transfer jobRun;

    public static SharedPreferences prefs(Context c){return c.getSharedPreferences("recall-archive-sync-v2",MODE_PRIVATE);}
    static String pair(Context c){return prefs(c).getString("destination",MintSync.prefs(c).getString("pair",""));}
    static synchronized void setDestination(Context c,String code)throws Exception{
        ArchiveDestination destination=new ArchiveDestination(code);String previous=prefs(c).getString("destinationIdentity","");
        if(previous.isEmpty()&&!pair(c).isEmpty())previous=new ArchiveDestination(pair(c)).identity;
        stop(c);SharedPreferences.Editor edit=prefs(c).edit().putString("destination",code).putString("destinationIdentity",destination.identity);
        if(!destination.identity.equals(previous)){
            try(ArchiveStore store=new ArchiveStore(c)){store.requeueLocal();}
            for(String key:prefs(c).getAll().keySet())if(key.startsWith("ack:"))edit.remove(key);
        }
        if(!edit.putString("status","Paired. Preview a batch to send content.").commit())throw new IOException("Could not save archive destination");
    }
    static synchronized void disconnect(Context c){stop(c);prefs(c).edit().putString("destination","").commit();}
    static String sha(byte[] b)throws Exception{return MintSync.hex(MessageDigest.getInstance("SHA-256").digest(b));}
    static String binding(Context c)throws Exception{return sha(pair(c).getBytes(StandardCharsets.UTF_8));}
    static long cap(Context c){return Math.min(256,Math.max(8,prefs(c).getInt("capMiB",32)))*MIB;}
    static boolean selected(Context c,String kind){return prefs(c).getBoolean(kind,"clip".equals(kind));}
    static boolean auto(Context c){return prefs(c).getBoolean("auto",false);}
    static boolean wifi(Context c){return prefs(c).getBoolean("wifi",true);}
    static boolean charging(Context c){return prefs(c).getBoolean("charging",true);}
    public static String constraints(Context c){
        ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);
        Network n=cm==null?null:cm.getActiveNetwork();
        NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);
        if(caps==null)return "Waiting for network";
        if(wifi(c)&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return "Waiting for Wi-Fi";
        BatteryManager battery=c.getSystemService(BatteryManager.class);
        if(charging(c)&&(battery==null||!battery.isCharging()))return "Waiting for charging";
        return "";
    }
    private static String settings(Context c){
        return selected(c,"clip")+"|"+selected(c,"segment")+"|"+selected(c,"journal")+"|"+wifi(c)+"|"+charging(c)+"|"+cap(c);
    }
    public static final class Plan {
        public long totalCount,oversized;
        public long totalBytes,batchBytes;
        public final ArrayList<JSONObject> batch=new ArrayList<>();
        final String paired,settings;
        private final Map<String,List<JSONObject>> candidates=new LinkedHashMap<>();
        Plan(Context c)throws Exception{paired=binding(c);settings=ArchiveSync.settings(c);}
        void add(Context c,JSONObject item)throws Exception{
            long bytes=item.getLong("bytes");
            totalCount++;totalBytes+=bytes;
            long limit="segment".equals(item.getString("kind"))?128*MIB:16*MIB;
            if(bytes>limit||bytes>cap(c)){oversized++;return;}
            List<JSONObject> group=candidates.computeIfAbsent(item.getString("kind"),key->new ArrayList<>());
            if(group.size()<MAX_ITEMS)group.add(item);
        }
        void select(Context c)throws Exception{
            for(int i=0;i<MAX_ITEMS&&batch.size()<MAX_ITEMS;i++){
                for(String kind:new String[]{"clip","journal","segment"}){
                    List<JSONObject> group=candidates.get(kind);
                    if(group==null||i>=group.size()||batch.size()>=MAX_ITEMS)continue;
                    JSONObject obj=group.get(i);long bytes=obj.getLong("bytes");
                    if(batchBytes+bytes<=cap(c)){batch.add(obj);batchBytes+=bytes;}
                }
            }
        }
    }
    private static final class Fingerprint extends OutputStream {
        final MessageDigest hash;long count;
        Fingerprint()throws Exception{hash=MessageDigest.getInstance("SHA-256");}
        public void write(int b){hash.update((byte)b);count++;}
        public void write(byte[] b,int off,int n){hash.update(b,off,n);count+=n;}
    }
    private static JSONObject item(String kind,String id,long source,long start,long duration,long bytes,String hash,String title,String mime)throws Exception{
        return new JSONObject().put("kind",kind).put("id",id).put("source",source).put("startedAtMillis",start)
                .put("durationMs",duration).put("bytes",bytes).put("sha256",hash).put("title",title.substring(0,Math.min(400,title.length()))).put("mediaType",mime);
    }
    private static boolean sent(Context c,String id,String hash){return hash.equals(prefs(c).getString("ack:"+id,""));}
    public static Plan preview(Context c)throws Exception{
        Plan plan=new Plan(c);
        if(selected(c,"segment")) try(ArchiveStore store=new ArchiveStore(c)){
            ArchiveStore.Summary totals=store.summary();
            for(ArchiveStore.Segment s:store.pending(MAX_ITEMS)){
                plan.add(c,item("segment",s.id,0,s.startMs,s.durationMs,s.bytes,s.sha256,"Continuous audio","audio/aac"));
            }
            plan.totalCount=totals.pendingCount;plan.totalBytes=totals.pendingBytes;
        }
        if(selected(c,"clip"))try(Store store=new Store(c)){
            for(Store.Moment m:store.search("")){
                String revision=revision(m);
                if(sent(c,"clip-"+m.uid,revision))continue;
                Fingerprint out=new Fingerprint();store.exportZip(out,Collections.singletonList(m.id));
                String hash=MintSync.hex(out.hash.digest());
                String uploadId=m.uid.length()<=102?m.uid+"-"+hash.substring(0,24):"clip-"+hash;
                plan.add(c,item("clip",uploadId,m.id,m.startMs,Math.max(0,m.endMs-m.startMs),
                    out.count,hash,m.title,"application/zip").put("original",m.uid).put("revision",revision));
            }
        }
        if(selected(c,"journal"))try(JournalStore store=new JournalStore(c)){
            for(JournalStore.Session s:store.sessions()){
                if(s.endMs<=0||s.count==0)continue;
                Fingerprint out=new Fingerprint();store.export(s.id,out);
                String hash=MintSync.hex(out.hash.digest()), id="journal-"+s.startMs+"-"+hash.substring(0,24);
                if(!sent(c,id,hash))plan.add(c,item("journal",id,s.id,s.startMs,Math.max(0,s.endMs-s.startMs),out.count,hash,"Transcript journal","text/plain"));
            }
        }
        plan.select(c);return plan;
    }
    private static String revision(Store.Moment m)throws Exception{
        return sha(new JSONArray().put(m.uid).put(m.sha256).put(m.startMs).put(m.endMs).put(m.title).put(m.transcript).put(m.kind)
                .put(m.reason).put(m.note).put(m.correction).put(m.feedback).put(m.parentId).put(m.startSample).put(m.endSample)
                .toString().getBytes(StandardCharsets.UTF_8));
    }
    public static synchronized void approve(Context c,Plan plan)throws Exception{
        if(pair(c).isEmpty()||!plan.paired.equals(binding(c))||!plan.settings.equals(settings(c)))throw new IOException("Archive or transfer choices changed. Preview again.");
        if(!ListenPolicy.allowed(c))throw new IOException("Resume sharing first.");
        if(running!=null || prefs(c).contains("manual"))throw new IOException("A batch is already queued. Stop it before confirming another.");
        if(plan.batch.isEmpty())throw new IOException("No eligible items in this batch.");
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);if(scheduler!=null)scheduler.cancel(JOB);
        if(!prefs(c).edit().putString("manual",new JSONArray(plan.batch).toString()).putString("manualPair",plan.paired)
                .putString("manualSettings",plan.settings).putString("status","Confirmed batch queued").commit())throw new IOException("Unable to store confirmation.");
        schedule(c);
    }
    static synchronized void changed(Context c){
        cancel(c);
        prefs(c).edit().putBoolean("auto",false).remove("manual").remove("automaticPlan").putString("status","Transfer settings changed. Preview or enable automatic transfers.").commit();
    }
    public static synchronized void stop(Context c){
        prefs(c).edit().putBoolean("auto",false).remove("manual").remove("automaticPlan").putString("status","Transfers stopped").commit();cancel(c);
    }
    public static synchronized void reset(Context c){stop(c);prefs(c).edit().clear().commit();}
    private static void cancel(Context c){
        Transfer t=running;if(t!=null)t.abort();
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);if(scheduler!=null)scheduler.cancel(JOB);
    }
    public static synchronized void schedule(Context c){
        boolean manual=prefs(c).contains("manual");
        if((!manual&&!auto(c))||pair(c).isEmpty()||!ListenPolicy.allowed(c))return;
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        if(scheduler==null||scheduler.getPendingJob(JOB)!=null)return;
        long delay=manual?0:Math.max(0,Math.min(INTERVAL,prefs(c).getLong("next",0)-System.currentTimeMillis()));
        JobInfo.Builder job=new JobInfo.Builder(JOB,new ComponentName(c,ArchiveSync.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true).setRequiresCharging(charging(c)).setBackoffCriteria(60000,JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if(delay>0)job.setMinimumLatency(delay);
        try{if(scheduler.schedule(job.build())!=JobScheduler.RESULT_SUCCESS)prefs(c).edit().putString("status","Scheduling deferred by Android").apply();}
        catch(RuntimeException e){prefs(c).edit().putString("status","Scheduling deferred by Android").apply();}
    }
    static JSONObject exchange(ArchiveDestination p,String path,String method,byte[] body,int length,long offset,Transfer t)throws Exception{
        HttpsURLConnection conn=p.open(path);
        if(t!=null)t.connection=conn;
        try{
            if(t!=null)t.check();conn.setRequestMethod(method);
            if(body!=null){
                conn.setDoOutput(true);conn.setRequestProperty("Content-Type",offset<0?"application/json":"application/octet-stream");
                if(offset>=0)conn.setRequestProperty("X-Upload-Offset",String.valueOf(offset));
                conn.setFixedLengthStreamingMode(length);
                try(OutputStream out=conn.getOutputStream()){
                    for(int i=0;i<length;i+=32768){if(t!=null)t.check();out.write(body,i,Math.min(32768,length-i));}
                }
            }
            int status=conn.getResponseCode();
            if(status!=200 && status!=201)throw new IOException(status==507?"Archive is full or below reserve.":"Archive HTTP "+status);
            ByteArrayOutputStream buf=new ByteArrayOutputStream();
            try(InputStream in=conn.getInputStream()){
                byte[] b=new byte[1024];for(int n;(n=in.read(b))!=-1;){if(buf.size()+n>16384)throw new IOException("Invalid archive response");buf.write(b,0,n);}
            }
            return new JSONObject(buf.toString("UTF-8"));
        }finally{if(t!=null)t.connection=null;conn.disconnect();}
    }
    static JSONObject capacity(Context c)throws Exception{
        if(pair(c).isEmpty())throw new IOException("Pair an archive first.");
        if(!ListenPolicy.allowed(c))throw new IOException("Sharing is paused.");
        return exchange(new ArchiveDestination(pair(c)),"/v2/status","GET",null,0,-1,null);
    }
    static final class Transfer {
        final Context c;final String paired,config;final ArchiveDestination remote;volatile boolean stopped;volatile HttpsURLConnection connection;
        Transfer(Context c)throws Exception{this.c=c.getApplicationContext();paired=pair(c);remote=new ArchiveDestination(paired);config=settings(c);}
        void abort(){stopped=true;HttpsURLConnection conn=connection;if(conn!=null)new Thread(conn::disconnect,"archive-cancel").start();}
        void check()throws IOException{
            if(stopped||!paired.equals(pair(c))||!config.equals(settings(c))||!ListenPolicy.allowed(c))throw new IOException("Transfer stopped");
            String blocked=constraints(c);if(!blocked.isEmpty())throw new IOException(blocked);
        }
        void send(JSONObject obj,InputStream raw)throws Exception{
            String id=obj.getString("id"),sha=obj.getString("sha256");long size=obj.getLong("bytes");
            JSONObject header=new JSONObject();
            for(String key:new String[]{"id","kind","startedAtMillis","durationMs","bytes","sha256","mediaType","title"})header.put(key,obj.get(key));
            byte[] manifest=header.toString().getBytes(StandardCharsets.UTF_8);
            JSONObject ack=exchange(remote,"/v2/uploads","POST",manifest,manifest.length,-1,this);
            long offset=validate(ack,id,sha,size);
            if(!ack.getBoolean("complete")){
                DataInputStream in=new DataInputStream(raw);
                long skipped=0;byte[] chunk=new byte[(int)Math.min(MIB,size)];
                while(skipped<offset){int n=in.read(chunk,0,(int)Math.min(chunk.length,offset-skipped));if(n<0)throw new EOFException();skipped+=n;}
                while(offset<size){
                    check();int n=(int)Math.min(chunk.length,size-offset);in.readFully(chunk,0,n);
                    ack=exchange(remote,"/v2/uploads/"+id,"PUT",chunk,n,offset,this);
                    long next=validate(ack,id,sha,size);
                    if(next!=offset+n)throw new IOException("Archive offset mismatch");offset=next;
                }
            }
            if(!ack.getBoolean("complete")||offset!=size)throw new IOException("Missing final archive receipt");
            synchronized(ArchiveSync.class){
                check();
                if("segment".equals(obj.getString("kind"))){
                    try(ArchiveStore store=new ArchiveStore(c)){
                        if(!store.markSynced(id,sha,ack.toString()))throw new IOException("Unable to save segment receipt");
                    }
                }else{
                    String key=obj.optString("original","").isEmpty()?id:"clip-"+obj.getString("original");
                    if(!prefs(c).edit().putString("ack:"+key,obj.optString("revision",sha)).commit())throw new IOException("Unable to save transfer receipt");
                }
            }
        }
        long validate(JSONObject ack,String id,String sha,long size)throws Exception{
            if(!ack.getBoolean("ok")||!id.equals(ack.getString("id"))||!sha.equals(ack.getString("sha256"))||size!=ack.getLong("bytes"))throw new IOException("Archive receipt mismatch");
            long offset=ack.getLong("offset");
            if(offset<0 || offset>size || ack.getBoolean("complete")!=(offset==size))throw new IOException("Invalid archive progress");
            return offset;
        }
        void one(JSONObject obj)throws Exception{
            check();String kind=obj.getString("kind"),id=obj.getString("id");
            if(!selected(c,kind))throw new IOException("Content choice changed");
            if("segment".equals(kind)){
                try(ArchiveStore store=new ArchiveStore(c)){
                    ArchiveStore.Segment s=store.get(id);if(s==null)return;
                    if(!s.receipt.isEmpty())return;
                    if(s.bytes!=obj.getLong("bytes")||!s.sha256.equals(obj.getString("sha256")))throw new IOException("Segment changed");
                    try(InputStream in=store.open(id)){send(obj,in);}
                }
                return;
            }
            long headroom=c.getCacheDir().getUsableSpace()-ArchiveStore.reserveMiB(c)*MIB;
            if(headroom<obj.getLong("bytes"))throw new IOException("Insufficient staging space above phone reserve");
            File tmp=File.createTempFile("archive-out-",".tmp",c.getCacheDir());
            try{
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                try(OutputStream out=new java.security.DigestOutputStream(new FileOutputStream(tmp),digest)){
                    if("clip".equals(kind))try(Store store=new Store(c)){
                        Store.Moment m=store.get(obj.getLong("source"));if(m==null ||!revision(m).equals(obj.getString("revision")))return;
                        store.exportZip(out,Collections.singletonList(m.id));
                    }else if("journal".equals(kind))try(JournalStore store=new JournalStore(c)){
                        store.export(obj.getLong("source"),out);
                    }else throw new IOException("Unknown content type");
                }
                if(tmp.length()!=obj.getLong("bytes")||!MintSync.hex(digest.digest()).equals(obj.getString("sha256")))return;
                try(InputStream in=new FileInputStream(tmp)){send(obj,in);}
            }finally{tmp.delete();}
        }
        void run()throws Exception{
            if(!WORK.tryLock())throw new IOException("Another transfer is running");
            try{
                running=this;check();
                File[] stale=c.getCacheDir().listFiles((dir,name)->name.startsWith("archive-out-")&&name.endsWith(".tmp"));
                if(stale!=null)for(File f:stale)f.delete();
                SharedPreferences p=prefs(c);
                String manual=p.getString("manual",null);List<JSONObject> batch=new ArrayList<>();
                if(manual!=null){
                    if(!binding(c).equals(p.getString("manualPair",""))||!config.equals(p.getString("manualSettings","")))throw new IOException("Confirmation expired");
                    JSONArray arr=new JSONArray(manual);for(int i=0;i<arr.length();i++)batch.add(arr.getJSONObject(i));
                }else{
                    if(!auto(c))return;
                    String pending=p.getString("automaticPlan",null);
                    if(pending!=null){
                        JSONArray arr=new JSONArray(pending);for(int i=0;i<arr.length();i++)batch.add(arr.getJSONObject(i));
                    }else{
                        if(p.getLong("next",0)>System.currentTimeMillis())return;
                        batch=preview(c).batch;
                        synchronized(ArchiveSync.class){check();if(!p.edit().putString("automaticPlan",new JSONArray(batch).toString()).commit())throw new IOException("Unable to queue batch");}
                    }
                }
                long bytes=0;for(JSONObject obj:batch)bytes=Math.addExact(bytes,obj.getLong("bytes"));
                if(bytes>cap(c)||batch.size()>MAX_ITEMS)throw new IOException("Batch exceeds confirmed limit");
                if(!batch.isEmpty()){
                    JSONObject remoteStatus=exchange(remote,"/v2/status","GET",null,0,-1,this);
                    if(remoteStatus.getLong("availableBytes")<0)throw new IOException("Invalid server capacity");
                }
                while(!batch.isEmpty()){
                    check();one(batch.get(0));batch.remove(0);
                    synchronized(ArchiveSync.class){
                        check();if(!p.edit().putString(manual!=null?"manual":"automaticPlan",new JSONArray(batch).toString()).commit())throw new IOException("Unable to save batch progress");
                    }
                }
                synchronized(ArchiveSync.class){
                    check();SharedPreferences.Editor edit=p.edit().putString("status","Batch complete · "+java.text.DateFormat.getDateTimeInstance().format(new Date()));
                    if(manual!=null)edit.remove("manual");else edit.remove("automaticPlan").putLong("next",System.currentTimeMillis()+INTERVAL);edit.commit();
                }
            }finally{running=null;WORK.unlock();}
        }
    }
    @Override public boolean onStartJob(JobParameters job){
        final Transfer t;
        try{t=new Transfer(this);}catch(Exception e){return false;}
        jobRun=t;
        new Thread(()->{
            boolean retry=false;try{t.run();}catch(Exception e){retry=true;if(!t.stopped)prefs(this).edit().putString("status",
                "Pending · "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())).apply();}
            final boolean later=retry;
            new Handler(Looper.getMainLooper()).post(()->{
                if(jobRun!=t)return;jobRun=null;jobFinished(job,later);
                if(!later && auto(this))new Handler(Looper.getMainLooper()).postDelayed(()->schedule(this),1000);
            });
        },"archive-transfer").start();return true;
    }
    @Override public boolean onStopJob(JobParameters job){Transfer t=jobRun;jobRun=null;if(t!=null)t.abort();return auto(this)||prefs(this).contains("manual");}
}
