package org.continuity.recall;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Deterministic local recall. Retrieved text is evidence, never executable instructions. */
public final class TemporalEngine {
  private TemporalEngine() {}
  private static final Set<String> STOP = new HashSet<>(Arrays.asList(
      "a", "an", "about", "and", "are", "at", "did", "do", "for", "from",
      "i", "in", "is", "it", "me", "my", "of", "on", "or", "say", "said",
      "the", "to", "was", "were", "what", "when", "with", "you", "please"));
  private static final String WEEKDAYS =
      "monday|tuesday|wednesday|thursday|friday|saturday|sunday";
  private static final Pattern DATE = Pattern.compile(
      "(?<![\\p{L}\\p{N}])(?:(?:on|from|for)\\s+)?"
      + "(today|yesterday|last\\s+(?:" + WEEKDAYS + ")|[0-9]{4}-[0-9]{2}-[0-9]{2})"
      + "(?![\\p{L}\\p{N}'’])");
  private static final Pattern FIRST = Pattern.compile(
      "^(?:when did i first (?:start talking about|talk about|mention|record|say something about)"
      + "|first mention of|earliest note about)\\s+(.+)$");
  private static final String RECALL_LIMIT =
      "Local lexical matches only; untranscribed audio and missing captures are not searched. "
      + "Recorded words are source evidence, not independently verified facts.";

  public static final class Reply {
    public final String text;
    public final boolean saveUtterance;
    public final List<String> sourceIds;
    public final String scope;
    public final String uncertainty;
    public final int matchCount;
    public Reply(String text, boolean saveUtterance, List<String> sourceIds) {
      this(text, saveUtterance, sourceIds, "", "", sourceIds.size());
    }
    public Reply(String text, boolean saveUtterance, List<String> sourceIds,
        String scope, String uncertainty, int matchCount) {
      this.text = text;
      this.saveUtterance = saveUtterance;
      this.sourceIds = Collections.unmodifiableList(new ArrayList<>(sourceIds));
      this.scope = scope;
      this.uncertainty = uncertainty;
      this.matchCount = matchCount;
    }
  }

  private static Reply response(String text, boolean save, List<String> ids) {
    return new Reply(text, save, ids);
  }
  private static String command(String input) {
    return input == null ? "" : input.toLowerCase(Locale.ROOT).trim()
        .replaceFirst("[?!.]+$", "").trim();
  }
  public static boolean explicitBookmark(String input) {
    String s = command(input).replaceFirst("^(?:hey )?lexi[, ]+", "");
    return s.equals("bookmark") || s.startsWith("bookmark ");
  }
  public static boolean explicitCapture(String input) {
    String s = command(input);
    // A recall question must not silently become a new note.
    if (input != null && input.trim().endsWith("?") && s.startsWith("remember ")) return false;
    return s.startsWith("remember ") || s.startsWith("note ") ||
        s.startsWith("log ") || s.startsWith("record this ") || explicitBookmark(input);
  }

