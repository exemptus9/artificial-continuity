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
    static final long MIB=1048576L;
    static final int WATCH=0x54524133, JOB=0x54524132, BACKLOG_ITEMS=4096;
    static final long BACKLOG_BYTES=1024*MIB;
    private static final ReentrantLock WORK=new ReentrantLock();
    private static volatile Transfer running;
    private Transfer jobRun;

    public static SharedPreferences prefs(Context c){return c.getSharedPreferences("recall-archive-sync-v2",MODE_PRIVATE);}
    static String pair(Context c){return prefs(c).getString("destination",MintSync.prefs(c).getString("pair",""));}
    static synchronized void setDestination(Context c,String code)throws Exception{
        ArchiveDestination destination=new ArchiveDestination(code);String previous=prefs(c).getString("destinationIdentity","");
        if(previous.isEmpty()&&!pair(c).isEmpty())previous=new ArchiveDestination(pair(c)).identity;
        migrateLimits(c);stop(c);SharedPreferences.Editor edit=prefs(c).edit().putString("destination",code).putString("destinationIdentity",destination.identity);
        if(!destination.identity.equals(previous)){
            try(ArchiveStore store=new ArchiveStore(c)){store.requeueLocal();}
            edit.remove("lastReceiptAt").remove("lastReceiptKind").remove("next");
            for(String key:prefs(c).getAll().keySet())if(key.startsWith("ack:"))edit.remove(key);
        }
        if(!edit.putString("status","Paired. Preview a batch to send content.").commit())throw new IOException("Could not save archive destination");
    }
    static synchronized void enableContinuous(Context c,String expectedPair,String expectedPolicy,boolean freeVerified)throws Exception{
        if(pair(c).isEmpty()||!binding(c).equals(expectedPair)||!settings(c).equals(expectedPolicy))throw new IOException("Destination or transfer settings changed. Open setup again.");
        if(!ListenPolicy.allowed(c))throw new IOException("Resume sharing in the phone vault first.");
        stop(c);
        boolean earlier=ArchiveStore.enabled(c),eviction=ArchiveStore.evictSynced(c);
        try{
            ArchiveStore.setEvictSynced(c,freeVerified);
            ArchiveStore.setEnabled(c,true);
            if(!prefs(c).edit().putBoolean("segment",true).putBoolean("auto",true).remove("next")
                .putString("status","Continuous backup enabled").commit())throw new IOException("Could not save continuous backup choices");
        }catch(Exception e){
            ArchiveStore.setEnabled(c,earlier);ArchiveStore.setEvictSynced(c,eviction);throw e;
        }
        schedule(c);
    }
    static synchronized void disconnect(Context c){stop(c);prefs(c).edit().putString("destination","").commit();}
    static String sha(byte[] b)throws Exception{return MintSync.hex(MessageDigest.getInstance("SHA-256").digest(b));}
    static String binding(Context c)throws Exception{return sha(pair(c).getBytes(StandardCharsets.UTF_8));}
    private static synchronized void migrateLimits(Context c){
        SharedPreferences p=prefs(c);if(p.contains("limitsVersion"))return;
        boolean earlier=p.contains("auto")||p.contains("capMiB")||p.contains("manual")||p.contains("automaticPlan")||p.contains("manualSettings");
        SharedPreferences.Editor edit=p.edit().putInt("limitsVersion",1);
        if(!p.contains("capMiB"))edit.putInt("capMiB",earlier?32:128);
        if(!p.contains("maxItems"))edit.putInt("maxItems",earlier?64:256);
        if(!edit.commit())throw new IllegalStateException("Could not save transfer limits");
    }
    static long cap(Context c){
        migrateLimits(c);return Math.min(1024,Math.max(8,prefs(c).getInt("capMiB",128)))*MIB;
    }
    static int maxItems(Context c){
        migrateLimits(c);return Math.min(BACKLOG_ITEMS,Math.max(1,prefs(c).getInt("maxItems",256)));
    }
    static boolean selected(Context c,String kind){return prefs(c).getBoolean(kind,"clip".equals(kind));}
    static int minutes(Context c){int n=prefs(c).getInt("minutes",60);return n==5||n==15?n:60;}
    public static String backupStatus(Context c){
        if(pair(c).isEmpty())return "Phone only · archive unpaired";
        boolean automatic=auto(c),manual=prefs(c).contains("manual");
        String state=automatic?"Auto backup · "+minutes(c)+" min":manual?"Confirmed batch queued":"Manual backup";
        if(automatic||manual){
            String gate=constraints(c);
            if(!gate.isEmpty())state+=" · "+gate;
            else if(running!=null)state+=" · transferring";
            else if(automatic&&!manual){
                long remaining=prefs(c).getLong("next",0)-System.currentTimeMillis();
                if(remaining>0)state+=" · next batch eligible in "+((remaining+59999)/60000)+" min";
            }
        }
        long receipt=prefs(c).getLong("lastReceiptAt",0);
        return state+(receipt>0?"\nLast verified "+prefs(c).getString("lastReceiptKind","item")+" · "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(new Date(receipt)):"\nNo receipt recorded by this version for this destination");
    }
    static boolean auto(Context c){return prefs(c).getBoolean("auto",false);}
    static boolean wifi(Context c){return prefs(c).getBoolean("wifi",true);}
    static boolean preferWifi(Context c){return prefs(c).getBoolean("preferWifi",false);}
    static String networkLabel(Context c){return wifi(c)?"Wi-Fi only":preferWifi(c)?"Prefer Wi-Fi · mobile after 1 hour":"Any network";}
    static boolean onWifi(Context c){
        ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps=cm==null?null:cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
    static boolean charging(Context c){return prefs(c).getBoolean("charging",true);}
    public static String constraints(Context c){
        ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);
        Network n=cm==null?null:cm.getActiveNetwork();
        NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);
        if(caps==null)return "Waiting for network";
        if(wifi(c)&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return "Waiting for Wi-Fi";
        long since=prefs(c).getLong("wifiWait",0);
        if(preferWifi(c) && since>0 && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && System.currentTimeMillis()-since<3600000L)
            return "Waiting for preferred Wi-Fi · mobile allowed after 1 hour";
        BatteryManager battery=c.getSystemService(BatteryManager.class);
        if(charging(c)&&(battery==null||!battery.isCharging()))return "Waiting for charging";
        return "";
    }
    static String settings(Context c){
        return selected(c,"clip")+"|"+selected(c,"segment")+"|"+selected(c,"journal")+"|"+wifi(c)+"|"+charging(c)+"|"+cap(c)+"|"+maxItems(c)+(preferWifi(c)?"|preferWifi":"")+(minutes(c)==60?"":"|spacing="+minutes(c));
    }
    public static final class Plan {
        public long totalCount,oversized;
        public long totalBytes,batchBytes;
        public final ArrayList<JSONObject> batch=new ArrayList<>();
        final String paired,settings;
        final long byteLimit;final int itemLimit;final boolean backlog;
        private long checkedSpace=-1;
        private final Map<String,List<JSONObject>> candidates=new LinkedHashMap<>();
        Plan(Context c)throws Exception{this(c,false);}
        Plan(Context c,boolean backlog)throws Exception{
            this(backlog,backlog?BACKLOG_BYTES:cap(c),backlog?BACKLOG_ITEMS:maxItems(c),binding(c),ArchiveSync.settings(c));
        }
        Plan(boolean backlog,long byteLimit,int itemLimit,String paired,String settings){
            this.backlog=backlog;this.byteLimit=byteLimit;this.itemLimit=itemLimit;this.paired=paired;this.settings=settings;
        }
        boolean checkSpace(long bytes){checkedSpace=bytes;return bytes>=batchBytes;}
        void add(Context c,JSONObject item)throws Exception{
            long bytes=item.getLong("bytes");
            totalCount++;totalBytes+=bytes;
            long limit="segment".equals(item.getString("kind"))?128*MIB:16*MIB;
            if(bytes<=0||bytes>limit||bytes>byteLimit){oversized++;return;}
            List<JSONObject> group=candidates.computeIfAbsent(item.getString("kind"),key->new ArrayList<>());
            if(group.size()<itemLimit)group.add(item);
        }
        void select(Context c)throws Exception{
            for(int i=0;i<itemLimit&&batch.size()<itemLimit;i++){
                for(String kind:new String[]{"clip","journal","segment"}){
                    List<JSONObject> group=candidates.get(kind);
                    if(group==null||i>=group.size()||batch.size()>=itemLimit)continue;
                    JSONObject obj=group.get(i);long bytes=obj.getLong("bytes");
                    if(bytes<=byteLimit-batchBytes){batch.add(obj);batchBytes+=bytes;}
                }
            }
            candidates.clear();
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
    public static Plan preview(Context c)throws Exception{return preview(c,false);}
    public static Plan preview(Context c,boolean backlog)throws Exception{
        Plan plan=new Plan(c,backlog);
        if(selected(c,"segment")) try(ArchiveStore store=new ArchiveStore(c)){
            ArchiveStore.Summary totals=store.summary();
            long examine=Math.min(totals.pendingCount,plan.itemLimit);
            for(int offset=0;offset<examine;){
                List<ArchiveStore.Segment> segments=store.pending((int)Math.min(256,examine-offset),offset);
                if(segments.isEmpty())break;
                for(ArchiveStore.Segment s:segments)
                    plan.add(c,item("segment",s.id,0,s.startMs,s.durationMs,s.bytes,s.sha256,"Continuous audio","audio/aac"));
                offset+=segments.size();
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
        if(plan.backlog && plan.checkedSpace<plan.batchBytes)throw new IOException("Check server space for this backlog and preview again.");
        long total=0;for(JSONObject obj:plan.batch)total=Math.addExact(total,obj.getLong("bytes"));
        if(plan.batch.size()>plan.itemLimit||total!=plan.batchBytes||total>plan.byteLimit)throw new IOException("Preview exceeds its confirmed limits");
        try(ArchiveBatchQueue q=new ArchiveBatchQueue(c)){
            SharedPreferences p=prefs(c);
            q.retain(p.getString("manual",null),p.getString("automaticPlan",null));
            String ref=q.put(plan.batch);
            if(!p.edit().putString("manual",ref).putBoolean("manualBacklog",plan.backlog).putString("manualPair",plan.paired)
                .putString("manualSettings",plan.settings).putString("status","Confirmed batch queued").commit())
                throw new IOException("Unable to store confirmation");
        }
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);if(scheduler!=null)scheduler.cancel(JOB);
        schedule(c);
    }
    static synchronized void changed(Context c){
        migrateLimits(c);cancel(c);LimitAlerts.remote(c,false);
        prefs(c).edit().putBoolean("auto",false).remove("wifiWait").remove("manual").remove("manualBacklog").remove("automaticPlan").putString("status","Transfer settings changed. Preview or enable automatic transfers.").commit();
        clearQueue(c);
    }
    public static synchronized void stop(Context c){
        LimitAlerts.remote(c,false);
        prefs(c).edit().putBoolean("auto",false).remove("wifiWait").remove("manual").remove("manualBacklog").remove("automaticPlan").putString("status","Transfers stopped").commit();cancel(c);clearQueue(c);
    }
    public static synchronized void reset(Context c){
        stop(c);prefs(c).edit().clear().commit();
    }
    private static void clearQueue(Context c){
        if(c.getDatabasePath("archive-transfer-queue.sqlite").exists())
            try(ArchiveBatchQueue q=new ArchiveBatchQueue(c)){q.retain(null,null);}
    }
    private static void cancel(Context c){
        Transfer t=running;if(t!=null)t.abort();
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);if(scheduler!=null){scheduler.cancel(JOB);scheduler.cancel(WATCH);}
    }
    public static synchronized void schedule(Context c){
        boolean manual=prefs(c).contains("manual");
        if((!manual&&!auto(c))||pair(c).isEmpty()||!ListenPolicy.allowed(c))return;
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        if(scheduler==null)return;
        if(auto(c)&&scheduler.getPendingJob(WATCH)==null)try{
            scheduler.schedule(new JobInfo.Builder(WATCH,new ComponentName(c,ArchiveSync.class))
                .setPersisted(true).setPeriodic(15*60000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresCharging(charging(c)).build());
        }catch(RuntimeException ignored){/* A capture or app resume can still queue the primary job. */}
        if(scheduler.getPendingJob(JOB)!=null)return;
        long delay=manual?0:Math.max(0,Math.min(minutes(c)*60000L,prefs(c).getLong("next",0)-System.currentTimeMillis()));
        JobInfo.Builder job=new JobInfo.Builder(JOB,new ComponentName(c,ArchiveSync.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true).setRequiresCharging(charging(c)).setBackoffCriteria(60000,JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if(wifi(c)&&android.os.Build.VERSION.SDK_INT>=28)job.setRequiredNetwork(new android.net.NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build());
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
            if(t!=null && (status==507 || status==429))LimitAlerts.remote(t.c,true);
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
                prefs(c).edit().putLong("lastReceiptAt",System.currentTimeMillis())
                    .putString("lastReceiptKind",obj.getString("kind")).apply();
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
        boolean idle;
        void run()throws Exception{
            if(!WORK.tryLock())throw new IOException("Another transfer is running");
            try{
                running=this;check();
                File[] stale=c.getCacheDir().listFiles((dir,name)->name.startsWith("archive-out-")&&name.endsWith(".tmp"));
                if(stale!=null)for(File f:stale)f.delete();
                SharedPreferences p=prefs(c);
                String manual=p.getString("manual",null),key=manual!=null?"manual":"automaticPlan";
                String ref=p.getString(key,null);
                boolean backlog=manual!=null&&p.getBoolean("manualBacklog",false);
                if(manual!=null){
                    String stored=p.getString("manualSettings","");
                    boolean legacy=!ArchiveBatchQueue.reference(ref)&&maxItems(c)==64&&config.equals(stored+"|64");
                    if(!binding(c).equals(p.getString("manualPair",""))||(!config.equals(stored)&&!legacy))throw new IOException("Confirmation expired");
                }else if(!auto(c))return;
                try(ArchiveBatchQueue q=new ArchiveBatchQueue(c)){
                    synchronized(ArchiveSync.class){check();q.retain(p.getString("manual",null),p.getString("automaticPlan",null));}
                    if(!ArchiveBatchQueue.reference(ref)){
                        List<JSONObject> batch=new ArrayList<>();
                        if(ref!=null){
                            JSONArray arr=new JSONArray(ref);for(int i=0;i<arr.length();i++)batch.add(arr.getJSONObject(i));
                        }else{
                            if(p.getLong("next",0)>System.currentTimeMillis())return;
                            batch=preview(c).batch;
                        }
                        synchronized(ArchiveSync.class){
                            check();ref=q.put(batch);SharedPreferences.Editor edit=p.edit().putString(key,ref);
                            if(manual!=null)edit.putString("manualSettings",config);
                            if(!edit.commit())throw new IOException("Unable to persist transfer queue");
                        }
                    }
                    ArchiveBatchQueue.Summary summary=q.summary(ref);
                    if(summary.bytes>(backlog?BACKLOG_BYTES:cap(c))||summary.count>(backlog?BACKLOG_ITEMS:maxItems(c)))
                        throw new IOException("Batch exceeds confirmed limits");
                    if(summary.count>0){
                        if(preferWifi(c) && !onWifi(c) && !p.contains("wifiWait")){
                            if(!p.edit().putLong("wifiWait",System.currentTimeMillis()).commit())throw new IOException("Cannot save Wi-Fi preference timer");
                        }
                        check();
                        JSONObject remoteStatus=exchange(remote,"/v2/status","GET",null,0,-1,this);
                        if(remoteStatus.getLong("availableBytes")<0)throw new IOException("Invalid server capacity");
                    }
                    for(ArchiveBatchQueue.Row row;(row=q.next(ref))!=null;){
                        check();one(row.item);LimitAlerts.remote(c,false);
                        synchronized(ArchiveSync.class){check();q.done(ref,row.position);}
                    }
                    synchronized(ArchiveSync.class){
                        check();idle=manual==null && summary.count==0;
                        SharedPreferences.Editor edit=p.edit().remove("wifiWait").putString("status",idle?"No eligible items in this check":
                            "Batch checked · "+java.text.DateFormat.getDateTimeInstance().format(new Date()));
                        if(manual!=null)edit.remove("manual").remove("manualBacklog");else edit.remove("automaticPlan").putLong("next",idle?0:System.currentTimeMillis()+minutes(c)*60000L);
                        if(!edit.commit())throw new IOException("Unable to save transfer completion");
                    }
                }
            }finally{running=null;WORK.unlock();}
        }
    }
    @Override public boolean onStartJob(JobParameters job){
        if(job.getJobId()==LimitAlerts.JOB){
            new Thread(()->{try{LimitAlerts.remind(this);}finally{jobFinished(job,false);}},"archive-limit-check").start();
            return true;
        }
        if(job.getJobId()==WATCH){schedule(this);return false;}
        final Transfer t;
        try{t=new Transfer(this);}catch(Exception e){return false;}
        jobRun=t;
        new Thread(()->{
            boolean retry=false;try{t.run();}catch(Exception e){retry=true;if(!t.stopped)prefs(this).edit().putString("status",
                "Pending · "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())).apply();}
            final boolean later=retry;
            new Handler(Looper.getMainLooper()).post(()->{
                if(jobRun!=t)return;jobRun=null;jobFinished(job,later);
                if(!later && !t.idle && auto(this))new Handler(Looper.getMainLooper()).postDelayed(()->schedule(this),1000);
            });
        },"archive-transfer").start();return true;
    }
    @Override public boolean onStopJob(JobParameters job){if(job.getJobId()==WATCH || job.getJobId()==LimitAlerts.JOB)return false;Transfer t=jobRun;jobRun=null;if(t!=null)t.abort();return auto(this)||prefs(this).contains("manual");}
}
