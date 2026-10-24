package org.continuity.recall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Explicit, user-started foreground microphone service. Runs only during an
 * announced note with a persistent notification and Stop/Cancel actions.
 * A locked screen does not silently restart it; no boot receiver starts it.
 */
public final class BackgroundVoiceService extends Service {
  public static final String ACTION_START = "org.continuity.recall.voice.START";
  public static final String ACTION_STOP = "org.continuity.recall.voice.STOP";
  public static final String ACTION_CANCEL = "org.continuity.recall.voice.CANCEL";
  public static final String FILE_NAME = "background-recording.m4a";
  private static final String CHANNEL = "total-recall-voice";
  private static final int NOTICE = 1714;
  public static volatile boolean running = false;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private MediaRecorder recorder;
  private File file;
  private long started;
  private final Runnable watchPause = new Runnable() {
    @Override public void run() {
      if (recorder == null) return;
      try {
        if (Vault.read(BackgroundVoiceService.this).getJSONObject("policy")
            .getBoolean("paused")) {
          stopRecording(true, "privacy-pause");
          return;
        }
      } catch (Exception e) {
        stopRecording(true, "vault-unavailable");
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
    channel.setDescription("Visible when a user-started microphone recording is active");
    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
        .createNotificationChannel(channel);
  }

  private PendingIntent action(String name, int code) {
    Intent i = new Intent(this, BackgroundVoiceService.class);
    i.setAction(name);
    return PendingIntent.getService(this, code, i,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private Notification notification() {
    Intent open = new Intent(this, VoiceActivity.class);
    PendingIntent openScreen = PendingIntent.getActivity(this, 13, open,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Total Recall · Recording microphone")
        .setContentText("User-started private audio note. Stop or cancel here.")
        .setContentIntent(openScreen)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(android.R.drawable.ic_media_pause, "Stop & save",
            action(ACTION_STOP, 11))
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel",
            action(ACTION_CANCEL, 12))
        .build();
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? "" : intent.getAction();
    if (ACTION_CANCEL.equals(action)) {
      stopRecording(true, "user-cancel");
    } else if (ACTION_STOP.equals(action)) {
      stopRecording(false, "user-stop");
    } else if (ACTION_START.equals(action) && recorder == null) {
      try { startRecording(); }
      catch (Exception e) {
        Log.e("TotalRecallVoice", "Could not start foreground recording", e);
        running = false;
        stopSelf();
      }
    } else if (recorder == null) {
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  private void startRecording() throws Exception {
    if (Vault.read(this).getJSONObject("policy").getBoolean("paused"))
      throw new Exception("Capture is paused");
    file = new File(getNoBackupFilesDir(), FILE_NAME);
    if (file.exists()) throw new Exception(
        "An interrupted note exists. Recover it before starting another.");
    // Must be started via a visible user action with RECORD_AUDIO granted.
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(NOTICE, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    } else startForeground(NOTICE, notification());
    MediaRecorder candidate = new MediaRecorder();
    try {
      candidate.setAudioSource(MediaRecorder.AudioSource.MIC);
      candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      candidate.setAudioChannels(1);
      candidate.setAudioEncodingBitRate(32000);
      candidate.setAudioSamplingRate(16000);
      candidate.setOutputFile(file.getAbsolutePath());
      candidate.setMaxFileSize(Core.MAX_BLOB - 4096);
      candidate.setMaxDuration(15 * 60 * 1000);
      candidate.setOnInfoListener((source, reason, extra) -> {
        if (reason == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
            || reason == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
          stopRecording(false, "size-or-duration-limit");
        }
      });
      candidate.prepare();
      candidate.start();
      recorder = candidate;
      started = System.currentTimeMillis();
      running = true;
      handler.postDelayed(watchPause, 2000);
    } catch (Exception e) {
      candidate.release();
      // Preserve a potentially incomplete file rather than claiming a clean save.
      throw e;
    }
  }

  private void stopRecording(boolean cancel, String reason) {
    handler.removeCallbacks(watchPause);
    MediaRecorder active = recorder;
    recorder = null;
    running = false;
    if (active == null) { stopSelf(); return; }
    boolean finalized = false;
    try { active.stop(); finalized = true; }
    catch (Exception failure) {
      Log.w("TotalRecallVoice", "Audio was interrupted; recovery may be required");
    } finally { active.release(); }
    if (cancel && file != null) {
      file.delete(); // Cancel intentionally destroys the uncommitted file.
    } else if (finalized && file != null) {
      try { persistFile(this, file, started, reason, false); }
      catch (Exception e) {
        // Never delete the source on a failed archive commit.
        Log.e("TotalRecallVoice", "Vault commit failed; audio retained for recovery", e);
      }
    }
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  /** Atomic vault commit before deleting transient plaintext recording. */
  public static void persistFile(Context context, File recorded, long eventTime,
      String reason, boolean possiblyIncomplete) throws Exception {
    if (!recorded.isFile()) throw new Exception("No recording file");
    byte[] bytes = Vault.bounded(new FileInputStream(recorded), Core.MAX_BLOB);
    if (bytes.length == 0) throw new Exception("Empty recording");
    String sha = Core.hash(bytes);
    JSONArray attachments = new JSONArray().put(Core.obj(
        "sha256", sha, "bytes", bytes.length, "mime", "audio/mp4",
        "display_name", "background-voice-note.m4a"));
    JSONObject blobs = Core.obj(sha, Base64.getEncoder().encodeToString(bytes));
    long now = System.currentTimeMillis();
    long when = (eventTime > 0 && eventTime <= now) ? eventTime : now;
    Vault.change(context, state -> {
      JSONObject event = Core.event(state, "audio", "user-started-background-microphone",
          "", when, now, "microphone", null, attachments, Core.obj(
          "transcription", "unavailable",
          "microphone_scope", "explicit-foreground-service",
          "possibly_incomplete", possiblyIncomplete,
          "stop_reason", reason, "duration_ms", Math.max(0, now - when)));
      Core.add(state, event, blobs);
    });
    if (!recorded.delete())
      Log.w("TotalRecallVoice", "Archive saved but transient input cleanup failed");
  }

  @Override public void onDestroy() {
    handler.removeCallbacks(watchPause);
    if (recorder != null) {
      try { recorder.reset(); } catch (Exception ignored) {}
      recorder.release();
      recorder = null;
    }
    running = false;
    super.onDestroy();
  }
}
