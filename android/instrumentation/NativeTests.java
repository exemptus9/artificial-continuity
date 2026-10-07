package org.continuity.recall;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Emulator-only synthetic tests, not included in the release APK. */
public class NativeTests extends Instrumentation {
  int passed = 0;
  JSONArray cases = new JSONArray();
  String gate;

  public void onCreate(Bundle args) {
    super.onCreate(args);
    gate = args == null ? "" : args.getString("synthetic_emulator_only", "");
    start();
  }

  void yes(String name, boolean ok) throws Exception {
    cases.put(Core.obj("test", name, "status", ok ? "PASS" : "FAIL"));
    if (!ok) throw new Exception(name);
    passed++;
  }

  Button find(View v, String text) {
    if (v instanceof Button && ((Button) v).getText().toString().equals(text)) return (Button) v;
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) {
        Button b = find(g.getChildAt(i), text);
        if (b != null) return b;
      }
    }
    return null;
  }

  void click(Activity a, String label) throws Exception {
    Button b = find(a.getWindow().getDecorView(), label);
    if (b == null) throw new Exception("Button missing: " + label);
    runOnMainSync(() -> b.performClick());
    waitForIdleSync();
  }

  @Override
  public void onStart() {
    Context c = getTargetContext();
    Bundle result = new Bundle();
    try {
      if (!"YES".equals(gate)
          || !(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")))
        throw new Exception("Refusing non-emulator or unapproved test target");
      Vault.remove(c);
      JSONObject state = Vault.read(c);
      yes(
          "default sources off",
          !state.getJSONObject("policy").getBoolean("usage")
              && !state.getJSONObject("policy").getBoolean("notifications"));
      String text =
          "SYNTHETIC native share\n"
              + "One newline\n\n"
              + "Two newlines\t e\u0301 / é / 🧠 <script>inert</script>";
      Intent share =
          new Intent(Intent.ACTION_SEND)
              .setClassName(c, MainActivity.class.getName())
              .setType("text/plain")
              .putExtra(Intent.EXTRA_TEXT, text)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      MainActivity a = (MainActivity) startActivitySync(share);
      waitForIdleSync();
      click(a, "Save capture");
      JSONObject saved = Vault.read(c);
      String id = Core.ids(saved).get(0);
      JSONObject e = saved.getJSONObject("events").getJSONObject(id);
      yes(
          "Android share-to-UI durable commit",
          e.getString("text").equals(text)
              && e.getString("capture_method").equals("android-share"));
      byte[] ciphertext =
          java.nio.file.Files.readAllBytes(new File(c.getNoBackupFilesDir(), "vault.v1").toPath());
      yes(
          "on-disk bytes do not contain plaintext",
          !new String(ciphertext, java.nio.charset.StandardCharsets.ISO_8859_1)
              .contains("SYNTHETIC native share"));
      final MainActivity firstActivity = a;
      runOnMainSync(() -> firstActivity.finish());
      waitForIdleSync();
      a =
          (MainActivity)
              startActivitySync(
                  new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      yes(
          "activity reopen retains exact UTF-8 hash",
          Vault.read(c)
              .getJSONObject("events")
              .getJSONObject(id)
              .getString("text_sha256")
              .equals(Core.hash(Core.utf8(text))));
      final MainActivity current = a;
      runOnMainSync(
          () -> {
            try {
              current.showTimeline("SYNTHETIC", "all", "", "", false, false);
            } catch (Exception ex) {
              throw new RuntimeException(ex);
            }
          });
      waitForIdleSync();
      yes("timeline search has preserved ID", current.visibleIds.contains(id));
      runOnMainSync(
          () -> {
            try {
              current.showRecord(id);
            } catch (Exception ex) {
              throw new RuntimeException(ex);
            }
          });
      waitForIdleSync();
      yes(
          "original source view safe native text",
          find(current.getWindow().getDecorView(), "Create a linked revision") != null);
      JSONObject bundle =
          Core.exportArchive(Vault.read(c), Core.ids(Vault.read(c)), System.currentTimeMillis());
      JSONObject protectedFile =
          Crypto.seal(Core.utf8(bundle.toString()), "SYNTHETIC native passphrase".toCharArray());
      JSONObject decoded =
          StrictJson.parse(
              Core.decodeUtf8(
                  Crypto.open(protectedFile, "SYNTHETIC native passphrase".toCharArray())));
      JSONObject restore = Core.restore(Core.initial(System.currentTimeMillis()), decoded);
      yes(
          "Android crypto isolated restore exact",
          restore.getJSONObject("events").getJSONObject(id).getString("text").equals(text));
      yes(
          "repeat Android restore idempotent",
          Core.restore(restore, decoded).getJSONObject("events").length() == 1);
      long t = System.currentTimeMillis();
      Vault.change(
          c,
          s -> {
            JSONObject p = s.getJSONObject("policy");
            p.put("usage", true);
            p.getJSONObject("usage_allow").put(c.getPackageName(), t);
            p.getJSONObject("usage_allow").put("com.android.settings", t);
            p.put("usage_cursor", t);
          });
      yes("emulator Usage Access granted", UsageCollector.permission(c));
      Intent settings =
          new Intent(android.provider.Settings.ACTION_SETTINGS)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      c.startActivity(settings);
      Thread.sleep(1800);
      c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Thread.sleep(1800);
      UsageCollector.collect(c, "usage-instrumentation-catch-up");
      JSONObject after = Vault.read(c);
      Set<String> packages = new HashSet<>();
      for (String key : Core.ids(after)) {
        JSONObject event = after.getJSONObject("events").getJSONObject(key);
        if (event.getString("source_type").equals("auto.usage"))
          packages.add(event.getString("app"));
      }
      yes(
          "real emulator app-switch OS events reach same store",
          packages.contains("com.android.settings") && packages.contains(c.getPackageName()));
      long pauseAt = System.currentTimeMillis();
      Vault.change(c, s -> Core.pause(s, true, pauseAt));
      c.startActivity(settings);
      Thread.sleep(1000);
      c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Thread.sleep(1000);
      long resumed = System.currentTimeMillis();
      Vault.change(c, s -> Core.pause(s, false, resumed));
      UsageCollector.collect(c, "usage-instrumentation-resume");
      boolean violation = false;
      for (String key : Core.ids(Vault.read(c))) {
        JSONObject event = Vault.read(c).getJSONObject("events").getJSONObject(key);
        if (event.getString("source_type").startsWith("auto.")
            && event.getLong("event_ms") >= pauseAt
            && event.getLong("event_ms") < resumed) violation = true;
      }
      yes("real emulator paused app switches excluded", !violation);
      // Preserve this native-produced export for a separate real-core import test.
      JSONObject nativeExport =
          Core.exportArchive(Vault.read(c), Core.ids(Vault.read(c)), System.currentTimeMillis());
      File export = new File(c.getExternalFilesDir(null), "native-emulator.trbackup.json");
      try (FileOutputStream out = new FileOutputStream(export)) {
        out.write(
            Core.utf8(
                Crypto.seal(
                        Core.utf8(nativeExport.toString()),
                        "SYNTHETIC native passphrase".toCharArray())
                    .toString()));
        out.getFD().sync();
      }
      result.putString("result", "PASS");
      result.putInt("passed", passed);
      result.putString("export", export.getAbsolutePath());
    } catch (Throwable ex) {
      result.putString("result", "FAIL");
      result.putString("failure", ex.toString());
    }
    try {
      JSONObject receipt =
          Core.obj(
              "format",
              "MAX07EmulatorReceipt/1",
              "environment",
              Build.MODEL + " API " + Build.VERSION.SDK_INT,
              "physical_phone",
              false,
              "passed",
              passed,
              "cases",
              cases,
              "result",
              result.getString("result"),
              "failure",
              result.getString("failure", ""));
      File f = new File(c.getExternalFilesDir(null), "native-test-receipt.json");
      try (FileOutputStream out = new FileOutputStream(f)) {
        out.write(Core.utf8(receipt.toString(2)));
      }
      result.putString("receipt", f.getAbsolutePath());
    } catch (Exception ignored) {
    }
    finish(
        "PASS".equals(result.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED,
        result);
  }
}
