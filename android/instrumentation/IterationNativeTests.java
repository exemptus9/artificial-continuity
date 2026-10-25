package org.continuity.recall;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import android.graphics.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Native UI and upgrade acceptance on a disposable emulator. Never runs on an owner's phone. */
public final class IterationNativeTests extends Instrumentation {
  String gate,phase;int count;JSONArray cases=new JSONArray();
  @Override public void onCreate(Bundle args){super.onCreate(args);gate=args==null?"":args.getString("synthetic_emulator_only","");phase=args==null?"":args.getString("phase","");start();}
  void yes(String name,boolean ok)throws Exception{cases.put(Core.obj("test",name,"status",ok?"PASS":"FAIL"));if(!ok)throw new Exception(name);count++;}
  static View view(View root,String text){
    if((root instanceof TextView && ((TextView)root).getText().toString().contains(text))||text.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
    if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){View v=view(g.getChildAt(i),text);if(v!=null)return v;}}
    return null;
  }
  void click(Activity a,String label)throws Exception{Button b=UpgradeSeed.find(a.getWindow().getDecorView(),label);if(b==null)throw new Exception("Missing button: "+label);runOnMainSync(()->b.performClick());waitForIdleSync();}
  void await(Activity a,String text)throws Exception{for(int n=0;n<160;n++){final boolean[] found={false};runOnMainSync(()->found[0]=view(a.getWindow().getDecorView(),text)!=null);if(found[0])return;Thread.sleep(50);}throw new Exception("UI timeout: "+text);}
  AccessibilityNodeInfo node(AccessibilityNodeInfo root,String label){
    if(root==null)return null;if(label.contentEquals(root.getText()==null?"":root.getText())||label.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
    for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo found=node(root.getChild(i),label);if(found!=null)return found;}return null;
  }
  AccessibilityNodeInfo awaitNode(String label)throws Exception{for(int n=0;n<100;n++){AccessibilityNodeInfo found=node(getUiAutomation().getRootInActiveWindow(),label);if(found!=null)return found;Thread.sleep(50);}throw new Exception("Visible node missing: "+label);}
  void uiClick(String label)throws Exception{if(!awaitNode(label).performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new Exception("Click failed: "+label);waitForIdleSync();}
  void uiText(String label,String value)throws Exception{Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);if(!awaitNode(label).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b))throw new Exception("Text failed: "+label);waitForIdleSync();}
  void render(Activity a,String name)throws Exception{
    File out=new File(getTargetContext().getExternalFilesDir(null),name+".png");
    runOnMainSync(()->{try{View v=a.getWindow().getDecorView();Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));try(FileOutputStream f=new FileOutputStream(out)){b.compress(Bitmap.CompressFormat.PNG,100,f);}b.recycle();}catch(Exception e){throw new RuntimeException(e);}});
  }
  @Override public void onStart(){Bundle result=new Bundle();try{
    if(!"YES".equals(gate)||!(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish")))throw new Exception("Synthetic emulator required");
    Context c=getTargetContext();JSONObject seed=new JSONObject(Core.decodeUtf8(java.nio.file.Files.readAllBytes(new File(c.getExternalFilesDir(null),"upgrade-seed.json").toPath())));
    JSONObject initial=Vault.read(c),original=initial.getJSONObject("events").getJSONObject(seed.getString("id"));
    yes("previous APK source retained after update",Core.digest(original).equals(seed.getString("event_sha256")));
    yes("old encrypted store opens with same key",original.getString("text_sha256").equals(seed.getString("text_sha256")));
    yes("release version is 0.8.0",BuildInfo.VERSION.equals("0.8.0-test"));
    if(phase.equals("restart")){
      yes("source-linked next step survives process restart",MemoryWorkbench.desk(initial).length()>0);
      yes("offline recall works after process restart",TemporalEngine.recall(initial,"find lighthouse",System.currentTimeMillis(),java.time.ZoneId.systemDefault()).matchCount>0);
    }else{
      yes("in-place update retained original record count",Core.ids(initial).size()==seed.getInt("count"));
      yes("automatic sources stay off",!initial.getJSONObject("policy").getBoolean("usage")&&!initial.getJSONObject("policy").getBoolean("notifications"));
      MainActivity a=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
      click(a,"Test");click(a,"Verify durable test record");
      yes("verify success is immediately visible",awaitNode("Saved test record verified")!=null);uiClick("OK");
      click(a,"Capture");String note="SYNTHETIC lighthouse poem\n\nKeep both lines. e\u0301 / é / 🧠";
      runOnMainSync(()->a.editor.setText(note));click(a,"Save capture");
      final boolean[] receiptVisible={false};runOnMainSync(()->{View v=view(a.getWindow().getDecorView(),"Saved on this phone");receiptVisible[0]=v!=null&&v.getGlobalVisibleRect(new Rect());});
      yes("save receipt is in the visible viewport",receiptVisible[0]);render(a,"capture-receipt");
      ActivityMonitor monitor=addMonitor(MemoryActivity.class.getName(),null,false);click(a,"Open saved capture");
      MemoryActivity memory=(MemoryActivity)waitForMonitorWithTimeout(monitor,8000);removeMonitor(monitor);
      if(memory==null)throw new Exception("Memory screen did not open");await(memory,note);
      yes("saved capture opens exact original",view(memory.getWindow().getDecorView(),note)!=null);
      click(memory,"Add interpretation or next step");uiText("Review text","Continue the lighthouse stanza");uiText("Existing project ID","P29");uiClick("Review kind");uiClick("next step");uiClick("Save review");
      await(memory,"OWNER REVIEW · next step");
      yes("owner review saved through dialog",MemoryWorkbench.desk(Vault.read(c)).getJSONObject(0).getString("next_step_id").length()>0);
      click(memory,"Threads");await(memory,"Continue the lighthouse stanza");render(memory,"threads");
      yes("thread screen shows source-backed next step",view(memory.getWindow().getDecorView(),"Continue the lighthouse stanza")!=null);
      click(memory,"Ask");await(memory,"Search saved notes");
      int before=Core.ids(Vault.read(c)).size();
      runOnMainSync(()->((EditText)view(memory.getWindow().getDecorView(),"Search question for saved notes")).setText("find lighthouse today"));
      click(memory,"Search saved notes");await(memory,"FROM YOUR SAVED NOTES");render(memory,"ask-sources");
      yes("Ask returns real text evidence",view(memory.getWindow().getDecorView(),"lighthouse poem")!=null);
      yes("Ask never stores the question",Core.ids(Vault.read(c)).size()==before);
      click(memory,"Find");await(memory,"All captures");click(memory,"Inbox");
      yes("Inbox does not treat reviewed original as unreviewed",MemoryWorkbench.filteredRecords(Vault.read(c),"lighthouse",0,Long.MAX_VALUE,"","Inbox").isEmpty());
      JSONObject disk=Vault.read(c);JSONObject archive=Core.exportArchive(disk,Core.ids(disk),System.currentTimeMillis());
      JSONObject isolated=Core.restore(Core.initial(System.currentTimeMillis()),archive);
      File target=new File(c.getNoBackupFilesDir(),"iteration-isolated.v1");
      try(FileOutputStream f=new FileOutputStream(target)){f.write(Crypto.encrypt(Vault.key(),Core.utf8(isolated.toString())));f.getFD().sync();}
      JSONObject reloaded=new JSONObject(Core.decodeUtf8(Crypto.decrypt(Vault.key(),java.nio.file.Files.readAllBytes(target.toPath()))));
      yes("independent encrypted restore retains originals",Core.digest(reloaded.getJSONObject("events")).equals(Core.digest(disk.getJSONObject("events"))));
      yes("independent restore retains thread relationships",MemoryWorkbench.desk(reloaded).length()==MemoryWorkbench.desk(disk).length());
      byte[] sealed=Core.utf8(Crypto.seal(Core.utf8(archive.toString()),"SYNTHETIC iteration backup".toCharArray()).toString());
      try(FileOutputStream f=new FileOutputStream(new File(c.getExternalFilesDir(null),"iteration-native.trbackup.json"))){f.write(sealed);}
      yes("protected native export preserves content hashes",Core.digest(StrictJson.parse(Core.decodeUtf8(Crypto.open(new JSONObject(Core.decodeUtf8(sealed)),"SYNTHETIC iteration backup".toCharArray())))).equals(Core.digest(archive)));
      runOnMainSync(()->memory.finish());waitForIdleSync();
    }
    JSONObject receipt=Core.obj("format","TotalRecallIterationNative/1","status","PASS","phase",phase,"commit",BuildInfo.COMMIT,"version",BuildInfo.VERSION,"assertions",count,"cases",cases,"physical_phone",false,"signing","CI disposable key; owner-signed delivery verified separately");
    try(FileOutputStream f=new FileOutputStream(new File(c.getExternalFilesDir(null),"iteration-"+(phase.equals("restart")?"restart":"ui")+".json"))){f.write(Core.utf8(receipt.toString(2)));}
    result.putString("stream","\nITERATION_PASS "+count+"\n");finish(Activity.RESULT_OK,result);
  }catch(Exception ex){result.putString("stream","\nITERATION_FAIL "+ex+"\n");ex.printStackTrace();finish(Activity.RESULT_CANCELED,result);}}
}
