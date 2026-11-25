package org.totalrecall.listening;

import android.util.Base64;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** A LAN certificate fingerprint or a platform-validated Railway HTTPS origin. */
final class ArchiveDestination {
    static final String WEBSITE="https://total-recall-archive-production.up.railway.app";
    final String base,host,token,identity;
    final boolean cloud;
    private final MintSync.Pair lan;
    ArchiveDestination(String code)throws Exception{
        if(!code.startsWith("TR1.")||code.length()>3000)throw new IOException("Invalid pairing code");
        JSONObject o=new JSONObject(new String(Base64.decode(code.substring(4),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING),StandardCharsets.UTF_8));
        if(o.has("tls")){
            if(!"public-ca".equals(o.getString("tls"))||o.has("pin"))throw new IOException("Unsupported TLS mode");
            base=o.getString("url");URI uri=new URI(base);host=uri.getHost();token=o.getString("token");
            if(!WEBSITE.equals(base)||!token.matches("[0-9a-fA-F]{64}"))
                throw new IOException("Expected your secure Railway archive address and pairing token");
            cloud=true;lan=null;identity="railway:"+host;
        }else{
            lan=new MintSync.Pair(code);base=lan.base;host=lan.host;token=lan.token;cloud=false;identity="mint:"+lan.pin;
        }
    }
    HttpsURLConnection open(String path)throws Exception{
        if(!path.matches("/v2/(status|uploads(?:/[A-Za-z0-9_-]{1,128})?)"))throw new IOException("Unsupported archive route");
        if(!cloud)return MintSync.open(lan,path);
        // Keep the platform's default certificate trust and hostname verifier for this public DNS origin.
        HttpsURLConnection conn=(HttpsURLConnection)new URL(base+path).openConnection(Proxy.NO_PROXY);
        conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(9000);conn.setReadTimeout(30000);conn.setUseCaches(false);
        conn.setRequestProperty("Authorization","Bearer "+token);return conn;
    }
}
