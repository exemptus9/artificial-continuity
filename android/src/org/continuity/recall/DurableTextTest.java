package org.continuity.recall;

import org.json.*;

/** The phone wizard's exact-text check. No Android dependencies; no captured text in errors. */
public final class DurableTextTest {
  private DurableTextTest() {}

  public static String prepare(JSONObject state, String nonce) throws Exception {
    // Preparing a diagnostic must never replace the owner's unfinished words.
    // Check before changing wizard state, including its previous result/binding.
    if (!state.optString("draft", "").isEmpty()) {
      throw new Exception("Your unfinished capture is kept. Open Capture and save or finish "
          + "that draft before preparing a test phrase.");
    }
    String phrase = "Total Recall test " + nonce
        + "\nOne newline.\n\nTwo newlines.\nPoetry keeps its shape. e\u0301 / é / 🧠\t…";
    JSONObject wizard = state.getJSONObject("wizard");
    wizard.put("nonce", nonce).put("expected_hash", Core.hash(Core.utf8(phrase)));
    wizard.remove("durable_record_id");
    wizard.remove("durable-text");
    return phrase;
  }

  /** Bind the first exact saved test capture in the same transaction as its event. */
  public static void saved(JSONObject state, JSONObject event) throws Exception {
    JSONObject wizard = state.getJSONObject("wizard");
    String expected = wizard.optString("expected_hash", "");
    if (!wizard.optString("nonce", "").isEmpty()
        && wizard.optString("durable_record_id", "").isEmpty()
        && !expected.isEmpty()
        && Core.hash(Core.utf8(event.getString("text"))).equals(expected)
        && event.getString("text_sha256").equals(expected)) {
      wizard.put("durable_record_id", event.getString("id"));
    }
  }

  public static JSONObject verify(JSONObject state) throws Exception {
    JSONObject wizard = state.getJSONObject("wizard");
    String nonce = wizard.optString("nonce", "");
    String expected = wizard.optString("expected_hash", "");
    if (nonce.isEmpty() || expected.isEmpty()) {
      throw new Exception("Prepare a unique multiline test phrase, then tap Save capture. "
          + "Reopen the app and tap Verify. Your other notes are unchanged.");
    }
    JSONObject events = state.getJSONObject("events");
    String id = wizard.optString("durable_record_id", "");
    if (!id.isEmpty()) {
      JSONObject event = events.optJSONObject(id);
      if (event == null) throw new Exception("The saved test record is no longer in this store. "
          + "Prepare and save a new test phrase; do not reset the app.");
      if (matches(event, expected)) return event;
      throw new Exception("The saved test record failed its text hash comparison. "
          + "Keep the app and existing exports; report this result before relying on the store.");
    }
    // 0.7.0 saved only a nonce/hash. Accept its exact record even if a separate note
    // also mentions the nonce; an edited copy must not invalidate a preserved original.
    boolean changed = false;
    for (String candidate : Core.ids(state)) {
      JSONObject event = events.getJSONObject(candidate);
      if (matches(event, expected)) return event;
      changed |= event.optString("text", "").contains(nonce);
    }
    if (Core.hash(Core.utf8(state.optString("draft", ""))).equals(expected)) {
      throw new Exception("The test phrase is still a draft. Open Capture and tap Save capture, "
          + "then reopen the app and verify. Preparing a phrase alone does not save a record.");
    }
    if (changed) throw new Exception("A note contains this test's nonce, but its text does not "
        + "match the prepared phrase. Keep that note; prepare and save a new phrase without edits.");
    throw new Exception("No saved record matches the latest prepared test phrase. "
        + "Open Capture and save it. If you prepared another phrase after saving, "
        + "that starts a new test; the earlier note is still in Timeline.");
  }

  private static boolean matches(JSONObject event, String expected) throws Exception {
    return expected.equals(event.optString("text_sha256", ""))
        && expected.equals(Core.hash(Core.utf8(event.getString("text"))));
  }
}
