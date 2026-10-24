package org.continuity.recall;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.UUID;

/**
 * A bounded, explicitly started microphone foreground service. The active m4a is
 * an app-private plaintext recovery buffer; only committed archive data is
 * encrypted by Vault. No boot restart, retry loop, or automatic transcription.
 */
public final class BackgroundVoiceService extends Service {
  public static final String ACTION_START = "org.continuity.recall.voice.START";
  public static final String ACTION_STOP = "org.continuity.recall.voice.STOP";
  public static final String ACTION_CANCEL = "org.continuity.recall.voice.CANCEL";
  public static final String FILE_NAME = "background-recording.m4a";
  private static final String CHANNEL = "total-recall-voice";
  private static final String PREFS = "background-voice-state-v1";
  private static final String OWNER = "background";
  private static final int NOTICE = 1714;
  private static final int MAX_DURATION_MS = 15 * 60 * 1000;
  public static volatile boolean running = false;
  private static volatile String latestState = "";

  private final Handler handler = new Handler(Looper.getMainLooper());
  private MediaRecorder recorder;
  private AudioManager.AudioRecordingCallback recordingCallback;
  private File file;
  private long started, startedElapsed;
  private boolean ownsLease;

  private static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  private static void status(Context context, String value) {
    latestState = value;
    // Only fixed state codes and timing metadata are stored here; never content,
    // transcripts, credentials, exception messages, or original filenames.
    preferences(context).edit().putString("state", value).apply();
  }

  /** Truthful status for the Now screen, including service failures after dispatch. */
  public static String statusText(Context context) {
    if (running) return "Microphone on · Recording a private audio note";
    String state = latestState.isEmpty()
        ? preferences(context).getString("state", "idle") : latestState;
    File input = new File(context.getNoBackupFilesDir(), FILE_NAME);
    if (input.isFile()) {
      if ("saved-cleanup-needed".equals(state))
        return "Microphone off · Saved; temporary audio still needs cleanup";
      if ("cancel-cleanup-needed".equals(state))
        return "Microphone off · Could not discard temporary audio; retry cleanup";
      return "Microphone off · An interrupted or unsaved note is available for recovery";
    }
    if ("saved".equals(state)) return "Microphone off · Audio note saved in the encrypted vault";
    if ("saved-incomplete".equals(state))
      return "Microphone off · Partial audio saved; review it for missing or interrupted sound";
    if ("cancelled".equals(state)) return "Microphone off · Unsaved audio discarded";
    if ("paused".equals(state)) return "Microphone off · Capture paused; unsaved audio discarded";
    if ("notification-unavailable".equals(state))
      return "Microphone off · Enable the voice-note notification and channel before recording";
    if ("permission-denied".equals(state))
      return "Microphone off · Recording permission is unavailable";
    if ("microphone-busy".equals(state))
      return "Background recorder off · Finish the current speech request first";
    if ("vault-unavailable".equals(state))
      return "Microphone off · Could not open the private vault; recording did not start";
    if ("start-failed".equals(state))
      return "Microphone off · Recording could not start; open the app and try again";
    return "Background microphone off";
  }

  /** Original capture time if recorded; legacy buffers use an explicit estimate. */
  public static long interruptedStartedAt(Context context) {
    File input = new File(context.getNoBackupFilesDir(), FILE_NAME);
    return preferences(context).getLong("started_ms", input.lastModified());
  }

