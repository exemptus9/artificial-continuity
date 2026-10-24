package org.continuity.recall;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Explicit one-utterance voice entry and deterministic offline memory Q&A.
 * No wake word, silent ambient monitoring, cloud account or Internet permission.
 */
public final class VoiceActivity extends Activity implements RecognitionListener {
  private static final int MIC_ONCE = 41;
  private static final int MIC_BACKGROUND = 42;
  private static final int NOTICE_BACKGROUND = 43;
  private static final int BG = 0xff101c21, FG = 0xffeef2ed;
  private static final int MUTED = 0xffb3c4c8, ACCENT = 0xffb5edae;
  private SpeechRecognizer recognizer;
  private TextToSpeech tts;
  private boolean ttsReady = false, listening = false;
  private TextView status, answer, sourceIds;
  private EditText typed;
  private CheckBox journal;
  private String lastText = "", lastAnswer = "";

  private int dp(int n) {
    return (int) (getResources().getDisplayMetrics().density * n + .5f);
  }
  private TextView text(LinearLayout parent, String value, int sp, int color) {
    TextView v = new TextView(this);
    v.setText(value);
    v.setTextSize(sp);
    v.setTextColor(color);
    v.setPadding(dp(3), dp(9), dp(3), dp(9));
    v.setLineSpacing(dp(3), 1.0f);
    parent.addView(v);
    return v;
  }
  private void button(LinearLayout parent, String title, View.OnClickListener action) {
    Button b = new Button(this);
    b.setText(title);
    b.setAllCaps(false);
    b.setTextSize(18);
    b.setMinHeight(dp(58));
    parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
    b.setOnClickListener(action);
  }
  private void error(String heading, Exception ex) {
    status.setText(heading + ": " + (ex.getMessage() != null
        ? ex.getMessage() : ex.getClass().getSimpleName()));
  }

  @Override
  public void onCreate(Bundle savedInstance) {
    super.onCreate(savedInstance);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(BG);
    LinearLayout column = new LinearLayout(this);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(dp(20), dp(20), dp(20), dp(30));
    scroll.addView(column);
    setContentView(scroll);

    TextView title = text(column, "VOICE · TOTAL RECALL", 25, ACCENT);
    title.setTypeface(null, Typeface.BOLD);
    text(column, "Private, timestamped and grounded. Microphone off until you request one utterance. "
        + "On-device speech only; nothing is streamed by this app.", 15, MUTED);
    status = text(column, "Idle — no microphone access", 15, ACCENT);
    button(column, "Speak once · voice command", v -> requestSpeech());
    button(column, "Start a background audio note", v -> requestBackground());
    button(column, "Stop & save background audio", v -> controlBackground(
        BackgroundVoiceService.ACTION_STOP));
    button(column, "Cancel background audio", v -> new AlertDialog.Builder(this)
        .setMessage("Discard the current background recording without saving?")
        .setPositiveButton("Discard", (d, which) ->
            controlBackground(BackgroundVoiceService.ACTION_CANCEL))
        .setNegativeButton("Keep recording", null).show());

    journal = new CheckBox(this);
    journal.setText("Journal every recognized utterance (off by default)");
    journal.setTextColor(FG);
    journal.setTextSize(15);
    journal.setChecked(getPreferences(0).getBoolean("journal_utterances", false));
    journal.setOnCheckedChangeListener((v, value) ->
        getPreferences(0).edit().putBoolean("journal_utterances", value).apply());
    column.addView(journal);

    text(column, "You can say “Remember the glove compartment latch sticks,” "
        + "“What did I say about the glove latch?”, “Read my last note,” "
        + "or “What did I record today?”", 15, MUTED);
    typed = new EditText(this);
    typed.setTextColor(FG);
    typed.setHintTextColor(MUTED);
    typed.setTextSize(18);
    typed.setHint("Or type a note or memory question");
    typed.setSingleLine(false);
    typed.setMinLines(2);
    column.addView(typed);
    button(column, "Process typed words", v -> process(typed.getText().toString(), false));
    button(column, "Save typed text as note", v -> process(typed.getText().toString(), true));
    answer = text(column, "Answers appear here, sourced from your private timeline.", 18, FG);
    sourceIds = text(column, "", 12, MUTED);
    button(column, "Repeat answer aloud", v -> speak(lastAnswer));
    button(column, "Share reviewed context to ChatGPT", v -> share());
    button(column, "Open full Total Recall timeline", v ->
        startActivity(new Intent(this, MainActivity.class)));
    button(column, "Recover interrupted background recording", v -> recover());
    text(column, "Limits: This is not an always-on wake word. Android's on-device recognizer "
        + "handles discrete requests while this screen is open. Audio captured in background "
        + "has no transcript until a separate reviewed transcription step exists. "
        + "Sharing to ChatGPT is manual. Check local device permission and capacity.", 13, MUTED);

    tts = new TextToSpeech(this, result -> {
      if (result == TextToSpeech.SUCCESS) {
        ttsReady = true;
        tts.setLanguage(Locale.US);
        tts.setSpeechRate(1.05f);
      } else {
        status.setText("No working device speech-output engine. Text still works.");
      }
    });
    try { Vault.read(this); }
    catch (Exception ex) { error("Could not read encrypted vault", ex); }
  }

