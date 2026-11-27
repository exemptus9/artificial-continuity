package org.totalrecall.listening;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.os.Bundle;

public final class EndListeningActivity extends Activity {
    private boolean ending;
    private SpokenChoices guidance;
    @Override public void onResume(){
        super.onResume();RecallOverlay.reviewVisible(this,true);
        if(guidance!=null && !isFinishing() && validSession())guidance.play(SpokenChoices.Prompt.END,false);
    }
    @Override public void onPause(){if(guidance!=null)guidance.close();RecallOverlay.leaveEndDialog(this,ending);super.onPause();}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(!validSession()){finish();return;}
        guidance=new SpokenChoices(this);
        AlertDialog.Builder choices=new AlertDialog.Builder(this)
            .setTitle("End recording and close Total Recall?")
            .setMessage("Total Recall will close and recording will stop. The microphone will turn off, and live captions and journal capture will end. Your saved recordings and journal entries stay on this phone.")
            .setNegativeButton("Go back", (dialog, which) -> finish())
            .setPositiveButton("End & close", (dialog, which) -> endSession())
            .setOnCancelListener(dialog -> finish());
        if(guidance.enabled())choices.setNeutralButton("Hear choices again",null);
        AlertDialog dialog=choices.show();
        if(guidance.enabled())dialog.getButton(-3).setOnClickListener(v->{
            if(!guidance.play(SpokenChoices.Prompt.END,true))android.widget.Toast.makeText(this,SpokenChoices.Prompt.END.words,android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private boolean validSession(){
        if(!getIntent().hasExtra("endPausedAt"))return true;
        long at=getIntent().getLongExtra("endPausedAt",0);
        return at>0 && !RecallService.isListening() && RecallService.pausedAt(this)==at;
    }
    private void endSession() {
        if(!validSession()){finish();return;}
        if(guidance!=null)guidance.close();
        ending=true;RecallService.clearResume(this);
        if (RecallService.isListening()) RecallService.command(this, RecallService.END);
        RecallOverlay.sessionEnded(this);
        ActivityManager manager = getSystemService(ActivityManager.class);
        for (ActivityManager.AppTask task : manager.getAppTasks()) task.finishAndRemoveTask();
        finish();
    }
}
