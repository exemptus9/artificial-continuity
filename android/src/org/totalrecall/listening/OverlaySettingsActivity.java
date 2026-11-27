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

import android.widget.*;

/** The overlay permission is requested solely after an owner gesture on this page. */
public final class OverlaySettingsActivity extends Activity {
    private LinearLayout body;
    private boolean requesting;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);

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
        label("A small movable control stays over other apps during an active listening session. Drag anywhere to move it. Tap ↗ to expand or tap the status to open your full journal.",16,false);
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
                button("Writing setups",v->{
                    String[] setups={"Poet · hold for a poem line, show available audio, six caption lines",
                        "Word collector · hold for a Forge word, show saved and flagged totals, four caption lines"};
                    android.app.AlertDialog.Builder chooser=new android.app.AlertDialog.Builder(this)
                        .setTitle("Writing setups").setItems(setups,(dialog,which)->{
                            RecallOverlay.applyWritingSetup(this,which);render();
                        }).setNegativeButton("Keep current settings",null);
                    if(RecallOverlay.options(this).contains("previousFocus"))
                        chooser.setNeutralButton("Restore my settings",(dialog,which)->{
                            RecallOverlay.restoreWritingSetup(this);render();
                        });
                    chooser.show();
                });
                String[] sizes={"Ultra mini","Medium","Large"};
                int selected=RecallOverlay.options(this).getBoolean("expanded",false)?2:
                    RecallOverlay.options(this).getBoolean("ultra",false)?0:1;
                button("Overlay size · "+sizes[selected],v->new android.app.AlertDialog.Builder(this)
                    .setTitle("Overlay size").setSingleChoiceItems(sizes,selected,(dialog,which)->{
                        RecallOverlay.changeSize(this,which==0,which==2);dialog.dismiss();render();
                    }).setNegativeButton("Cancel",null).show());
                String[] focuses={"Cycle input, buffer and totals","Input level","Available audio","Saved and flagged","Current situation"};
                String[] focusValues={"auto","input","buffer","stats","scene"};
                int focus=Math.max(0,java.util.Arrays.asList(focusValues).indexOf(RecallOverlay.options(this).getString("miniFocus","auto")));
                button("Ultra mini between phrases · "+focuses[focus],v->new android.app.AlertDialog.Builder(this)
                    .setTitle("Between phrases").setSingleChoiceItems(focuses,focus,(dialog,which)->{
                        RecallOverlay.options(this).edit().putString("miniFocus",focusValues[which]).apply();dialog.dismiss();render();
                    }).setNegativeButton("Cancel",null).show());
                int hold=RecallOverlay.holdChoice(this);
                button("Ultra mini hold · "+RecallOverlay.SAVE_NAMES[hold],v->new android.app.AlertDialog.Builder(this)
                    .setTitle("Hold the readout to save").setSingleChoiceItems(RecallOverlay.SAVE_NAMES,hold,(dialog,which)->{
                        RecallOverlay.options(this).edit().putInt("holdSave",which).apply();
                        RecallOverlay.refreshOptions();dialog.dismiss();render();
                    }).setNegativeButton("Cancel",null).show());
                Switch full=new Switch(this);full.setText("Show mic route, journal, full audio and backup settings on the large panel");
                full.setChecked(RecallOverlay.options(this).getBoolean("fullDetail",true));
                full.setOnCheckedChangeListener((b,on)->RecallOverlay.options(this).edit().putBoolean("fullDetail",on).apply());body.addView(full);
                Switch caption=new Switch(this);caption.setText("Show live captions on the overlay");
                caption.setChecked(RecallOverlay.options(this).getBoolean("captions",true));
                caption.setOnCheckedChangeListener((b,on)->RecallOverlay.changeCaptions(this,on));body.addView(caption);
                String[] depths={"2 lines","4 lines","6 lines"};
                int depth=Math.max(0,Math.min(2,(RecallOverlay.options(this).getInt("captionLines",4)-2)/2));
                button("Large panel captions · "+depths[depth],v->new android.app.AlertDialog.Builder(this)
                    .setTitle("Visible caption lines").setSingleChoiceItems(depths,depth,(dialog,which)->{
                        RecallOverlay.options(this).edit().putInt("captionLines",2+2*which).apply();
                        RecallOverlay.refreshOptions();dialog.dismiss();render();
                    }).setNegativeButton("Cancel",null).show());
                label("↶ shows how many seconds you can save from before the tap. In Ultra mini, hold the readout to use your chosen save category. In Large, use Save as… for a Forge word, poem line or project decision. Use Start session to record until you finish, and Mark point to bookmark the audio while it runs.",15,false);
                label("Drag any control to move the panel. Scroll a tall panel from the body or drag its header to move it. Tap the status for the journal, More for secondary actions, or × to end recording after confirmation. The panel disappears when the phone locks. Hiding it keeps listening active.",15,false);
                button(RecallService.isListening()?"Show controls now":"Preview start control",v->{RecallOverlay.idlePreview(this);finish();});
            }
        }
        button("Lock screen controls & sound",v->startActivity(new Intent(this,CaptureReviewActivity.class).putExtra("noticeSettings",true)));
        button("Back to Total Recall",v->finish());
    }
}
