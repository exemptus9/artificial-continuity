package org.totalrecall.listening;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;

/** The overlay permission is requested solely after an owner gesture on this page. */
public final class OverlaySettingsActivity extends Activity {
    private LinearLayout body;
    private boolean requesting;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        requesting=saved!=null && saved.getBoolean("requested");
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putBoolean("requested",requesting);}
    @Override protected void onResume() {
        super.onResume();
        boolean granted=Settings.canDrawOverlays(this);
        if(requesting && granted) RecallOverlay.choice(this,true);
        requesting=false;
        if(!granted && RecallOverlay.enabled(this))RecallOverlay.choice(this,false);
        render();
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private TextView label(String words,int size,boolean bold){
        TextView v=new TextView(this); v.setText(words);v.setTextSize(size);v.setTextColor(Color.rgb(19,47,49));
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        v.setPadding(0,dp(8),0,dp(8));body.addView(v);return v;
    }
    private void button(String words, View.OnClickListener click){
        Button b=new Button(this);b.setText(words);b.setAllCaps(false);b.setFilterTouchesWhenObscured(true);
        b.setOnClickListener(click);body.addView(b);
    }
    private void render(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(244,248,246));
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20),dp(26),dp(20),dp(24));scroll.addView(body);setContentView(scroll);
        label("Floating controls",28,true);
        label("A small movable control stays over other apps during an active listening session. Drag Recall to move it. Tap Recall to expand or collapse.",16,false);
        boolean grant=Settings.canDrawOverlays(this), enabled=RecallOverlay.enabled(this);
        label("Overlay "+(enabled&&grant?"enabled":"off"),17,true);
        if(!grant){
            label("Android will open its Display over other apps list. Choose Total Recall, enable the permission, then return here.",15,false);
            button("Allow floating controls",v->{
                requesting=true;
                try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}
                catch(ActivityNotFoundException e){
                    try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));}
                    catch(ActivityNotFoundException missing){requesting=false;Toast.makeText(this,"Open Android Settings → Special app access → Display over other apps.",Toast.LENGTH_LONG).show();}
                }
            });
        }else{
            Switch enable=new Switch(this);enable.setText("Show controls during listening");enable.setChecked(enabled);enable.setFilterTouchesWhenObscured(true);
            body.addView(enable);
            enable.setOnCheckedChangeListener((b,on)->{RecallOverlay.choice(this,on);render();});
            if(enabled){
                Switch caption=new Switch(this);caption.setText("Show live words in expanded overlay");
                caption.setChecked(RecallOverlay.options(this).getBoolean("captions",false));
                caption.setOnCheckedChangeListener((b,on)->RecallOverlay.changeCaptions(this,on));body.addView(caption);
                label("Anyone looking at the unlocked screen may see enabled captions. Controls disappear when the phone locks. Hiding the overlay keeps the listening session running.",15,false);
                button(RecallService.isListening()?"Show controls now":"Preview start control",v->{RecallOverlay.idlePreview(this);finish();});
            }
        }
        button("Back to Total Recall",v->finish());
    }
}
