package org.totalrecall.listening;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.continuity.recall.ListenPolicy;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Sends only committed selective clips to the explicitly paired and certificate-pinned receiver. */
public final class MintSync extends JobService {
    private static final int JOB = 0x54524d53;
    static final long MAX_ZIP = 16L * 1024 * 1024;
    private volatile Run current;
    private static long requestNumber;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("recall-mint-link-v1", MODE_PRIVATE);
    }

    static final class Pair {
        final String base, host, token, pin;
        Pair(String encoded) throws Exception {
            if (!encoded.startsWith("TR1.") || encoded.length() > 3000) throw new IOException("Invalid pairing code");
            String json = new String(Base64.decode(encoded.substring(4), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(json);
            base = o.getString("url");
            URI uri = new URI(base);
            host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || !isPrivateIPv4(host) || uri.getPort() <= 0 || uri.getPort() > 65535 ||
                !base.equals("https://" + host + ":" + uri.getPort())) throw new IOException("Pairing needs a private LAN IPv4 HTTPS address");
            token = o.getString("token");
            pin = o.getString("pin");
            if (!token.matches("[0-9a-fA-F]{64}") || !pin.matches("[0-9a-f]{64}")) throw new IOException("Invalid receiver key or certificate fingerprint");
        }
    }

    static boolean isPrivateIPv4(String host) {
        if (host == null) return false;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] oct = new int[4];
        for (int i=0;i<4;i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return false;
            oct[i] = Integer.parseInt(parts[i]);
            if (oct[i] > 255) return false;
        }
        return oct[0]==10 || (oct[0]==192 && oct[1]==168) || (oct[0]==172 && oct[1]>=16 && oct[1]<=31);
    }

    public static synchronized void schedule(Context c) {
        if (prefs(c).getString("pair", "").isEmpty() || !ListenPolicy.allowed(c)) return;
        requestNumber++;
        try {
            JobScheduler scheduler = c.getSystemService(JobScheduler.class);
            if (scheduler == null || scheduler.getPendingJob(JOB) != null) return;
            JobInfo info = new JobInfo.Builder(JOB, new ComponentName(c, MintSync.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(60000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
            if (scheduler.schedule(info) != JobScheduler.RESULT_SUCCESS)
                prefs(c).edit().putString("status","Android deferred scheduling. Use Sync now to retry.").apply();
        } catch (RuntimeException unavailable) {
            prefs(c).edit().putString("status","Android could not schedule a transfer. Use Sync now to retry.").apply();
        }
    }

    public static synchronized void reset(Context c) {
        prefs(c).edit().clear().commit();
        cancel(c);
        clearInterruptedExports(c);
    }

    private static void clearInterruptedExports(Context c) {
        File[] old = c.getCacheDir().listFiles((d,n) -> n.startsWith("mint-transfer-") && n.endsWith(".zip"));
        if (old != null) for (File f : old) f.delete();
    }

    private static String revision(Store.Moment m) throws Exception {
        org.json.JSONArray fields = new org.json.JSONArray();
        fields.put(m.sha256).put(m.title).put(m.transcript).put(m.kind).put(m.reason)
                .put(m.note).put(m.correction).put(m.feedback);
        return hex(MessageDigest.getInstance("SHA-256").digest(fields.toString().getBytes(StandardCharsets.UTF_8)));
    }

    static synchronized void cancel(Context c) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(JOB);
    }

    private final class Run implements Runnable {
        final JobParameters job;
        final String code;
        final long startedAtRequest;
        volatile boolean stopped;
        volatile HttpsURLConnection connection;
        Run(JobParameters job, String code) {
            this.job=job; this.code=code;
            synchronized (MintSync.class) { this.startedAtRequest=requestNumber; }
        }

        void check() throws IOException {
            if (stopped || !ListenPolicy.allowed(MintSync.this) || code.isEmpty() || !code.equals(prefs(MintSync.this).getString("pair","")))
                throw new IOException("Transfer stopped");
        }

        void status(String message) {
            if (!stopped && code.equals(prefs(MintSync.this).getString("pair","")))
                prefs(MintSync.this).edit().putString("status",message).apply();
        }

        @Override public void run() {
            boolean retry=false;
            try {
                check();
                Pair pair = new Pair(code);
                SharedPreferences p = prefs(MintSync.this);
                long first = p.getLong("after",0);
                int sent = 0, tooLarge = 0;
                try (Store store = new Store(MintSync.this)) {
                    List<Store.Moment> moments = store.search("");
                    Collections.sort(moments, Comparator.comparingLong(m -> m.id));
                    for (Store.Moment m : moments) {
                        check();
                        if (m.id<=first) continue;
                        String rev = revision(m);
                        if (rev.equals(p.getString("sentRevision:"+m.uid,""))) continue;
                        if (m.audio.length()>MAX_ZIP) { tooLarge++; continue; }
                        if (sent == 12) { retry=true; break; }
                        File archive = File.createTempFile("mint-transfer-", ".zip", getCacheDir());
                        try {
                            try (OutputStream out = new FileOutputStream(archive)) {
                                store.exportZip(out, Collections.singletonList(m.id));
                            }
                            if (archive.length()>MAX_ZIP) { tooLarge++; continue; }
                            post(pair, archive, m);
                            synchronized (MintSync.class) {
                                check();
                                boolean newClip = !p.contains("sentRevision:"+m.uid);
                                if (!p.edit().putString("sentRevision:"+m.uid,rev)
                                        .putLong("receivedCount", p.getLong("receivedCount",0)+(newClip ? 1 : 0)).commit())
                                    throw new IOException("Phone could not persist transfer receipt");
                            }
                            sent++;
                        } finally {
                            if (!archive.delete()) archive.deleteOnExit();
                        }
                    }
                }
                status(tooLarge > 0 ? "Receiver current except "+tooLarge+" clips over 16 MiB. Use file export for those."
                       : retry ? "Continuing pending clips." : "Caught up at "+java.text.DateFormat.getDateTimeInstance().format(new java.util.Date()));
            } catch (Exception e) {
                retry=true;
                status("Pending. Check Mint receiver, pairing and local Wi-Fi. "+(e instanceof Refusal ? e.getMessage() : "A transfer was interrupted."));
            } finally {
                final boolean again=retry;
                new android.os.Handler(getMainLooper()).post(() -> {
                    if (current==this) {
                        current=null;
                        if (!stopped) synchronized (MintSync.class) {
                            jobFinished(job,again || startedAtRequest!=requestNumber);
                        }
                    }
                });
            }
        }

        void post(final Pair pair, File archive, Store.Moment m) throws Exception {
            X509TrustManager pinned = new X509TrustManager() {
                @Override public X509Certificate[] getAcceptedIssuers(){ return new X509Certificate[0]; }
                @Override public void checkClientTrusted(X509Certificate[] chain,String type) throws CertificateException { throw new CertificateException("Server pin only"); }
                @Override public void checkServerTrusted(X509Certificate[] chain,String type) throws CertificateException {
                    try {
                        if (chain==null || chain.length==0 || !pair.pin.equals(hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()))))
                            throw new CertificateException("Unrecognized Mint certificate");
                        chain[0].checkValidity();
                    } catch (java.security.NoSuchAlgorithmException e) { throw new CertificateException(e); }
                }
            };
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null,new TrustManager[]{pinned},null);
            HttpsURLConnection conn=(HttpsURLConnection)new URL(pair.base+"/v1/import").openConnection(Proxy.NO_PROXY);
            connection=conn;
            try {
                check();
                conn.setSSLSocketFactory(tls.getSocketFactory());
                // Full DER pin above authenticates the receiver, including a self-signed certificate.
                conn.setHostnameVerifier((host,session) -> pair.host.equals(host));
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(9000); conn.setReadTimeout(20000);
                conn.setUseCaches(false); conn.setRequestMethod("POST"); conn.setDoOutput(true);
                conn.setRequestProperty("Authorization","Bearer "+pair.token);
                conn.setRequestProperty("Content-Type","application/zip");
                conn.setFixedLengthStreamingMode(archive.length());
                MessageDigest sha=MessageDigest.getInstance("SHA-256");
                try (InputStream in=new FileInputStream(archive); OutputStream out=conn.getOutputStream()) {
                    byte[] buf=new byte[32768];
                    for (int n; (n=in.read(buf))!=-1;) { check(); sha.update(buf,0,n); out.write(buf,0,n); }
                }
                int response=conn.getResponseCode();
                if (response!=200 && response!=201) throw new Refusal("Receiver HTTP "+response+".");
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                try (InputStream in=conn.getInputStream()) {
                    byte[] b=new byte[1024];
                    for(int n; (n=in.read(b))!=-1;) {
                        if (out.size()+n>8192) throw new Refusal("Invalid Mint receipt.");
                        out.write(b,0,n);
                    }
                }
                JSONObject receipt=new JSONObject(out.toString("UTF-8"));
                if (!receipt.optBoolean("ok") || !hex(sha.digest()).equals(receipt.optString("sha256"))
                        || !m.uid.equals(receipt.optString("id")) || !m.sha256.equals(receipt.optString("audioSha256"))) {
                    throw new Refusal("Mint receipt did not match this clip.");
                }
            } finally { conn.disconnect(); connection=null; }
        }
    }
    static final class Refusal extends IOException { Refusal(String s) { super(s); } }

    static String hex(byte[] bytes) {
        final char[] digits="0123456789abcdef".toCharArray(), result=new char[2*bytes.length];
        for(int i=0;i<bytes.length;i++) {
            result[i*2]=digits[(bytes[i]&255)>>>4]; result[i*2+1]=digits[bytes[i]&15];
        }
        return new String(result);
    }
    @Override public boolean onStartJob(JobParameters params) {
        String code=prefs(this).getString("pair","");
        if (code.isEmpty()) return false;
        clearInterruptedExports(this);
        Run run=new Run(params,code);
        current=run;
        new Thread(run,"recall-mint-transfer").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        Run run=current;
        if (run!=null && run.job.getJobId()==params.getJobId()) {
            current=null; run.stopped=true;
            HttpsURLConnection conn=run.connection;
            if(conn!=null) new Thread(conn::disconnect,"recall-mint-disconnect").start();
        }
        return !prefs(this).getString("pair","").isEmpty();
    }
}
