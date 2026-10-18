package com.intentintegrity.app;
import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.*;
import android.widget.*;
import java.util.*;
/** Metadata-only, event-driven entry guard. No node trees, screen text or idle polling. */
public final class IntentGuardService extends AccessibilityService {
    private static java.lang.ref.WeakReference<IntentGuardService> current=new java.lang.ref.WeakReference<>(null);
    private WindowManager wm;private ScrollView overlay;private String foreground="",overlayPackage="";
    private final Set<String> keyboards=new HashSet<>();private final Handler handler=new Handler(Looper.getMainLooper());private final Lease pass=new Lease();private boolean screenOff;
    private final Runnable expiry=()->sync();
    private final SharedPreferences.OnSharedPreferenceChangeListener changes=(p,k)->{if("guard_consent".equals(k)||"guard_paused".equals(k)||"guard_apps".equals(k)){pass.clear();sync();}};
    private final BroadcastReceiver screen=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(Intent.ACTION_SCREEN_OFF.equals(i.getAction())){screenOff=true;foreground="";pass.clear();handler.removeCallbacks(expiry);hide();}else screenOff=false;}};
    public static void disableCurrent(){IntentGuardService s=current.get();if(s!=null){s.hide();s.disableSelf();}}
    @Override protected void onServiceConnected(){current=new java.lang.ref.WeakReference<>(this);wm=(WindowManager)getSystemService(WINDOW_SERVICE);InputMethodManager im=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);for(InputMethodInfo k:im.getEnabledInputMethodList())keyboards.add(k.getPackageName());LocalData.prefs(this).registerOnSharedPreferenceChangeListener(changes);IntentFilter f=new IntentFilter();f.addAction(Intent.ACTION_SCREEN_OFF);f.addAction(Intent.ACTION_SCREEN_ON);if(Build.VERSION.SDK_INT>=33)registerReceiver(screen,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screen,f);}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){if(event.getEventType()!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED||event.getPackageName()==null)return;String pkg=event.getPackageName().toString();if(keyboards.contains(pkg))return;if(pkg.equals(getPackageName())){String cls=String.valueOf(event.getClassName());if(cls.endsWith("MainActivity")||cls.endsWith("BrowserActivity")||cls.endsWith("GuardSettingsActivity")){foreground="";hide();}return;}foreground=pkg;sync();}
    private String[] selected(){if(screenOff||!LocalData.prefs(this).getBoolean("guard_consent",false)||LocalData.prefs(this).getBoolean("guard_paused",false))return null;Set<String>chosen=LocalData.prefs(this).getStringSet("guard_apps",Collections.emptySet());if(!chosen.contains(foreground))return null;for(String[]a:GuardSettingsActivity.apps())if(a[0].equals(foreground))return a;return null;}
    private void sync(){handler.removeCallbacks(expiry);String[]a=selected();if(a==null){hide();return;}if(pass.valid(foreground,SystemClock.elapsedRealtime())){hide();handler.postDelayed(expiry,500);return;}if(overlay!=null&&!overlayPackage.equals(a[0]))hide();if(overlay==null)show(a);}
    private void show(String[]app){if(wm==null)return;LinearLayout content=Ui.column(this);content.setGravity(Gravity.CENTER_VERTICAL);content.setPadding(Ui.dp(this,24),Ui.dp(this,30),Ui.dp(this,24),Ui.dp(this,30));content.addView(Ui.text(this,"INTENT INTEGRITY / OPTIONAL APP GUARD",11,Ui.ACCENT));Ui.heading(content,"You opened "+app[1]+".","What did you come to find?");EditText q=Ui.input(content,app[2].equals("instagram")?"Instagram username (@name)":"Search phrase",500);q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        Runnable search=()->{String text=q.getText().toString().trim();try{Routes.search(app[2],text);}catch(Exception e){q.setError(e.getMessage());return;}Intent i=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("site",app[2]).putExtra("query",text).putExtra("search_now",true);foreground="";hide();startActivity(i);};
        q.setOnEditorActionListener((v,id,e)->{if(id==EditorInfo.IME_ACTION_SEARCH){search.run();return true;}return false;});Ui.button(content,"Search in protected browser",true,search);
        Ui.button(content,"Use native app · allow ALL content for 1 minute",false,()->{pass.grant(app[0],SystemClock.elapsedRealtime(),60000);hide();sync();});
        Ui.button(content,"Go Home",false,()->{foreground="";hide();performGlobalAction(GLOBAL_ACTION_HOME);});
        Ui.button(content,"Pause protection & open settings",false,()->{LocalData.prefs(this).edit().putBoolean("guard_paused",true).apply();foreground="";hide();startActivity(new Intent(this,GuardSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));});
        content.addView(Ui.text(this,"This covers the app, not its audio. A native pass permits the feed too. Android may briefly show the app before this shield appears.",13,Ui.MUTED));
        overlay=new ScrollView(this);overlay.setFillViewport(true);overlay.setBackgroundColor(Ui.BG);overlay.addView(content);overlayPackage=app[0];WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.OPAQUE);p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;p.setTitle("Intent Integrity app shield");try{wm.addView(overlay,p);}catch(RuntimeException e){overlay=null;overlayPackage="";foreground="";}
    }
    private void hide(){if(overlay!=null&&wm!=null){try{wm.removeView(overlay);}catch(IllegalArgumentException ignored){}overlay=null;overlayPackage="";}}
    @Override public void onInterrupt(){foreground="";hide();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);LocalData.prefs(this).unregisterOnSharedPreferenceChangeListener(changes);try{unregisterReceiver(screen);}catch(IllegalArgumentException ignored){}hide();current.clear();super.onDestroy();}
}