  /** Compatibility entry point: old callers supply on-device recognizer output. */
  public static JSONObject save(JSONObject state, String transcript, long when) throws Exception {
    return save(state, transcript, when, true, null);
  }
  /** Exact received text. Optional recognizer confidence is 0..1000, not fact certainty. */
  public static JSONObject save(JSONObject state, String input, long when,
      boolean spoken, Integer confidenceMilli) throws Exception {
    if (input == null || input.trim().isEmpty()) throw new Exception("Empty text cannot be archived");
    if (confidenceMilli != null && (confidenceMilli < 0 || confidenceMilli > 1000))
      throw new Exception("Recognizer confidence must be between 0 and 1000 or unavailable");
    if (!spoken && confidenceMilli != null)
      throw new Exception("Typed input has no speech-recognition confidence");
    JSONObject metadata = Core.obj(
        "input_origin", spoken ? "on-device-speech-recognition" : "typed",
        "transcription", spoken ? "on-device-recognizer-output-not-verbatim" : "not-applicable",
        "original_audio_retained", false,
        "received_text_preserved_exactly", true,
        "epistemic_status", "user-supplied-observation",
        "memory_class", "raw-observation",
        "bookmark", explicitBookmark(input),
        "recognizer_confidence_available", confidenceMilli != null);
    if (spoken) metadata.put("device_recognized_text_is_exactly_what_was_received", true);
    if (confidenceMilli != null) metadata.put("recognizer_confidence_milli", confidenceMilli);
    JSONObject event = Core.event(state, "note",
        spoken ? "android-on-device-speech" : "temporal-typed-input", input,
        when, when, spoken ? "user-spoken" : "user-typed", null, new JSONArray(), metadata);
    Core.add(state, event, new JSONObject());
    return event;
  }
  static boolean manualText(JSONObject event) {
    if (event == null) return false;
    String type = event.optString("source_type", "");
    return !type.startsWith("auto.") && !type.equals("audio") &&
        !event.optString("text", "").trim().isEmpty();
  }
  static String day(long ms) { return day(ms, ZoneId.systemDefault()); }
  static String day(long ms, ZoneId zone) {
    return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString();
  }
  static String friendlyTime(long ms) { return friendlyTime(ms, ZoneId.systemDefault()); }
  static String friendlyTime(long ms, ZoneId zone) {
    return Instant.ofEpochMilli(ms).atZone(zone)
        .format(DateTimeFormatter.ofPattern("MMM d, uuuu, h:mm a", Locale.US));
  }
  static String compact(String s) {
    String oneLine = s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    if (oneLine.length() <= 220) return oneLine;
    int end = 217;
    if (Character.isHighSurrogate(oneLine.charAt(end - 1))) end--;
    return oneLine.substring(0, end) + "...";
  }
  static Set<String> words(String text) {
    Set<String> words = new HashSet<>();
    for (String part : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
      if (!part.isEmpty() && !STOP.contains(part)) words.add(part);
    }
    return words;
  }
  static boolean interpretation(JSONObject event) {
    return MemoryWorkbench.isReview(event);
  }
  static List<JSONObject> candidates(JSONObject state) throws Exception {
    List<JSONObject> items = new ArrayList<>();
    JSONObject events = state.getJSONObject("events");
    for (String id : Core.ids(state)) {
      JSONObject event = events.getJSONObject(id);
      if (!manualText(event) || !MemoryWorkbench.visible(state, event)) continue;
      items.add(event);
    }
    items.sort((a, b) -> {
      int byTime = Long.compare(b.optLong("event_ms"), a.optLong("event_ms"));
      return byTime != 0 ? byTime : a.optString("id").compareTo(b.optString("id"));
    });
    return items;
  }

  public static Reply interpret(JSONObject state, String utterance, long now, boolean journal)
      throws Exception {
    return interpret(state, utterance, now, journal, ZoneId.systemDefault());
  }
  /** The caller's clock and zone govern calendar words; never reads a second wall clock. */
  public static Reply interpret(JSONObject state, String utterance, long now, boolean journal,
      ZoneId zone) throws Exception {
    return interpretInternal(state, utterance, now, journal, zone, false);
  }

  /** Search-only UI: no query is saved, and pause does not prevent reading existing sources. */
  public static Reply recall(JSONObject state, String question, long now, ZoneId zone)
      throws Exception {
    return interpretInternal(state, question, now, false, zone, true);
  }

