package org.continuity.recall;

import android.content.*;

public class Boot extends BroadcastReceiver {
  public void onReceive(Context c, Intent i) {
    try {
      UsageJob.schedule(c);
    } catch (Exception e) {
      /* Locked/key unavailable: user opening app retries. */
    }
  }
}
