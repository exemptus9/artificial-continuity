package com.intentintegrity.app;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;

/** No network, no automatic search history. Only explicitly saved links. */
public final class LocalData {
    private LocalData() {}
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("intent_integrity",Context.MODE_PRIVATE); }
    public static JSONArray later(Context c) { try{return new JSONArray(prefs(c).getString("later","[]"));}catch(JSONException e){return new JSONArray();} }
    public static synchronized void save(Context c,String url,String title) throws JSONException {
        if(Routes.site(url).isEmpty() || url.length()>4096) throw new JSONException("Only supported HTTPS links can be saved.");
        JSONArray out=new JSONArray(); JSONObject item=new JSONObject(); item.put("url",url); item.put("title",title==null?url:title.substring(0,Math.min(160,title.length()))); item.put("savedAt",System.currentTimeMillis()); out.put(item);
        JSONArray old=later(c); for(int i=0;i<old.length() && out.length()<100;i++){JSONObject x=old.getJSONObject(i);if(!url.equals(x.optString("url")))out.put(x);}
        if(!prefs(c).edit().putString("later",out.toString()).commit())throw new JSONException("Storage write failed.");
    }
    public static synchronized void remove(Context c,int n) { JSONArray a=later(c);a.remove(n);prefs(c).edit().putString("later",a.toString()).commit(); }
    public static String export(Context c) throws JSONException { return new JSONObject().put("format","intent-integrity-later").put("version",1).put("items",later(c)).toString(2); }
    public static synchronized int restore(Context c,String text) throws JSONException {
        if(text.length()>524288) throw new JSONException("Backup is too large.");
        JSONObject root=new JSONObject(text);
        if(!"intent-integrity-later".equals(root.optString("format")) || root.optInt("version")!=1)throw new JSONException("Not a supported backup.");
        JSONArray items=root.getJSONArray("items");if(items.length()>100)throw new JSONException("Maximum 100 saved links.");
        JSONArray out=new JSONArray(); java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(int i=0;i<items.length();i++){JSONObject x=items.getJSONObject(i);String u=x.getString("url");String title=x.optString("title",u); if(u.length()>4096 || Routes.site(u).isEmpty() || title.length()>160)throw new JSONException("Invalid saved link at item "+(i+1)); if(seen.add(u))out.put(new JSONObject().put("url",u).put("title",title).put("savedAt",Math.max(0,x.optLong("savedAt",0))));}
        if(!prefs(c).edit().putString("later",out.toString()).commit())throw new JSONException("Storage write failed.");return out.length();
    }
}