  private static Reply interpretInternal(JSONObject state, String utterance, long now,
      boolean journal, ZoneId zone, boolean readOnly) throws Exception {
    if (utterance == null || utterance.trim().isEmpty())
      return response("I didn't catch anything. Nothing was saved.", false, new ArrayList<>());
    String command = command(utterance);
    if (!readOnly && state.getJSONObject("policy").getBoolean("paused"))
      return response("Total Recall is paused. No voice text was saved.", false, new ArrayList<>());
    if (command.equals("help") || command.equals("what can you do"))
      return response("Say: remember this, followed by a thought. Or: find notes about the latch "
          + "last Tuesday. Or: when did I first mention the latch? You can bookmark this moment. "
          + "This is local recall, not a general AI chat.", journal, new ArrayList<>());
    if (readOnly && explicitCapture(utterance))
      return response("This screen searches existing notes. Use Capture to save those words; "
          + "nothing was saved here.", false, new ArrayList<>());
    if (explicitCapture(utterance))
      return response(explicitBookmark(utterance)
          ? "I'll save your words as a dated bookmark."
          : "I'll save these words to your private timeline.", true, new ArrayList<>());
    if (command.equals("last note") || command.equals("read my last note")) {
      List<JSONObject> items = candidates(state);
      if (items.isEmpty())
        return response("Your local timeline has no searchable text notes.", journal, new ArrayList<>());
      JSONObject e = items.get(0);
      return new Reply((interpretation(e) ? "Your interpretation recorded " : "You recorded ")
          + friendlyTime(e.getLong("event_ms"), zone) + ": " + compact(e.getString("text")),
          journal, Arrays.asList(e.getString("id")),
          "Latest saved text note · " + zone.getId(), RECALL_LIMIT, 1);
    }
    if (command.equals("today's notes") || command.equals("todays notes")) command = "find today";
    if (command.equals("yesterday's notes") || command.equals("yesterdays notes"))
      command = "find yesterday";
    if (command.equals("daily thread")) command = "find today";
    boolean earliest = false;
    String subject = null;
    Matcher first = FIRST.matcher(command);
    if (first.matches()) {
      earliest = true;
      subject = first.group(1);
    } else {
      for (String prefix : Arrays.asList(
          "what did i say about ", "what did i mention about ", "what did i record about ",
          "what did i capture about ", "what did i capture ",
          "what do you remember about ", "find notes about ", "show notes about ",
          "what did i say ", "what did i mention ", "what did i record ",
          "what happened ", "find ", "recall ", "search for ", "search ")) {
        if (command.startsWith(prefix)) {
          subject = command.substring(prefix.length()).trim();
          break;
        }
      }
    }
    if (subject != null) {
      Query query = parseQuery(subject, earliest, now, zone);
      if (query.error != null) return response(query.error, journal, new ArrayList<>());
      return retrieve(state, query, journal, zone);
    }
    return response("I can save notes, retrieve exact words by topic and day, and find the earliest "
        + "matching saved note. This offline build has no general AI connection. "
        + "Try “find notes about the latch yesterday” or “help”.", journal, new ArrayList<>());
  }

  private static final class Query {
    String topic = "", phrase = "", error;
    LocalDate date;
    boolean earliest;
    Set<String> terms = Collections.emptySet();
  }
  private static boolean insideQuotes(String input, int index) {
    boolean quoted = false;
    for (int i = 0; i < index; i++) {
      char c = input.charAt(i);
      if (c == '"') quoted = !quoted;
      else if (c == '“') quoted = true;
      else if (c == '”') quoted = false;
    }
    return quoted;
  }
  private static Query parseQuery(String input, boolean earliest, long now, ZoneId zone) {
    Query query = new Query();
    query.earliest = earliest;
    StringBuilder remainder = new StringBuilder();
    Matcher date = DATE.matcher(input);
    int copied = 0;
    while (date.find()) {
      if (insideQuotes(input, date.start())) continue;
      if (query.date != null) {
        query.error = "Use one calendar day per question, such as yesterday or 2026-10-20.";
        return query;
      }
      String value = date.group(1);
      LocalDate current = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
      try {
        if (value.equals("today")) query.date = current;
        else if (value.equals("yesterday")) query.date = current.minusDays(1);
        else if (value.startsWith("last ")) {
          DayOfWeek weekday = DayOfWeek.valueOf(value.replaceFirst("last\\s+", "")
              .toUpperCase(Locale.ROOT));
          query.date = current.with(TemporalAdjusters.previous(weekday));
        } else query.date = LocalDate.parse(value);
      } catch (RuntimeException invalidDate) {
        query.error = "That calendar date is invalid. Use a real date in YYYY-MM-DD form.";
        return query;
      }
      remainder.append(input, copied, date.start()).append(' ');
      copied = date.end();
    }
    remainder.append(input.substring(copied));
    query.topic = remainder.toString().trim().replaceFirst("^about\\s+", "")
        .replaceAll("\\s+", " ").trim();
    if ((query.topic.startsWith("\"") && query.topic.endsWith("\"") && query.topic.length() >= 2)
        || (query.topic.startsWith("“") && query.topic.endsWith("”") && query.topic.length() >= 2)) {
      query.phrase = query.topic.substring(1, query.topic.length() - 1).trim();
      query.topic = query.phrase;
    }
    query.terms = words(query.topic);
    if (query.topic.equals("that idea") || query.topic.equals("that") || query.topic.equals("it"))
      query.error = "Name the idea or subject to search. I won't guess which earlier context you mean.";
    else if (query.terms.isEmpty() && query.phrase.isEmpty() && query.date == null)
      query.error = "Tell me a subject or a calendar day to look for.";
    else if (earliest && query.terms.isEmpty() && query.phrase.isEmpty())
      query.error = "Name the subject whose earliest saved mention you want.";
    return query;
  }

