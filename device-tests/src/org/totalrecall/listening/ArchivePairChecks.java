package org.totalrecall.listening;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

final class ArchivePairChecks {
    private static final String TOKEN="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static String code(String origin,boolean cloud)throws Exception {
        JSONObject o=new JSONObject().put("url",origin).put("token",TOKEN);
        if(cloud)o.put("tls","public-ca");else o.put("pin",TOKEN);
        return "TR1."+Base64.encodeToString(o.toString().getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
    }
    private static void rejects(String origin,boolean cloud)throws Exception {
        try{new ArchiveDestination(code(origin,cloud));}catch(Exception denied){return;}
        throw new AssertionError("Accepted an unexpected archive origin: "+origin);
    }
    static void verify()throws Exception {
        ArchiveDestination remote=new ArchiveDestination(code(ArchiveDestination.WEBSITE,true));
        HttpsURLConnection conn=remote.open("/v2/status");
        try{
            if(!remote.cloud||conn.getInstanceFollowRedirects()||!conn.getURL().getHost().equals(remote.host)
                ||conn.getHostnameVerifier()!=HttpsURLConnection.getDefaultHostnameVerifier()
                ||conn.getSSLSocketFactory()!=HttpsURLConnection.getDefaultSSLSocketFactory())
                throw new AssertionError("Cloud HTTPS is not using platform validation");
        }finally{conn.disconnect();}
        for(String origin:new String[]{"http://total-recall-archive-production.up.railway.app",
            ArchiveDestination.WEBSITE+"/",ArchiveDestination.WEBSITE+"?authorization=test",ArchiveDestination.WEBSITE+":8443",
            ArchiveDestination.WEBSITE+".example.test","https://other.up.railway.app","https://user@total-recall-archive-production.up.railway.app",
            "https://10.0.0.1:8443"}) rejects(origin,true);
        for(String origin:new String[]{"https://100.63.255.255:8443","https://100.128.0.0:8443","https://8.8.8.8:8443",
            "https://127.0.0.1:8443","https://192.168.1.1:8443/a"})rejects(origin,false);
        for(String origin:new String[]{"https://100.64.0.0:8443","https://100.127.255.255:8443","https://192.168.1.1:8443"})
            if(new ArchiveDestination(code(origin,false)).cloud)throw new AssertionError("LAN transport unexpectedly used public CA");
    }
}
