package org.continuity.recall;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Deterministic local recall. Retrieved text is evidence, never instructions.
 * Does not call a model or a network endpoint, and never invents absent events.
 */
public final class TemporalEngine {
  private TemporalEngine() {}

  private static final Set<String> STOP = new HashSet<>(Arrays.asList(
      "a", "an", "about", "and", "are", "at", "did", "do", "for", "from",
      "i", "in", "is", "it", "me", "my", "of", "on", "or", "say", "said",
      "the", "to", "was", "were", "what", "when", "with", "you"));

  public static final class Reply {
    public final String text;
    public final boolean saveUtterance;
    public final List<String> sourceIds;
    public Reply(String text, boolean saveUtterance, List<String> sourceIds) {
      this.text = text;
      this.saveUtterance = saveUtterance;
      this.sourceIds = sourceIds;
    }
  }

  private static Reply response(String text, boolean save, List<String> ids) {
    return new Reply(text, save, ids);
  }

  public static boolean explicitCapture(String input) {
    String s = input.toLowerCase(Locale.ROOT).trim();
    return s.startsWith("remember ") || s.startsWith("note ") ||
        s.startsWith("log ") || s.startsWith("record this ");
  }

  public static JSONObject save(JSONObject state, String transcript, long when) throws Exception {
    if (transcript == null || transcript.trim().isEmpty())
      throw new Exception("Empty voice text cannot be archived");
    JSONObject event = Core.event(state, "note", "android-on-device-speech",
        transcript, when, when, "user-spoken", null, new JSONArray(),
        Core.obj("transcription", "on-device-recognizer-output-not-verbatim",
                 "original_audio_retained", false,
                 "device_recognized_text_is_exactly_what_was_received", true));
    Core.add(state, event, new JSONObject());
    return event;
  }

  static boolean manualText(JSONObject event) {
    if (event == null) return false;
    String type = event.optString("source_type", "");
    return !type.startsWith("auto.") && !type.equals("audio") &&
        !event.optString("text", "").trim().isEmpty();
  }

  static String day(long ms) {
    return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .toLocalDate().toString();
  }

