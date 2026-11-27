package org.totalrecall.listening;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class WearableMicDialog {
    static AlertDialog show(Activity owner){
        WearableMicCheck check=WearableMicCheck.open();
        LinearLayout body=new LinearLayout(owner);body.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(16*owner.getResources().getDisplayMetrics().density);body.setPadding(pad,pad,pad,pad);
        TextView instructions=new TextView(owner);
        instructions.setText("Keep the phone still and away from the wrist. Take a quiet room reading. For each wrist, unmute only that transmitter and say “violet lantern” when measuring begins. Use your receiver’s merged or mono output for two transmitters.");
        body.addView(instructions);
        TextView reading=new TextView(owner);reading.setPadding(0,pad,0,pad);body.addView(reading);
        Button[] controls=new Button[3];String[] labels={"Measure quiet room","Measure wrist A","Measure wrist B (optional)"};
        for(int i=0;i<controls.length;i++){
            final int index=i;Button button=new Button(owner);button.setText(labels[i]);
            button.setOnClickListener(v->check.start(index));body.addView(button);controls[i]=button;
        }
        TextView note=new TextView(owner);note.setText("These are levels at Recall’s input. A receiver can mix both transmitters into one channel. Their physical identity requires testing them one at a time. This check keeps only levels while open. Your current recording settings still apply.");body.addView(note);
        ScrollView scroll=new ScrollView(owner);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("Wearable mic check").setView(scroll).setPositiveButton("Done",null).create();
        Handler main=new Handler(Looper.getMainLooper());
        Runnable tick=new Runnable(){public void run(){
            String value=check.reading();if(!reading.getText().toString().equals(value))reading.setText(value);
            for(int i=0;i<controls.length;i++)controls[i].setEnabled(check.available(i));
            main.postDelayed(this,200);
        }};
        dialog.setOnDismissListener(d->{main.removeCallbacks(tick);check.close();});
        dialog.show();tick.run();return dialog;
    }
}
