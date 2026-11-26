package org.continuity.recall;

import android.content.Context;
import org.totalrecall.listening.RecallService;

/** The listener and archive activity share the original app-wide pause setting. */
public final class ListenPolicy {
  private static volatile Boolean paused;
  private ListenPolicy() {}
  static void update(boolean value) { paused = value; }
  static void invalidate() { paused = null; }
  public static boolean allowed(Context c) {
    Boolean value = paused;
    if (value != null) return !value;
    synchronized (Vault.class) {
      try {
        update(Vault.read(c).getJSONObject("policy").getBoolean("paused"));
        return !paused;
      } catch (Exception error) { return false; }
    }
  }
  static void pauseService(Context c) {
    RecallService.clearResume(c);
    if (RecallService.isListening()) RecallService.command(c, RecallService.PAUSE);
  }
}
