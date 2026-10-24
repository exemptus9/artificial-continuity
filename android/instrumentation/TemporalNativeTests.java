package org.continuity.recall;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic emulator acceptance. Never included in the app APK or run on a real phone. */
public final class TemporalNativeTests extends Instrumentation {
  private String gate;
  private boolean restartOnly;
  private int passed;
  private final JSONArray cases = new JSONArray();
  private Context target;
  private Activity activity;

  @Override public void onCreate(Bundle args) {
    super.onCreate(args);
    gate = args == null ? "" : args.getString("synthetic_emulator_only", "");
    restartOnly = args != null && "YES".equals(args.getString("verify_restart_only", ""));
    start();
  }

  private void yes(String name, boolean result) throws Exception {
    cases.put(Core.obj("test", name, "status", result ? "PASS" : "FAIL"));
    if (!result) throw new Exception(name);
    passed++;
  }

  private void main(Runnable work) throws Exception {
    final Throwable[] failure = {null};
    runOnMainSync(() -> { try { work.run(); } catch (Throwable ex) { failure[0] = ex; } });
    if (failure[0] != null) throw new Exception("Main-thread test action failed", failure[0]);
    waitForIdleSync();
  }

  private List<View> views(View root) {
    List<View> result = new ArrayList<>();
    result.add(root);
    if (root instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) root;
      for (int i = 0; i < group.getChildCount(); i++) result.addAll(views(group.getChildAt(i)));
    }
    return result;
  }

  private List<View> allViews() {
    List<View> result = new ArrayList<>();
    for (View root : WindowInspector.getGlobalWindowViews()) result.addAll(views(root));
    return result;
  }

  private boolean hasText(String expected) throws Exception {
    final boolean[] found = {false};
    main(() -> {
      for (View v : views(activity.getWindow().getDecorView()))
        if (v instanceof TextView && ((TextView) v).getText().toString().contains(expected)) found[0] = true;
    });
    return found[0];
  }

  private void awaitText(String expected) throws Exception {
    long end = SystemClock.elapsedRealtime() + 15000;
    while (SystemClock.elapsedRealtime() < end) {
      if (hasText(expected)) return;
      SystemClock.sleep(100);
    }
    throw new Exception("UI did not show: " + expected);
  }

  private void click(String label) throws Exception {
    main(() -> {
      for (View v : allViews()) {
        if (v instanceof Button && ((Button) v).getText().toString().equals(label)) {
          v.performClick();
          return;
        }
      }
      throw new IllegalStateException("Button missing: " + label);
    });
  }

  private void typeFirst(String value) throws Exception {
    main(() -> {
      for (View v : views(activity.getWindow().getDecorView())) {
        if (v instanceof EditText) { ((EditText) v).setText(value); return; }
      }
      throw new IllegalStateException("Text input missing");
    });
  }

  private Activity open(Class<? extends Activity> type, String section, String sourceId) throws Exception {
    if (activity != null) main(() -> activity.finish());
    Intent request = new Intent(target, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    if (section != null) request.putExtra("section", section);
    if (sourceId != null) {
      ArrayList<String> selected = new ArrayList<>(); selected.add(sourceId);
      request.putStringArrayListExtra("source_ids", selected);
    }
    activity = startActivitySync(request);
    waitForIdleSync();
    return activity;
  }

  /** Actual Android layout rendering; production FLAG_SECURE is never cleared. */
  private void snapshot(String filename) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 15000;
    final boolean[] laidOut = {false};
    while (SystemClock.elapsedRealtime() < deadline && !laidOut[0]) {
      main(() -> {
        View view = activity.getWindow().getDecorView();
        laidOut[0] = view.getWidth() > 0 && view.getHeight() > 0 && !view.isLayoutRequested();
      });
      if (!laidOut[0]) SystemClock.sleep(100);
    }
    if (!laidOut[0]) throw new Exception("Android layout did not settle before screenshot");
    final Bitmap[] bitmap = {null};
    main(() -> {
      View view = activity.getWindow().getDecorView();
      bitmap[0] = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
      view.draw(new Canvas(bitmap[0]));
    });
    File folder = new File(target.getExternalFilesDir(null), "temporal-visual-evidence");
    if (!folder.isDirectory() && !folder.mkdirs()) throw new Exception("Evidence directory unavailable");
    try (FileOutputStream out = new FileOutputStream(new File(folder, filename))) {
      if (!bitmap[0].compress(Bitmap.CompressFormat.PNG, 100, out)) throw new Exception("Screenshot failed");
      out.getFD().sync();
    } finally { bitmap[0].recycle(); }
  }

  private void shell(String command) throws Exception {
    try (ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand(command);
         FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
      byte[] buffer = new byte[1024]; while (input.read(buffer) != -1) { }
    }
  }

  private void verifyRestart() throws Exception {
    String expected = "SYNTHETIC: the glove compartment latch sticks. Keep the original wording.\nA second line.";
    JSONObject state = Vault.read(target);
    JSONObject original = null;
    for (String id : Core.ids(state)) {
      JSONObject event = state.getJSONObject("events").getJSONObject(id);
      if (expected.equals(event.optString("text"))) { original = event; break; }
    }
    yes("Cold process retains exact source text and hash", original != null
        && Core.hash(Core.utf8(expected)).equals(original.getString("text_sha256")));
    String id = original.getString("id");
    yes("Cold process retains source-linked review", MemoryWorkbench.reviews(state, id).size() == 1
        && "next_step".equals(MemoryWorkbench.status(state, id)));
    yes("Cold process retains explicit capture pause", state.getJSONObject("policy").getBoolean("paused"));
    open(MemoryActivity.class, "timeline", id);
    awaitText("Open original & context");
    yes("Cold process exposes saved original in memory UI", hasText(expected));
    snapshot("08-reopened.png");
  }

  @Override public void onStart() {
    target = getTargetContext();
    Bundle result = new Bundle();
    boolean authorized = "YES".equals(gate)
        && (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
    try {
      if (!authorized || Build.VERSION.SDK_INT < 29)
        throw new Exception("Refusing non-emulator, unsupported API or unapproved synthetic target");
      if (restartOnly) {
        verifyRestart();
        result.putString("result", "PASS");
      } else {
      Vault.remove(target);
      open(VoiceActivity.class, null, null);
      awaitText("Speak once");
      yes("Now activity retains secure-window privacy flag",
          (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
      yes("Now opens without background capture", !BackgroundVoiceService.running);
      final boolean[] defaultJournalOff = {false};
      main(() -> {
        for (View v : views(activity.getWindow().getDecorView()))
          if (v instanceof CheckBox && ((CheckBox) v).getText().toString().equals("Also save my recall questions"))
            defaultJournalOff[0] = !((CheckBox) v).isChecked();
      });
      yes("Recognized-utterance journal defaults off", defaultJournalOff[0]);
      String words = "SYNTHETIC: the glove compartment latch sticks. Keep the original wording.\nA second line.";
      typeFirst(words);
      click("Save these words");
      awaitText("Saved.");
      JSONObject saved = Vault.read(target);
      String originalId = Core.ids(saved).get(0);
      JSONObject original = saved.getJSONObject("events").getJSONObject(originalId);
      String originalHash = Core.digest(original);
      yes("Typed Now note is durably saved without speech attribution",
          words.equals(original.getString("text")) && !original.getString("capture_method").contains("speech"));
      open(VoiceActivity.class, null, null);
      typeFirst("What did I say about the glove compartment?");
      click("Ask my timeline");
      awaitText("glove compartment latch sticks");
      yes("Reopened Now screen retrieves its durable source ID", hasText(originalId));
      yes("Recall leaves journal unchanged", Vault.read(target).getJSONObject("events").length() == 1);
      snapshot("01-now.png");

      open(MemoryActivity.class, "timeline", originalId);
      awaitText("Open original & context");
      yes("Time ribbon displays preserved source words", hasText(words));
      snapshot("02-timeline.png");
      click("Open original & context");
      awaitText("Add interpretation or next step");
      snapshot("03-source.png");
      click("Add interpretation or next step");
      main(() -> {
        for (View v : allViews()) {
          if (v instanceof EditText) {
            EditText input = (EditText) v;
            String hint = input.getHint() == null ? "" : input.getHint().toString();
            if (hint.equals("What should future you know?")) input.setText("Inspect the retaining screw before trying the latch again.");
            if (hint.startsWith("Project ID")) input.setText("P29");
          }
          if (v instanceof Spinner) ((Spinner) v).setSelection(4);
        }
      });
      click("Save review");
      awaitText("OWNER REVIEW · next step");
      JSONObject reviewed = Vault.read(target);
      yes("Review UI preserves the exact original source hash",
          originalHash.equals(Core.digest(reviewed.getJSONObject("events").getJSONObject(originalId))));
      yes("Review UI appends a linked next-step interpretation",
          MemoryWorkbench.reviews(reviewed, originalId).size() == 1
          && "next_step".equals(MemoryWorkbench.status(reviewed, originalId)));

      open(MemoryActivity.class, "desk", null);
      awaitText("Inspect the retaining screw");
      yes("Continuity desk exposes explicitly reviewed project", hasText("P29"));
      snapshot("04-desk.png");
      open(MemoryActivity.class, "privacy", null);
      awaitText("Pause & discard active recording");
      yes("Privacy screen discloses offline integration boundary", hasText("No connected AI or automatic sync"));
      snapshot("05-privacy.png");
      click("Pause & discard active recording");
      awaitText("Resume capture");
      yes("Privacy pause persists", Vault.read(target).getJSONObject("policy").getBoolean("paused"));
      int beforePausedAttempt = Vault.read(target).getJSONObject("events").length();
      open(VoiceActivity.class, null, null);
      typeFirst("SYNTHETIC should not persist while paused");
      click("Save these words");
      awaitText("Capture is paused");
      yes("Paused Now save is denied without adding records",
          beforePausedAttempt == Vault.read(target).getJSONObject("events").length());

      shell("settings put system font_scale 1.6");
      SystemClock.sleep(700);
      open(MemoryActivity.class, "privacy", null);
      awaitText("Resume capture");
      yes("Privacy renders with large text configuration", activity.getResources().getConfiguration().fontScale >= 1.5f);
      snapshot("06-large-text.png");
      getUiAutomation().setRotation(UiAutomation.ROTATION_FREEZE_90);
      SystemClock.sleep(700);
      open(MemoryActivity.class, "desk", null);
      awaitText("Inspect the retaining screw");
      yes("Desk renders in landscape",
          activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
      snapshot("07-landscape.png");
      result.putString("result", "PASS");
      }
    } catch (Throwable failure) {
      result.putString("result", "FAIL");
      result.putString("failure", failure.toString());
    } finally {
      if (authorized) {
        try { shell("settings put system font_scale 1.0"); getUiAutomation().setRotation(UiAutomation.ROTATION_UNFREEZE); }
        catch (Exception ignored) { }
      }
    }
    try {
      JSONObject receipt = Core.obj("format", "TemporalEmulatorReceipt/1", "physical_phone", false,
          "environment", Build.MODEL + " API " + Build.VERSION.SDK_INT,
          "app_version", BuildInfo.VERSION, "source_commit", BuildInfo.COMMIT,
          "source_sha256", BuildInfo.SOURCE_SHA256, "completed_ms", System.currentTimeMillis(),
          "restart_only", restartOnly,
          "screenshot_kind", "Android Activity decor rendering on synthetic emulator; FLAG_SECURE retained",
          "voice_hardware_tested", false, "passed", passed, "cases", cases,
          "result", result.getString("result"), "failure", result.getString("failure", ""));
      File file = new File(target.getExternalFilesDir(null), restartOnly
          ? "temporal-restart-receipt.json" : "temporal-test-receipt.json");
      try (FileOutputStream out = new FileOutputStream(file)) { out.write(Core.utf8(receipt.toString(2))); }
      result.putString("receipt", file.getAbsolutePath());
      result.putInt("passed", passed);
    } catch (Exception ignored) { }
    finish("PASS".equals(result.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }
}
