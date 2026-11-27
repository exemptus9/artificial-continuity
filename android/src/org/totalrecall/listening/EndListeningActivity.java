package org.totalrecall.listening;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.os.Bundle;

public final class EndListeningActivity extends Activity {
    private boolean ending;
    @Override public void onResume(){super.onResume();RecallOverlay.reviewVisible(this,true);}
    @Override public void onPause(){RecallOverlay.leaveEndDialog(this,ending);super.onPause();}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new AlertDialog.Builder(this)
            .setTitle("End recording and close Total Recall?")
            .setMessage("The microphone, live captions and journal capture will stop. Saved recordings and journal entries stay on this phone.")
            .setNegativeButton("Go back", (dialog, which) -> finish())
            .setPositiveButton("End & close", (dialog, which) -> endSession())
            .setOnCancelListener(dialog -> finish()).show();
    }

    private void endSession() {
        ending=true;RecallService.clearResume(this);
        if (RecallService.isListening()) RecallService.command(this, RecallService.END);
        RecallOverlay.sessionEnded(this);
        ActivityManager manager = getSystemService(ActivityManager.class);
        for (ActivityManager.AppTask task : manager.getAppTasks()) task.finishAndRemoveTask();
        finish();
    }
}