  static String friendlyTime(long ms) {
    return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US));
  }

  static String compact(String s) {
    String oneLine = s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    return oneLine.length() <= 220 ? oneLine : oneLine.substring(0, 217) + "...";
  }

  static Set<String> words(String text) {
    Set<String> words = new HashSet<>();
    for (String part : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
      if (part.length() >= 2 && !STOP.contains(part)) words.add(part);
    }
    return words;
  }

  static List<JSONObject> candidates(JSONObject state) throws Exception {
    List<JSONObject> items = new ArrayList<>();
    JSONObject events = state.getJSONObject("events");
    for (String id : Core.ids(state)) {
      JSONObject event = events.getJSONObject(id);
      if (manualText(event)) items.add(event);
    }
    items.sort((a, b) -> Long.compare(b.optLong("event_ms"), a.optLong("event_ms")));
    return items;
  }

  public static Reply interpret(JSONObject state, String utterance, long now, boolean journal)
      throws Exception {
    if (utterance == null || utterance.trim().isEmpty())
      return response("I didn't catch anything. Nothing was saved.", false, new ArrayList<>());
    String command = utterance.toLowerCase(Locale.ROOT).trim();
    if (state.getJSONObject("policy").getBoolean("paused"))
      return response("Total Recall is paused. No voice text was saved.", false, new ArrayList<>());

    if (command.equals("help") || command.equals("what can you do")) {
      return response("Say: remember this, followed by a thought. Or: what did I say about "
          + "the glove compartment? Or: what did I record today? "
          + "This is local recall, not a general AI chat.", journal, new ArrayList<>());
    }
    if (explicitCapture(utterance)) {
      return response("I'll save the recognized words to your private timeline.", true,
          new ArrayList<>());
    }
    if (command.contains("what did i record today") ||
        command.contains("what happened today") ||
        command.equals("today's notes") || command.equals("todays notes")) {
      return daySummary(state, LocalDate.now(ZoneId.systemDefault()).toString(), journal);
    }
    if (command.contains("what did i record yesterday") ||
        command.contains("what happened yesterday") ||
        command.equals("yesterday's notes")) {
      return daySummary(state,
          LocalDate.now(ZoneId.systemDefault()).minusDays(1).toString(), journal);
    }
    if (command.equals("last note") || command.equals("read my last note")) {
      List<JSONObject> items = candidates(state);
      if (items.isEmpty())
        return response("Your local timeline has no searchable text notes.",
            journal, new ArrayList<>());
      JSONObject e = items.get(0);
      return response("From " + friendlyTime(e.getLong("event_ms")) + ": " +
          compact(e.getString("text")), journal, Arrays.asList(e.getString("id")));
    }

    String subject = null;
    for (String prefix : Arrays.asList(
        "what did i say about ", "what do you remember about ",
        "find notes about ", "find ", "recall ", "search for ")) {
      if (command.startsWith(prefix)) {
        subject = command.substring(prefix.length()).trim();
        break;
      }
    }
    if (subject != null) {
      subject = subject.replaceFirst("[?!.]+$", "");
      if (words(subject).isEmpty())
        return response("Tell me which subject to look for.", journal, new ArrayList<>());
      return lookup(state, subject, journal);
    }
    return response(
        "I can remember your spoken notes, search them, and read a dated summary. "
        + "For open-ended AI conversation, use ChatGPT Voice; this offline build "
        + "does not pretend to be connected to a model.", journal, new ArrayList<>());
  }

  public static Reply daySummary(JSONObject state, String requestedDay, boolean journal)
      throws Exception {
    List<String> ids = new ArrayList<>();
    StringBuilder out = new StringBuilder("Notes for " + requestedDay + ": ");
    int total = 0;
    for (JSONObject e : candidates(state)) {
      if (!day(e.getLong("event_ms")).equals(requestedDay)) continue;
      total++;
      if (ids.size() < 3) {
        if (!ids.isEmpty()) out.append(" | ");
        out.append(friendlyTime(e.getLong("event_ms"))).append(": ")
            .append(compact(e.getString("text")));
        ids.add(e.getString("id"));
      }
    }
    if (total == 0) return response(
        "I found no local text notes for " + requestedDay
        + ". This does not mean nothing happened.", journal, ids);
    if (total > ids.size()) out.append(" | And ").append(total - ids.size())
        .append(" more text notes in your timeline.");
    return response(out.toString(), journal, ids);
  }

  public static Reply lookup(JSONObject state, String subject, boolean journal)
      throws Exception {
    Set<String> terms = words(subject);
    List<JSONObject> items = candidates(state);
    List<JSONObject> matched = new ArrayList<>();
    for (JSONObject e : items) {
      String lower = e.getString("text").toLowerCase(Locale.ROOT);
      Set<String> recordWords = words(lower);
      int hits = 0;
      for (String term : terms) if (recordWords.contains(term)) hits++;
      if (hits > 0) matched.add(e);
    }
    // Prefer more lexical matches, then most recent. Do not synthesize missing details.
    matched.sort((a, b) -> {
      int sa = overlap(terms, words(a.optString("text")));
      int sb = overlap(terms, words(b.optString("text")));
      if (sa != sb) return Integer.compare(sb, sa);
      return Long.compare(b.optLong("event_ms"), a.optLong("event_ms"));
    });
    List<String> ids = new ArrayList<>();
    StringBuilder out = new StringBuilder();
    for (JSONObject e : matched) {
      if (ids.size() == 3) break;
      if (out.length() > 0) out.append(" | ");
      out.append("You recorded ").append(friendlyTime(e.getLong("event_ms")))
          .append(": ").append(compact(e.getString("text")));
      ids.add(e.getString("id"));
    }
    if (ids.isEmpty()) return response(
        "I couldn't find a local text note matching " + subject
            + ". Audio without transcription isn't searchable by its words.",
        journal, ids);
    return response(out.toString(), journal, ids);
  }

  static int overlap(Set<String> query, Set<String> text) {
    int hits = 0;
    for (String term : query) if (text.contains(term)) hits++;
    return hits;
  }
}
