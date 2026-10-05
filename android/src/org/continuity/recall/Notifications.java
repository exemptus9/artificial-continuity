package org.continuity.recall;

import android.app.Notification;
import android.service.notification.*;
import java.util.*;
import org.json.*;

public class Notifications extends NotificationListenerService {
  final Set<String> active = new HashSet<>();

  @Override
  public void onListenerConnected() {
    try {
      Vault.change(
          this,
          s -> {
            s.getJSONObject("policy")
                .put("notification_status", "Listener connected; allowlist enforced");
          });
      StatusBarNotification[] list = getActiveNotifications();
      if (list != null) for (StatusBarNotification n : list) capture(n, "discovered-active", 0);
    } catch (Exception e) {
    }
  }

  @Override
  public void onListenerDisconnected() {
    try {
      Vault.change(
          this,
          s -> {
            JSONObject p = s.getJSONObject("policy");
            p.put("notification_status", "Listener disconnected — coverage gap");
            Vault.gap(
                p,
                "Notification listener disconnected",
                System.currentTimeMillis(),
                System.currentTimeMillis());
          });
    } catch (Exception e) {
    }
  }

  @Override
  public void onNotificationPosted(StatusBarNotification n) {
    capture(n, active.contains(n.getKey()) ? "updated" : "posted-or-updated", 0);
  }

  @Override
  public void onNotificationRemoved(StatusBarNotification n, RankingMap r, int reason) {
    capture(n, "removed", reason);
    active.remove(n.getKey());
  }

  void capture(StatusBarNotification n, String transition, int reason) {
    try {
      long observed = System.currentTimeMillis();
      Vault.change(
          this,
          s -> {
            JSONObject p = s.getJSONObject("policy");
            String pkg = n.getPackageName();
            if (p.getBoolean("paused")
                || !p.getBoolean("notifications")
                || !p.getJSONObject("notification_allow").has(pkg)) return;
            long post =
                n
                    .getPostTime(); // Original notification payload, extras, tag and channel are
                                    // never retained.
            if (post < p.optLong("notification_since", 0)
                || post < p.getJSONObject("notification_allow").getLong(pkg)
                || Core.inRanges(p.getJSONArray("exclusions"), post)) return;
            String keyHash = Core.hash(Core.utf8(s.getString("device_id") + ":" + n.getKey()));
            long eventTime =
                transition.equals("discovered-active")
                    ? observed
                    : transition.equals("removed") ? observed : post;
            JSONObject event =
                Core.event(
                    s,
                    "auto.notification",
                    "notification-listener",
                    "",
                    eventTime,
                    observed,
                    pkg,
                    null,
                    new JSONArray(),
                    Core.obj(
                        "notification_identity_sha256",
                        keyHash,
                        "transition",
                        transition,
                        "grouped",
                        n.isGroup(),
                        "group_summary",
                        (n.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0,
                        "post_time_ms",
                        post,
                        "removed_reason",
                        reason,
                        "content_status",
                        "not-requested-metadata-only"));
            if (Core.allowed(s, event)) {
              Core.add(s, event, new JSONObject());
              p.put("notification_observed", observed);
              p.put(
                  "notification_status", "Live callbacks while listener connected; metadata only");
              active.add(n.getKey());
            }
          });
    } catch (Exception e) {
      /* Failed save has no receipt. See capacity and source status in dashboard. */
    }
  }
}