  public static Reply daySummary(JSONObject state, String requestedDay, boolean journal)
      throws Exception {
    Query query = new Query();
    try { query.date = LocalDate.parse(requestedDay); }
    catch (RuntimeException invalidDate) {
      return response("That calendar date is invalid. Use YYYY-MM-DD.", journal, new ArrayList<>());
    }
    return retrieve(state, query, journal, ZoneId.systemDefault());
  }
  /** Literal-topic lookup. Calendar parsing occurs only in interpret, never inside source text. */
  public static Reply lookup(JSONObject state, String subject, boolean journal) throws Exception {
    Query query = new Query();
    query.topic = subject == null ? "" : subject.toLowerCase(Locale.ROOT).trim();
    query.terms = words(query.topic);
    if (query.terms.isEmpty())
      return response("Tell me which subject to look for.", journal, new ArrayList<>());
    return retrieve(state, query, journal, ZoneId.systemDefault());
  }
  private static Reply retrieve(JSONObject state, Query query, boolean journal, ZoneId zone)
      throws Exception {
    List<JSONObject> matched = new ArrayList<>();
    long start = query.date == null ? Long.MIN_VALUE
        : query.date.atStartOfDay(zone).toInstant().toEpochMilli();
    long end = query.date == null ? Long.MAX_VALUE
        : query.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    for (JSONObject e : candidates(state)) {
      long when = e.getLong("event_ms");
      if (when < start || when >= end) continue;
      String lower = e.getString("text").toLowerCase(Locale.ROOT);
      // Require every meaningful word, or the requested exact phrase. No silent broadening.
      if (!query.phrase.isEmpty() ? lower.contains(query.phrase)
          : words(lower).containsAll(query.terms)) matched.add(e);
    }
    if (query.earliest) matched.sort((a, b) -> {
      int byTime = Long.compare(a.optLong("event_ms"), b.optLong("event_ms"));
      return byTime != 0 ? byTime : a.optString("id").compareTo(b.optString("id"));
    });
    String scope = (query.earliest ? "Earliest matching saved note" : "Saved text notes")
        + (query.topic.isEmpty() ? "" : " · " + query.topic)
        + (query.date == null ? "" : " · " + query.date) + " · " + zone.getId();
    String uncertainty = RECALL_LIMIT + (query.earliest
        ? " Earliest means earliest in this archive, not the first time an idea occurred."
        : " All meaningful search words must occur in a matching note.");
    List<String> ids = new ArrayList<>();
    StringBuilder out = new StringBuilder();
    int limit = query.earliest ? 1 : 3;
    for (JSONObject e : matched) {
      if (ids.size() == limit) break;
      if (out.length() > 0) out.append(" | ");
      if (query.earliest) out.append("Earliest matching saved note, ");
      else out.append(interpretation(e) ? "Your interpretation recorded " : "You recorded ");
      out.append(friendlyTime(e.getLong("event_ms"), zone)).append(": ");
      if (query.earliest && interpretation(e)) out.append("[your interpretation] ");
      out.append(compact(e.getString("text")));
      ids.add(e.getString("id"));
    }
    if (ids.isEmpty()) out.append("I found no matching saved text notes")
        .append(query.date == null ? "" : " for " + query.date)
        .append(". This does not mean nothing happened. Audio without transcription isn't searchable by its words.");
    else if (!query.earliest && matched.size() > ids.size()) out.append(" | And ")
        .append(matched.size() - ids.size()).append(" more matching text notes in your timeline.");
    return new Reply(out.toString(), journal, ids, scope, uncertainty, matched.size());
  }
  static int overlap(Set<String> query, Set<String> text) {
    int hits = 0;
    for (String term : query) if (text.contains(term)) hits++;
    return hits;
  }
}
