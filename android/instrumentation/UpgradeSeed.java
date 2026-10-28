package org.continuity.recall;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.security.KeyStore;
import org.json.*;

/** Runs only on an explicitly selected disposable emulator; seeds the previous APK through its UI. */
public final class UpgradeSeed extends Instrumentation {
  String gate;
  @Override public void onCreate(Bundle args){super.onCreate(args);gate=args==null?"":args.getString("synthetic_emulator_only","");start();}
  static Button find(View v,String label){
    if(v instanceof Button && label.equals(((Button)v).getText().toString()))return (Button)v;
    if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Button b=find(g.getChildAt(i),label);if(b!=null)return b;}}
    return null;
  }
  void click(Activity a,String label)throws Exception{Button b=find(a.getWindow().getDecorView(),label);if(b==null)throw new Exception("Missing "+label);runOnMainSync(()->b.performClick());waitForIdleSync();}
  static void requireEmpty(File directory)throws Exception{
    if(directory==null)throw new Exception("Cannot verify fixture directory; refusing to seed");
    if(!directory.exists())return;
    File[] entries=directory.listFiles();
    if(entries==null||entries.length!=0)throw new Exception("Existing app data; refusing to seed");
  }
  @Override public void onStart(){Bundle result=new Bundle();try{
    if(!"YES".equals(gate)||!(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish")))throw new Exception("Synthetic emulator required");
    Context c=getTargetContext();
    // An emulator is not proof of disposability. Refuse existing data and keys;
    // never clear a vault, even in a synthetic test harness.
    requireEmpty(c.getNoBackupFilesDir());requireEmpty(c.getFilesDir());requireEmpty(c.getCacheDir());
    File data=new File(c.getApplicationInfo().dataDir);
    requireEmpty(new File(data,"shared_prefs"));requireEmpty(new File(data,"databases"));
    requireEmpty(c.getExternalFilesDir(null));
    KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
    if(keys.containsAlias(Vault.ALIAS))throw new Exception("Existing app key; refusing to seed");
    MainActivity a=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
    click(a,"Test");click(a,"Prepare a unique multiline test phrase");click(a,"Save capture");
    JSONObject state=Vault.read(c);String id=Core.ids(state).get(0);JSONObject e=state.getJSONObject("events").getJSONObject(id);
    JSONObject receipt=Core.obj("source_version",c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName,
        "id",id,"event_sha256",Core.digest(e),"text_sha256",e.getString("text_sha256"),"count",1);
    try(FileOutputStream f=new FileOutputStream(new File(c.getExternalFilesDir(null),"upgrade-seed.json"))){f.write(Core.utf8(receipt.toString()));f.getFD().sync();}
    result.putString("stream","\nUPGRADE_SEED_PASS\n");finish(Activity.RESULT_OK,result);
  }catch(Exception ex){result.putString("stream","\nUPGRADE_SEED_FAIL "+ex+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
