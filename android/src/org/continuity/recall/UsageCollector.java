package org.continuity.recall;

import android.app.*;
import android.app.usage.*;
import android.content.*;
import android.os.*;
import java.util.*;
import org.json.*;

public final class UsageCollector {
  public static boolean permission(Context c) {
    AppOpsManager a = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
    return a.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.getPackageName())
        == AppOpsManager.MODE_ALLOWED;
  }

  public static void collect(Context c, String method) throws Exception {
    Vault.change(
        c,
        s -> {
          JSONObject p = s.getJSONObject("policy");
          long now = System.currentTimeMillis(), cursor = p.getLong("usage_cursor");
          if (p.getBoolean("paused") || !p.getBoolean("usage")) return;
          if (!permission(c)) {
            p.put("usage_status", "Permission unavailable — enable Usage Access");
            Vault.gap(p, "Usage permission unavailable", cursor, now);
            p.put("usage_cursor", now);
            return;
          }
          if (now < cursor) {
            Vault.gap(p, "Clock moved backward; cursor reset", now, cursor);
            p.put("usage_cursor", now);
            return;
          }
          long start = Math.max(cursor, now - 86400000L);
          if (start > cursor)
            Vault.gap(
                p, "Catch-up limited to 24 hours; older history not collected", cursor, start);
          UsageEvents es =
              ((UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE))
                  .queryEvents(start, now);
          if (es == null) {
            p.put("usage_status", "OS history unavailable (possibly locked)");
            Vault.gap(p, "Usage API unavailable", start, now);
            p.put("usage_cursor", now);
            return;
          }
          UsageEvents.Event e = new UsageEvents.Event();
          int count = 0, scanned = 0;
          boolean capped = false;
          long next = now;
          while (es.hasNextEvent()) {
            es.getNextEvent(e);
            scanned++;
            if (scanned > 20000) {
              capped = true;
              next = Math.max(start, e.getTimeStamp());
              break;
            }
            int type = e.getEventType();
            if (type != 1 && type != 2 && type != 23 && type != 24) continue;
            String pkg = e.getPackageName();
            if (pkg == null || !p.getJSONObject("usage_allow").has(pkg)) continue;
            int instance = 0; // Public SDK does not expose an activity instance identity.
            String identity =
                "usage:"
                    + pkg
                    + ":"
                    + type
                    + ":"
                    + e.getTimeStamp()
                    + ":"
                    + String.valueOf(e.getClassName());
            JSONObject event =
                Core.event(
                    s,
                    "auto.usage",
                    method,
                    "",
                    e.getTimeStamp(),
                    now,
                    pkg,
                    identity,
                    new JSONArray(),
                    Core.obj(
                        "transition",
                        type == 1
                            ? "resumed"
                            : type == 2 ? "paused" : type == 23 ? "stopped" : "destroyed",
                        "event_type",
                        type,
                        "instance",
                        instance,
                        "source_identity_sha256",
                        Core.hash(Core.utf8(identity))));
            // A retry re-observes a known OS identity at a different observation time: retain first
            // observation.
            if (s.getJSONObject("events").has(event.getString("id")) || !Core.allowed(s, event))
              continue;
            if (s.getJSONObject("events").length() >= Core.MAX_EVENTS) {
              capped = true;
              next = e.getTimeStamp();
              break;
            }
            Core.add(s, event, new JSONObject());
            count++;
          }
          p.put("usage_cursor", next);
          p.put("usage_observed", now);
          p.put(
              "usage_status",
              capped
                  ? "Backlog: capacity/query limit; export or retry"
                  : "Catch-up observed; last batch " + count + " events");
          if (capped) Vault.gap(p, "Partial batch; retained cursor for retry", next, now);
          long cutoff = now - p.getInt("retention_days") * 86400000L;
          for (String id : Core.ids(s)) {
            JSONObject old = s.getJSONObject("events").getJSONObject(id);
            if (old.getString("source_type").startsWith("auto.")
                && old.getLong("event_ms") < cutoff) Core.erase(s, id);
          }
          Core.gc(s);
        });
  }
}
