package com.intentintegrity.testprobe;
import android.app.*;
import android.os.*;
import android.util.*;
import android.graphics.Rect;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.*;
import org.xmlpull.v1.XmlSerializer;
import java.io.StringWriter;
/** CI-only inspection APK. NEVER bundled in the consumer application. */
public final class Snapshot extends Instrumentation {
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){Bundle result=new Bundle();try{
  UiAutomation ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);SystemClock.sleep(400);
  XmlSerializer xml=Xml.newSerializer();StringWriter out=new StringWriter();xml.setOutput(out);xml.startTag(null,"hierarchy");int count=0;
  for(AccessibilityWindowInfo w:ui.getWindows()){AccessibilityNodeInfo root=w.getRoot();if(root!=null){write(xml,root,0);count++;}}
  if(count==0){AccessibilityNodeInfo root=ui.getRootInActiveWindow();if(root!=null)write(xml,root,0);}
  xml.endTag(null,"hierarchy");xml.flush();result.putString("snapshot",Base64.encodeToString(out.toString().getBytes("UTF-8"),Base64.NO_WRAP));finish(Activity.RESULT_OK,result);
 }catch(Exception e){result.putString("error",e.toString());finish(Activity.RESULT_CANCELED,result);}}
 private void write(XmlSerializer x,AccessibilityNodeInfo n,int depth)throws Exception {if(depth>60)return;Rect r=new Rect();n.getBoundsInScreen(r);x.startTag(null,"node");x.attribute(null,"text",n.getText()==null?"":n.getText().toString());x.attribute(null,"content-desc",n.getContentDescription()==null?"":n.getContentDescription().toString());x.attribute(null,"enabled",String.valueOf(n.isEnabled()));x.attribute(null,"visible",String.valueOf(n.isVisibleToUser()));x.attribute(null,"bounds","["+r.left+","+r.top+"]["+r.right+","+r.bottom+"]");for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)write(x,c,depth+1);}x.endTag(null,"node");}
}
