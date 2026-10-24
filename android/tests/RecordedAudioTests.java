package org.continuity.recall;

import java.util.UUID;
import org.json.JSONObject;

public final class RecordedAudioTests {
  private static int assertions;
  private static void check(boolean ok, String label) {
    assertions++;
    if (!ok) throw new AssertionError(label);
  }
  private interface Task { void run() throws Exception; }
  private static void rejects(Task task, String label) throws Exception {
    boolean rejected = false;
    try { task.run(); } catch (Exception expected) { rejected = true; }
    check(rejected, label);
  }
  public static void main(String[] args) throws Exception {
    long now = 1792800000000L;
    byte[] audio = new byte[]{1,2,3,4};
    String captureId = UUID.randomUUID().toString();
    JSONObject state = Core.initial(now);
    JSONObject event = RecordedAudio.commit(state, audio, captureId,
        now, now + 2000, 2000, true, false, "user-stop");
    check(state.getJSONObject("events").length() == 1, "one committed recording");
    check(event.getJSONObject("metadata").getBoolean("duration_known"), "known duration");
    String original = Core.digest(event);
    JSONObject recovered = RecordedAudio.commit(state, audio, captureId,
        now, now + 86400000, -1, true, true, "recovered-after-interruption");
    check(original.equals(Core.digest(recovered)), "recovery preserves original event");
    check(state.getJSONObject("events").length() == 1, "recovery does not duplicate");
    rejects(() -> RecordedAudio.commit(state, new byte[]{9}, captureId,
        now, now, -1, true, true, "recovered"), "same session rejects different bytes");
    state.getJSONArray("deleted_ids").put(event.getString("id"));
    state.getJSONObject("events").remove(event.getString("id"));
    rejects(() -> RecordedAudio.commit(state, audio, captureId,
        now, now + 90000, -1, true, true, "recovered"), "recovery cannot resurrect deletion");
    JSONObject paused = Core.initial(now);
    paused.getJSONObject("policy").put("paused", true);
    rejects(() -> RecordedAudio.commit(paused, audio, UUID.randomUUID().toString(),
        now, now, -1, true, true, "recovered"), "paused capture cannot commit");
    JSONObject rangeDeleted = Core.initial(now);
    rangeDeleted.getJSONArray("delete_ranges").put(Core.obj("start_ms", now - 1, "end_ms", now + 1));
    rejects(() -> RecordedAudio.commit(rangeDeleted, audio, UUID.randomUUID().toString(),
        now, now + 86400000, -1, true, true, "recovered"),
        "recovery cannot bypass deleted time range by using ingestion time");
    JSONObject older = Core.initial(now);
    JSONObject incomplete = RecordedAudio.commit(older, audio, UUID.randomUUID().toString(),
        now, now + 86400000, -1, false, true, "recovered-after-interruption");
    JSONObject metadata = incomplete.getJSONObject("metadata");
    check(!metadata.getBoolean("duration_known"), "process-death duration remains unknown");
    check(metadata.getLong("duration_ms") == -1, "no fabricated day-long recording duration");
    check(metadata.getString("event_time_basis").equals("file-modified-estimate"),
        "legacy orphan timestamp explicitly estimated");
    rejects(() -> RecordedAudio.commit(older, new byte[0], UUID.randomUUID().toString(),
        now, now, -1, false, true, "recovered"), "empty recording rejected");
    check(incomplete.getString("text").isEmpty(), "no invented transcript");
    check(incomplete.getJSONArray("attachments").getJSONObject(0).getString("sha256")
        .equals(Core.hash(audio)), "original bytes hashed");
    JSONObject foreground = Core.initial(now);
    String foregroundCapture = UUID.randomUUID().toString();
    JSONObject visible = RecordedAudio.commit(foreground, audio, foregroundCapture,
        now, now + 2000, 2000, true, false, "user-stop", "user-microphone",
        "explicit-visible-activity", "OS reported input type 15");
    check(visible.getString("capture_method").equals("user-microphone"),
        "existing foreground capture method preserved");
    check(visible.getJSONArray("attachments").getJSONObject(0).getString("display_name")
        .equals("voice-note.m4a"), "foreground attachment naming preserved");
    JSONObject visibleRecovered = RecordedAudio.commit(foreground, audio, foregroundCapture,
        now, now + 86400000, -1, true, true, "process-interrupted", "user-microphone",
        "explicit-visible-activity", "Unknown after interruption");
    check(Core.digest(visible).equals(Core.digest(visibleRecovered)),
        "foreground retry preserves original input route and evidence");
    check(foreground.getJSONObject("events").length() == 1,
        "foreground retry does not duplicate archive event");
    System.out.println("RecordedAudioTests: " + assertions + " assertions passed");
  }
}
