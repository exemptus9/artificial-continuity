package com.intentintegrity.app;
import android.app.*;
import android.os.Bundle;
import android.provider.Settings;
import android.content.*;
import android.widget.*;
import java.util.*;

public final class GuardSettingsActivity extends Activity {
    public static String[][] apps(){java.util.ArrayList<String[]>a=new java.util.ArrayList<>();a.add(new String[]{"com.google.android.youtube","YouTube","youtube"});a.add(new String[]{"com.instagram.android","Instagram","instagram"});a.add(new String[]{"com.instagram.barcelona","Threads","threads"});a.add(new String[]{"com.facebook.katana","Facebook","facebook"});a.add(new String[]{"com.zhiliaoapp.musically","TikTok","tiktok"});a.add(new String[]{"com.ss.android.ugc.trill","TikTok (regional)","tiktok"});a.add(new String[]{"com.reddit.frontpage","Reddit","reddit"});a.add(new String[]{"com.twitter.android","X","x"});if(BuildConfig.DEBUG)a.add(new String[]{"com.intentintegrity.fixture","Test feed app (debug only)","youtube"});return a.toArray(new String[0][]);}
    @Override public void onCreate(Bundle s){super.onCreate(s);render();}
    @Override protected void onResume(){super.onResume();render();}
    private void render(){ScrollView sc=new ScrollView(this);LinearLayout l=Ui.column(this);sc.addView(l);setContentView(sc);Ui.inset(this,sc);Ui.heading(l,"A door before the feed.","Choose exactly which installed apps get an entry shield. No apps are selected by default.");
        l.addView(Ui.text(this,"This experimental mode covers the whole selected app, not just its feed. Search opens this app's protected browser. A timed native-app pass deliberately permits ALL content, including feeds and messages. It cannot guarantee zero first-frame exposure or stop another app's audio.",15,Ui.MUTED));
        Set<String> selected=new HashSet<>(LocalData.prefs(this).getStringSet("guard_apps",Collections.emptySet()));
        for(String[] app:apps()){CheckBox c=new CheckBox(this);c.setText(app[1]);c.setTextColor(Ui.TEXT);c.setMinHeight(Ui.dp(this,50));c.setChecked(selected.contains(app[0]));l.addView(c);c.setOnCheckedChangeListener((b,on)->{Set<String>x=new HashSet<>(LocalData.prefs(this).getStringSet("guard_apps",Collections.emptySet()));if(on)x.add(app[0]);else x.remove(app[0]);LocalData.prefs(this).edit().putStringSet("guard_apps",x).apply();});}
        String enabled=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);String component=new ComponentName(this,IntentGuardService.class).flattenToString();boolean connected=enabled!=null && Arrays.asList(enabled.split(":")).contains(component);
        l.addView(Ui.text(this,"Android service: "+(connected?"enabled":"not enabled")+" · in-app consent: "+(LocalData.prefs(this).getBoolean("guard_consent",false)?"granted":"not granted"),14,Ui.ACCENT));
        Ui.button(l,"Review disclosure & enable",true,this::consent);
        boolean paused=LocalData.prefs(this).getBoolean("guard_paused",false);Ui.button(l,paused?"Resume chosen-app protection":"Pause all app protection",false,()->{LocalData.prefs(this).edit().putBoolean("guard_paused",!paused).apply();render();});
        Ui.button(l,"Revoke consent & disable service",false,()->{LocalData.prefs(this).edit().putBoolean("guard_consent",false).putBoolean("guard_paused",true).apply();IntentGuardService.disableCurrent();render();});
        Ui.button(l,"Back to search",false,this::finish);
    }
    private void consent(){new AlertDialog.Builder(this).setTitle("Accessibility access — your choice").setMessage("Intent Integrity uses Android's AccessibilityService API to receive the package name and window-change metadata of the foreground app, then show a shield over apps YOU select.\n\nIt does not request screen contents, read messages or passwords, take screenshots, perform gestures, or upload or retain an app-activity history. The shield includes buttons you may deliberately use to search, go Home, or allow the native app temporarily.\n\nThis is an optional digital-wellbeing feature, not a declared disability accessibility tool. It can react only after Android sends an event; an initial flash and background audio are possible. Other apps and system settings remain accessible.\n\nAgree only to continue to Android's own permission screen. You may decline and keep using search-first browsing.").setPositiveButton("I agree · open settings",(d,w)->{LocalData.prefs(this).edit().putBoolean("guard_consent",true).putBoolean("guard_paused",false).commit();startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}).setNegativeButton("Not now",null).show();}
}
