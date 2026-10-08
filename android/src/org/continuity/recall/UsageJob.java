package org.continuity.recall;

import android.app.job.*;
import android.content.*;
import org.json.*;

public class UsageJob extends JobService {
  public static final int ID = 701;

  public static void schedule(Context c) throws Exception {
    JSONObject p = Vault.read(c).getJSONObject("policy");
    JobScheduler j = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    j.cancel(ID);
    if (p.getBoolean("usage") && !p.getBoolean("paused")) {
      int result =
          j.schedule(
              new JobInfo.Builder(ID, new ComponentName(c, UsageJob.class))
                  .setPeriodic(15 * 60 * 1000L)
                  .setPersisted(true)
                  .setRequiresBatteryNotLow(true)
                  .build());
      if (result != JobScheduler.RESULT_SUCCESS)
        throw new Exception("Android refused background job; on-open catch-up still available");
    }
  }

  public boolean onStartJob(JobParameters p) {
    new Thread(
            () -> {
              try {
                UsageCollector.collect(this, "usage-periodic-catch-up");
              } catch (Exception e) {
                /* No private data in logs; next run retries committed cursor. */
              } finally {
                jobFinished(p, false);
              }
            },
            "recall-usage")
        .start();
    return true;
  }

  public boolean onStopJob(JobParameters p) {
    return true;
  }
}