  private void requestSpeech() {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_ONCE);
      return;
    }
    if (Build.VERSION.SDK_INT < 31 ||
        !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
      status.setText("No Android on-device speech recognizer is available. "
          + "Typed notes still work; no cloud recognizer fallback.");
      return;
    }
    if (listening) {
      status.setText("Already listening to a requested utterance.");
      return;
    }
    try {
      if (recognizer != null) recognizer.destroy();
      recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
      recognizer.setRecognitionListener(this);
      Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
      request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
          RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
      request.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag());
      request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
      request.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
      listening = true;
      status.setText("Microphone listening for one utterance...");
      recognizer.startListening(request);
    } catch (Exception ex) {
      listening = false;
      error("Speech could not start", ex);
    }
  }

  private void requestBackground() {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_BACKGROUND);
      return;
    }
    if (Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTICE_BACKGROUND);
      return;
    }
    try {
      if (Vault.read(this).getJSONObject("policy").getBoolean("paused")) {
        status.setText("Capture paused. Resume it in Total Recall controls first.");
        return;
      }
      Intent i = new Intent(this, BackgroundVoiceService.class);
      i.setAction(BackgroundVoiceService.ACTION_START);
      startForegroundService(i);
      status.setText("Background note starting. Android will display a microphone notification. "
          + "Use its Stop & save action, or this screen's Stop button.");
    } catch (Exception ex) {
      error("Could not start background note", ex);
    }
  }

  private void controlBackground(String action) {
    Intent i = new Intent(this, BackgroundVoiceService.class);
    i.setAction(action);
    try {
      // User-visible control action. A nonexistent service does not create a recording.
      startService(i);
      status.setText(action.equals(BackgroundVoiceService.ACTION_CANCEL)
          ? "Cancel requested." : "Stop & save requested. Review your timeline.");
    } catch (Exception ex) {
      error("Recorder action failed", ex);
    }
  }

  private void process(String input, boolean forceSave) {
    if (input == null || input.trim().isEmpty()) {
      status.setText("Empty utterance. Nothing saved.");
      return;
    }
    try {
      long now = System.currentTimeMillis();
      JSONObject state = Vault.read(this);
      TemporalEngine.Reply reply = TemporalEngine.interpret(state, input, now, journal.isChecked());
      if (state.getJSONObject("policy").getBoolean("paused")) {
        status.setText("Capture is paused. No record was added.");
        answer.setText(reply.text);
        return;
      }
      boolean save = forceSave || reply.saveUtterance;
      final String[] id = {""};
      if (save) {
        Vault.change(this, s -> {
          JSONObject event = TemporalEngine.save(s, input, now);
          id[0] = event.getString("id");
        });
      }
      lastText = input;
      lastAnswer = reply.text;
      typed.setText(input);
      answer.setText(reply.text);
      StringBuilder provenance = new StringBuilder();
      if (save) provenance.append("SAVED · ").append(id[0])
          .append("\nTranscript is the device-recognized/typed text, not verbatim audio.\n");
      else provenance.append("NOT SAVED · recognized queries remain ephemeral.\n");
      if (!reply.sourceIds.isEmpty()) {
        provenance.append("Local source record IDs:\n");
        for (String sourceId : reply.sourceIds) provenance.append(sourceId).append('\n');
      }
      sourceIds.setText(provenance.toString());
      status.setText(save ? "Encrypted capture committed to Total Recall." :
          "Local lookup complete. No new record saved.");
      speak(reply.text);
    } catch (Exception ex) {
      error("No save was confirmed", ex);
    }
  }

  private void speak(String message) {
    if (message == null || message.isEmpty()) return;
    if (ttsReady) {
      String words = message.length() > 1200 ? message.substring(0, 1200) + "... "
          + "See the screen for the full answer." : message;
      tts.speak(words, TextToSpeech.QUEUE_FLUSH, null, "temporal-answer");
    } else {
      status.setText("Answer is visible; speech output isn't ready.");
    }
  }

  private void share() {
    if (lastText.isEmpty() || lastAnswer.isEmpty()) {
      status.setText("Ask something first. Nothing has been shared.");
      return;
    }
    try {
      if (Vault.read(this).getJSONObject("policy").getBoolean("paused")) {
        status.setText("Sharing blocked while Total Recall is paused.");
        return;
      }
      Intent share = new Intent(Intent.ACTION_SEND);
      share.setType("text/plain");
      share.putExtra(Intent.EXTRA_TEXT,
          "User question/recognized text:\n" + lastText +
          "\n\nLocal timeline retrieval (unverified user-source evidence):\n" +
          lastAnswer + "\n\nPlease distinguish quotes, inference and suggestions.");
      startActivity(Intent.createChooser(share, "Review and send to an app"));
    } catch (Exception ex) { error("Could not open share chooser", ex); }
  }

  private void recover() {
    File file = new File(getNoBackupFilesDir(), BackgroundVoiceService.FILE_NAME);
    if (BackgroundVoiceService.running) {
      status.setText("Background audio is still recording. Stop it before recovery.");
      return;
    }
    if (!file.isFile()) {
      status.setText("No interrupted background recording found.");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Recover an incomplete recording?")
        .setMessage("This audio might be truncated, corrupt or unplayable. "
            + "Attempt preservation in the encrypted archive without asserting completeness?")
        .setPositiveButton("Recover", (d, w) -> {
          try {
            BackgroundVoiceService.persistFile(this, file, file.lastModified(),
                "recovered-after-interruption", true);
            status.setText("Recovered bytes saved. Playback may be incomplete.");
          } catch (Exception e) { error("Recovery failed; file retained", e); }
        })
        .setNegativeButton("Cancel", null).show();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] p, int[] grants) {
    super.onRequestPermissionsResult(code, p, grants);
    if (grants.length == 0 || grants[0] != PackageManager.PERMISSION_GRANTED) {
      status.setText(code == NOTICE_BACKGROUND
          ? "Notification permission denied. Background recording was not started; "
              + "Stop/Cancel controls must be visible before starting."
          : "Microphone permission not granted. Typed recall still works.");
      return;
    }
    if (code == MIC_ONCE) requestSpeech();
    if (code == MIC_BACKGROUND || code == NOTICE_BACKGROUND) requestBackground();
  }

  @Override public void onReadyForSpeech(Bundle b) { status.setText("Speak now."); }
  @Override public void onBeginningOfSpeech() { status.setText("Hearing your words..."); }
  @Override public void onRmsChanged(float rms) {}
  @Override public void onBufferReceived(byte[] bytes) {}
  @Override public void onEndOfSpeech() { status.setText("Processing recognized words..."); }
  @Override public void onError(int code) {
    listening = false;
    status.setText("Recognizer ended with code " + code
        + ". Nothing was committed. Try again or type.");
  }
  @Override public void onResults(Bundle bundle) {
    listening = false;
    ArrayList<String> guesses = bundle.getStringArrayList(
        SpeechRecognizer.RESULTS_RECOGNITION);
    if (guesses == null || guesses.isEmpty()) {
      status.setText("No recognized words. Nothing saved.");
      return;
    }
    process(guesses.get(0), false);
  }
  @Override public void onPartialResults(Bundle b) {}
  @Override public void onEvent(int type, Bundle params) {}

  @Override
  protected void onPause() {
    if (listening && recognizer != null) {
      recognizer.cancel();
      listening = false;
    }
    super.onPause();
  }
  @Override
  protected void onDestroy() {
    if (recognizer != null) recognizer.destroy();
    if (tts != null) tts.shutdown();
    super.onDestroy();
  }
}
