package org.continuity.recall;

import java.util.Base64;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Source-preserving, idempotent commit rules for one explicitly started audio note. */
public final class RecordedAudio {
  private RecordedAudio() {}

  public static JSONObject commit(JSONObject state, byte[] bytes, String captureId,
      long startedMs, long observedMs, long durationMs, boolean knownStart,
      boolean possiblyIncomplete, String reason) throws Exception {
    return commit(state, bytes, captureId, startedMs, observedMs, durationMs, knownStart,
        possiblyIncomplete, reason, "user-started-background-microphone",
        "explicit-foreground-service", "");
  }

  public static JSONObject commit(JSONObject state, byte[] bytes, String captureId,
      long startedMs, long observedMs, long durationMs, boolean knownStart,
      boolean possiblyIncomplete, String reason, String captureMethod,
      String microphoneScope, String inputRoute) throws Exception {
    if (bytes == null || bytes.length == 0 || bytes.length > Core.MAX_BLOB)
      throw new Exception("Recording size unsupported");
    UUID.fromString(captureId);
    if (startedMs < 0 || observedMs < 0 || durationMs < -1)
      throw new Exception("Invalid recording timing");
    if (!"user-started-background-microphone".equals(captureMethod)
        && !"user-microphone".equals(captureMethod))
      throw new Exception("Unsupported recording capture method");
    String sha = Core.hash(bytes);
    JSONArray attachments = new JSONArray().put(Core.obj(
        "sha256", sha, "bytes", bytes.length, "mime", "audio/mp4",
        "display_name", "user-microphone".equals(captureMethod)
            ? "voice-note.m4a" : "background-voice-note.m4a"));
    JSONObject metadata = Core.obj(
        "transcription", "unavailable",
        "microphone_scope", microphoneScope,
        "possibly_incomplete", possiblyIncomplete,
        "stop_reason", reason,
        "duration_ms", durationMs,
        "duration_known", durationMs >= 0,
        "event_time_basis", knownStart ? "recording-start" : "file-modified-estimate");
    if (inputRoute != null && !inputRoute.isEmpty()) metadata.put("input_route", inputRoute);
    String identity = "user-microphone".equals(captureMethod) ? "foreground-voice:" : "background-voice:";
    JSONObject event = Core.event(state, "audio", captureMethod,
        "", startedMs, observedMs, "microphone", identity + captureId, attachments, metadata);
    String id = event.getString("id");
    JSONObject existing = state.getJSONObject("events").optJSONObject(id);
    if (existing != null) {
      JSONArray previous = existing.getJSONArray("attachments");
      if (!"audio".equals(existing.getString("source_type"))
          || !captureMethod.equals(existing.getString("capture_method"))
          || previous.length() != 1
          || !sha.equals(previous.getJSONObject(0).getString("sha256"))
          || bytes.length != previous.getJSONObject(0).getLong("bytes"))
        throw new Exception("Recording identity conflict");
      // Recovery after a successful commit but failed temporary-file deletion must
      // reuse the ORIGINAL event, including timestamps and provenance.
      event = existing;
    }
    Core.add(state, event, Core.obj(sha, Base64.getEncoder().encodeToString(bytes)));
    return event;
  }
}