  private final Runnable watchPause = new Runnable() {
    @Override public void run() {
      if (recorder == null) return;
      if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
          != PackageManager.PERMISSION_GRANTED) {
        stopRecording(false, "permission-revoked", true, true);
        return;
      }
      if (!notificationsVisible()) {
        stopRecording(false, "notification-disabled", true, true);
        return;
      }
      try {
        if (Vault.read(BackgroundVoiceService.this).getJSONObject("policy")
            .getBoolean("paused")) {
          stopRecording(true, "privacy-pause", false, true);
          return;
        }
      } catch (Exception ignored) {
        // Stop capture, but do not destroy an already authorized observation just
        // because storage became unavailable. A failed commit retains the buffer.
        stopRecording(false, "vault-unavailable", true, true);
        return;
      }
      handler.postDelayed(this, 2000);
    }
  };

  @Override public IBinder onBind(Intent intent) { return null; }

  @Override public void onCreate() {
    super.onCreate();
    NotificationChannel channel = new NotificationChannel(CHANNEL,
        "Total Recall active voice notes", NotificationManager.IMPORTANCE_LOW);
    channel.setDescription("Visible while a user-started microphone note is active");
    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
        .createNotificationChannel(channel);
  }

  private boolean notificationsVisible() {
    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
    return manager.areNotificationsEnabled() && channel != null
        && channel.getImportance() != NotificationManager.IMPORTANCE_NONE
        && (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED);
  }

  private PendingIntent action(String name, int code) {
    Intent intent = new Intent(this, BackgroundVoiceService.class).setAction(name);
    return PendingIntent.getService(this, code, intent,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private Notification notification() {
    PendingIntent openScreen = PendingIntent.getActivity(this, 13,
        new Intent(this, VoiceActivity.class),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Total Recall · Recording microphone")
        .setContentText("Private audio note · Stop & save or discard below")
        .setContentIntent(openScreen)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setCategory(Notification.CATEGORY_SERVICE)
        .addAction(android.R.drawable.ic_media_pause, "Stop & save", action(ACTION_STOP, 11))
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Discard", action(ACTION_CANCEL, 12))
        .build();
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? "" : intent.getAction();
    if (ACTION_CANCEL.equals(action)) {
      stopRecording(true, "user-cancel", false, true);
    } else if (ACTION_STOP.equals(action)) {
      stopRecording(false, "user-stop", false, true);
    } else if (ACTION_START.equals(action) && recorder == null) {
      startRecording();
    } else if (recorder == null) {
      stopSelf();
    }
    // Process death never restarts capture or replays the previous start intent.
    return START_NOT_STICKY;
  }

  private void startRecording() {
    if (!MicrophoneLease.acquire(OWNER)) {
      status(this, "microphone-busy");
      stopSelf();
      return;
    }
    ownsLease = true;
    String failure = "start-failed";
    MediaRecorder candidate = null;
    try {
      if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
          != PackageManager.PERMISSION_GRANTED) {
        failure = "permission-denied";
        throw new IllegalStateException();
      }
      if (!notificationsVisible()) {
        failure = "notification-unavailable";
        throw new IllegalStateException();
      }
      failure = "vault-unavailable";
      boolean paused = Vault.read(this).getJSONObject("policy").getBoolean("paused");
      if (paused) {
        failure = "paused";
        throw new IllegalStateException();
      }
      failure = "start-failed";
      file = new File(getNoBackupFilesDir(), FILE_NAME);
      if (file.exists()) {
        failure = "interrupted";
        throw new IllegalStateException();
      }
      // Persist capture identity BEFORE opening the microphone. An interrupted
      // commit/recovery must not invent a second event or a new event timestamp.
      started = System.currentTimeMillis();
      if (!preferences(this).edit().putString("capture_id", UUID.randomUUID().toString())
          .putLong("started_ms", started).remove("duration_ms")
          .remove("finished_ms").putString("state", "starting").commit())
        throw new IllegalStateException();
      latestState = "starting";
      if (Build.VERSION.SDK_INT >= 29)
        startForeground(NOTICE, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
      else startForeground(NOTICE, notification());
      candidate = new MediaRecorder();
      candidate.setAudioSource(MediaRecorder.AudioSource.MIC);
      if (Build.VERSION.SDK_INT >= 30) candidate.setPrivacySensitive(true);
      candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      candidate.setAudioChannels(1);
      candidate.setAudioEncodingBitRate(32000);
      candidate.setAudioSamplingRate(16000);
      candidate.setOutputFile(file.getAbsolutePath());
      candidate.setMaxFileSize(Core.MAX_BLOB - 4096);
      candidate.setMaxDuration(MAX_DURATION_MS);
      final MediaRecorder sourceRecorder = candidate;
      candidate.setOnInfoListener((source, reason, extra) -> {
        if (recorder != sourceRecorder) return;
        if (reason == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
            || reason == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)
          stopRecording(false, "size-or-duration-limit", false, true);
      });
      candidate.setOnErrorListener((source, what, extra) -> {
        if (recorder == sourceRecorder)
          stopRecording(false, "recorder-interrupted", true, true);
      });
      if (Build.VERSION.SDK_INT >= 29) {
        recordingCallback = new AudioManager.AudioRecordingCallback() {
          @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
            if (recorder != sourceRecorder) return;
            for (AudioRecordingConfiguration config : configs) {
              if (config.isClientSilenced()) {
                stopRecording(false, "system-silenced", true, true);
                return;
              }
            }
          }
        };
        candidate.registerAudioRecordingCallback(getMainExecutor(), recordingCallback);
      }
      candidate.prepare();
      startedElapsed = SystemClock.elapsedRealtime();
      candidate.start();
      recorder = candidate;
      running = true;
      status(this, "recording");
      handler.postDelayed(watchPause, 2000);
    } catch (Exception ignored) {
      releaseRecorder(candidate);
      running = false;
      // A start failure can leave bytes. Preserve them for explicit review;
      // only an empty, newly created output is safe to remove here.
      if (file != null && file.isFile() && file.length() == 0) file.delete();
      status(this, failure);
      releaseLease();
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
    }
  }

  private void releaseRecorder(MediaRecorder active) {
    if (active == null) return;
    if (Build.VERSION.SDK_INT >= 29 && recordingCallback != null) {
      try { active.unregisterAudioRecordingCallback(recordingCallback); }
      catch (Exception ignored) {}
    }
    recordingCallback = null;
    try { active.release(); } catch (Exception ignored) {}
  }

  private void releaseLease() {
    if (ownsLease) {
      ownsLease = false;
      MicrophoneLease.release(OWNER);
    }
  }

  private void stopRecording(boolean cancel, String reason, boolean incomplete, boolean stopService) {
    handler.removeCallbacks(watchPause);
    MediaRecorder active = recorder;
    recorder = null; // Fence recorder callbacks before stop/release can produce them.
    running = false;
    if (active != null) {
      long duration = Math.max(0, SystemClock.elapsedRealtime() - startedElapsed);
      boolean finalized = false;
      try { active.stop(); finalized = true; }
      catch (Exception ignored) { /* Too-short and interrupted files are not safe to call saved. */ }
      finally { releaseRecorder(active); }
      preferences(this).edit().putLong("finished_ms", System.currentTimeMillis())
          .putLong("duration_ms", finalized && !incomplete ? duration : -1).commit();
      if (cancel) {
        boolean deleted = file == null || !file.exists() || file.delete();
        status(this, deleted ? ("privacy-pause".equals(reason) ? "paused" : "cancelled")
            : "cancel-cleanup-needed");
      } else if (finalized && file != null) {
        try { persistFile(this, file, started, reason, incomplete); }
        catch (Exception ignored) { status(this, "interrupted"); }
      } else {
        status(this, "interrupted");
      }
    }
    releaseLease();
    stopForeground(STOP_FOREGROUND_REMOVE);
    if (stopService) stopSelf();
  }

  /** Commit once before deleting the plaintext buffer. A failed commit retains it. */
  public static synchronized void persistFile(Context context, File recorded, long eventTime,
      String reason, boolean possiblyIncomplete) throws Exception {
    if (running) throw new Exception("Stop recording before recovery");
    if (!recorded.isFile()) throw new Exception("No recording file");
    byte[] bytes = Vault.bounded(new FileInputStream(recorded), Core.MAX_BLOB);
    if (bytes.length == 0) throw new Exception("Empty recording");
    SharedPreferences prefs = preferences(context);
    String captureId = prefs.getString("capture_id", "");
    boolean knownStart = prefs.contains("started_ms");
    long start = knownStart ? prefs.getLong("started_ms", eventTime) : eventTime;
    if (start < 0) start = recorded.lastModified();
    // A deterministic legacy ID still makes retries idempotent when a pre-0.9
    // interrupted buffer had no durable capture identity.
    if (captureId.isEmpty()) captureId = UUID.nameUUIDFromBytes(bytes).toString();
    final String identity = captureId;
    final long when = start;
    final long duration = possiblyIncomplete ? -1 : prefs.getLong("duration_ms", -1);
    final long observed = System.currentTimeMillis();
    Vault.change(context, state -> RecordedAudio.commit(state, bytes, identity,
        when, observed, duration, knownStart, possiblyIncomplete, reason));
    status(context, recorded.delete() ? (possiblyIncomplete ? "saved-incomplete" : "saved")
        : "saved-cleanup-needed");
  }

  @Override public void onDestroy() {
    // Android may skip onDestroy on abrupt process death. The durable capture
    // identity and private buffer remain available on the next explicit launch.
    stopRecording(false, "service-stopped", true, false);
    super.onDestroy();
  }
}
