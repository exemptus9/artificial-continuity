package org.continuity.recall;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Source-preserving organization and owner review. No model, I/O or inference.
 * Review classifications describe the owner's assessment, never verified truth.
 * Mutations belong inside Vault.change; original event objects are never edited.
 */
public final class MemoryWorkbench {
  private MemoryWorkbench() {}

  public static final List<String> STATUSES = Collections.unmodifiableList(Arrays.asList(
      "observation", "inference", "decision", "intention", "next_step", "blocker", "canonical"));
  public static final int MAX_REVIEW_BYTES = 8000;

  private static final Comparator<JSONObject> NEWEST_FIRST = (a, b) -> {
    int time = Long.compare(b.optLong("event_ms"), a.optLong("event_ms"));
    if (time != 0) return time;
    int sequence = Integer.compare(sequence(b), sequence(a));
    if (sequence != 0) return sequence;
    return a.optString("id").compareTo(b.optString("id"));
  };

  private static int sequence(JSONObject event) {
    JSONObject metadata = event.optJSONObject("metadata");
    return metadata == null ? 0 : metadata.optInt("review_sequence", 0);
  }

  public static boolean isReview(JSONObject event) {
    return event != null && "user-review".equals(event.optString("capture_method"));
  }

  /** A review always links directly to an intact original, never another review. */
  public static boolean visible(JSONObject state, JSONObject event) throws Exception {
    return visible(state, event, new HashMap<>());
  }

  private static boolean visible(JSONObject state, JSONObject event,
      Map<String, String> sourceHashes) throws Exception {
    if (event == null || Core.deleted(state, event)) return false;
    if (!isReview(event)) return true;
    JSONObject metadata = event.optJSONObject("metadata");
    if (metadata == null
        || !"user_interpretation".equals(metadata.optString("review_role"))
        || !STATUSES.contains(metadata.optString("epistemic_status"))) return false;
    JSONObject source = state.getJSONObject("events").optJSONObject(metadata.optString("source_id"));
    if (source == null || isReview(source) || Core.deleted(state, source)
        || source.optString("source_type").startsWith("auto.")) return false;
    String sourceId = source.getString("id");
    String hash = sourceHashes.get(sourceId);
    if (hash == null) {
      hash = Core.digest(source);
      sourceHashes.put(sourceId, hash);
    }
    return hash.equals(metadata.optString("source_sha256"));
  }

  public static String sourceId(JSONObject event) {
    JSONObject metadata = event.optJSONObject("metadata");
    return isReview(event) && metadata != null
        ? metadata.optString("source_id", "") : event.optString("id", "");
  }

  private static JSONObject annotation(JSONObject state, String id) {
    JSONObject annotations = state.optJSONObject("annotations");
    JSONObject annotation = annotations == null ? null : annotations.optJSONObject(id);
    return annotation == null ? new JSONObject() : annotation;
  }

  public static String projectId(JSONObject state, JSONObject event) {
    // Old reviews follow their source when the owner moves it to another project.
    return annotation(state, sourceId(event)).optString("project_id", "");
  }

  private static void validateProject(String projectId) throws Exception {
    if (projectId == null || (!projectId.isEmpty() && !projectId.matches("P[0-9]{1,4}")))
      throw new Exception("Use an established project ID such as P29, or leave it blank.");
  }

  /** Lexical text filtering, half-open time range, manual captures only. */
  public static List<JSONObject> records(JSONObject state, String query, long sinceInclusive,
      long untilExclusive, String projectId) throws Exception {
    if (sinceInclusive < 0 || untilExclusive <= sinceInclusive)
      throw new Exception("Invalid time range.");
    validateProject(projectId);
    String trimmed = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    if (trimmed.length() > 1000) throw new Exception("Search is limited to 1,000 characters.");
    String[] terms = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
    List<JSONObject> found = new ArrayList<>();
    JSONObject events = state.getJSONObject("events");
    Map<String, String> sourceHashes = new HashMap<>();
    for (String id : Core.ids(state)) {
      JSONObject event = events.getJSONObject(id);
      long time = event.getLong("event_ms");
      if (event.optString("source_type").startsWith("auto.") || !visible(state, event, sourceHashes)
          || time < sinceInclusive || time >= untilExclusive
          || (!projectId.isEmpty() && !projectId.equals(projectId(state, event)))) continue;
      String text = event.optString("text", "").toLowerCase(Locale.ROOT);
      boolean matches = true;
      for (String term : terms) if (!text.contains(term)) { matches = false; break; }
      if (matches) found.add(event);
    }
    found.sort(NEWEST_FIRST);
    return found;
  }

  /** Quick views are intersections of explicit filters; they never widen the project/date scope. */
  public static List<JSONObject> filteredRecords(JSONObject state, String query, long since,
      long until, String project, String kind) throws Exception {
    if (!Arrays.asList("All captures", "Inbox", "Important", "Links", "Voice", "Files").contains(kind))
      throw new Exception("Choose a supported capture filter.");
    List<JSONObject> rows = records(state, query, since, until, project);
    List<JSONObject> result = new ArrayList<>();
    for (JSONObject event : rows) {
      JSONObject a = annotation(state, sourceId(event));
      String type = event.getString("source_type");
      if (kind.equals("Inbox") && (isReview(event) || a.optBoolean("reviewed"))) continue;
      if (kind.equals("Important") && !a.optBoolean("important")) continue;
      if (kind.equals("Links") && !type.equals("link")) continue;
      if (kind.equals("Voice") && !type.equals("audio")) continue;
      if (kind.equals("Files") && !type.equals("file") && !type.equals("image")) continue;
      result.add(event);
    }
    return result;
  }

  /** Select source dependencies before their reviews, without changing any stored record. */
  public static List<String> contextIds(JSONObject state, List<String> selected) throws Exception {
    List<String> result = new ArrayList<>();
    for (String id : selected) {
      JSONObject event = state.getJSONObject("events").optJSONObject(id);
      if (!visible(state, event)) throw new Exception("A selected source is unavailable. Refresh before sharing.");
      String source = sourceId(event);
      if (isReview(event) && !result.contains(source)) result.add(source);
      if (!result.contains(id)) result.add(id);
    }
    return result;
  }

  public static void validateContextSnapshot(JSONObject state, JSONArray records) throws Exception {
    if (state.getJSONObject("policy").getBoolean("paused")) throw new Exception("Sharing is disabled while paused.");
    for (int i=0;i<records.length();i++) {
      JSONObject item=records.getJSONObject(i), event=state.getJSONObject("events").optJSONObject(item.getString("id"));
      if (!visible(state,event) || !Core.digest(event).equals(item.getString("record_sha256"))
          || !Core.digest(annotation(state,item.getString("id"))).equals(Core.digest(item.getJSONObject("annotation"))))
        throw new Exception("A selected record changed or was deleted. Refresh the context preview before sharing.");
    }
  }

  /** Newest first; these are interpretations, not replacements for source wording. */
  public static List<JSONObject> reviews(JSONObject state, String sourceId) throws Exception {
    List<JSONObject> found = new ArrayList<>();
    Map<String, String> sourceHashes = new HashMap<>();
    for (String id : Core.ids(state)) {
      JSONObject event = state.getJSONObject("events").getJSONObject(id);
      if (isReview(event) && sourceId.equals(sourceId(event)) && visible(state, event, sourceHashes))
        found.add(event);
    }
    found.sort(NEWEST_FIRST);
    return found;
  }

  public static String status(JSONObject state, String id) throws Exception {
    JSONObject event = state.getJSONObject("events").optJSONObject(id);
    if (!visible(state, event)) return "unavailable";
    if (isReview(event)) return event.getJSONObject("metadata").getString("epistemic_status");
    List<JSONObject> reviews = reviews(state, id);
    return reviews.isEmpty() ? "unreviewed"
        : reviews.get(0).getJSONObject("metadata").getString("epistemic_status");
  }

  public static String label(JSONObject state, JSONObject event) throws Exception {
    return isReview(event)
        ? "Owner interpretation · " + status(state, event.getString("id")).replace('_', ' ')
        : "Original source · " + status(state, event.getString("id")).replace('_', ' ');
  }

  /**
   * Appends an immutable review with exact supplied interpretation and source hash.
   * Existing reviews remain intact; the latest review determines current classification.
   * Selecting canonical is a classification only; no source content is rewritten.
   */
  public static JSONObject review(JSONObject state, String id, String projectId,
      String epistemicStatus, boolean important, String interpretation, long now)
      throws Exception {
    validateProject(projectId);
    if (!STATUSES.contains(epistemicStatus)) throw new Exception("Choose a supported review status.");
    if (interpretation == null || interpretation.trim().isEmpty())
      throw new Exception("Write what this review means before saving it.");
    if (Core.utf8(interpretation).length > MAX_REVIEW_BYTES || interpretation.indexOf('\0') >= 0)
      throw new Exception("Review text is limited to 8,000 UTF-8 bytes and cannot contain NUL.");
    UUID.fromString(id);
    JSONObject selected = state.getJSONObject("events").optJSONObject(id);
    if (!visible(state, selected)) throw new Exception("The source is unavailable or has been deleted.");
    JSONObject source = state.getJSONObject("events").getJSONObject(sourceId(selected));
    if (source.optString("source_type").startsWith("auto."))
      throw new Exception("Review a deliberately captured source, not automatic metadata.");
    String originalId = source.getString("id");
    JSONObject originalAnnotation = annotation(state, originalId);
    JSONObject updated = Core.copy(originalAnnotation);
    updated.put("project_id", projectId);
    updated.put("important", important);
    updated.put("reviewed", true);
    Core.validateAnnotation(updated);
    int nextSequence = 1;
    for (JSONObject previous : reviews(state, originalId))
      nextSequence = Math.max(nextSequence, sequence(previous) + 1);
    JSONObject event = Core.event(state, "note", "user-review", interpretation, now, now,
        "memory-workbench", null, new JSONArray(), Core.obj(
            "source_id", originalId,
            "source_sha256", Core.digest(source),
            "review_role", "user_interpretation",
            "epistemic_status", epistemicStatus,
            "review_sequence", nextSequence,
            "original_unchanged", true));
    // Core.add validates capture policy/capacity before organization is changed.
    Core.add(state, event, new JSONObject());
    state.getJSONObject("annotations").put(originalId, updated);
    state.getJSONObject("annotations").put(event.getString("id"), Core.obj(
        "project_id", projectId, "important", important, "reviewed", true));
    return event;
  }

  /**
   * One card per explicitly assigned project. Intentions, decisions and blockers
   * are drawn only from the latest owner review of each source. Missing values
   * mean no reviewed statement is available, not that no blocker exists.
   */
  public static JSONArray desk(JSONObject state) throws Exception {
    List<JSONObject> visibleRecords = records(state, "", 0, Long.MAX_VALUE, "");
    Map<String, JSONObject> latestReviews = new HashMap<>();
    for (JSONObject event : visibleRecords) {
      if (isReview(event) && !latestReviews.containsKey(sourceId(event)))
        latestReviews.put(sourceId(event), event);
    }
    Map<String, JSONObject> projects = new LinkedHashMap<>();
    for (JSONObject source : visibleRecords) {
      if (isReview(source)) continue;
      String project = projectId(state, source);
      if (project.isEmpty()) continue;
      JSONObject card = projects.get(project);
      if (card == null) {
        card = Core.obj("project_id", project, "latest_source_id", source.getString("id"),
            "latest_ms", source.getLong("event_ms"), "last_activity_ms", source.getLong("event_ms"),
            "record_count", 0, "decision_id", "", "next_step_id", "", "blocker_id", "");
        projects.put(project, card);
      }
      card.put("record_count", card.getInt("record_count") + 1);
      JSONObject review = latestReviews.get(source.getString("id"));
      if (review == null) continue;
      card.put("last_activity_ms", Math.max(card.getLong("last_activity_ms"), review.getLong("event_ms")));
      String kind = review.getJSONObject("metadata").getString("epistemic_status");
      if (!Arrays.asList("decision", "next_step", "blocker").contains(kind)) continue;
      String field = kind + "_id";
      JSONObject previous = state.getJSONObject("events").optJSONObject(card.optString(field));
      if (previous == null || NEWEST_FIRST.compare(review, previous) < 0)
        card.put(field, review.getString("id"));
    }
    List<JSONObject> ordered = new ArrayList<>(projects.values());
    ordered.sort((a, b) -> {
      int time = Long.compare(b.optLong("last_activity_ms"), a.optLong("last_activity_ms"));
      return time != 0 ? time : a.optString("project_id").compareTo(b.optString("project_id"));
    });
    JSONArray result = new JSONArray();
    for (JSONObject project : ordered) result.put(project);
    return result;
  }

  /** Local deletion includes the selected source and its linked interpretations. */
  public static void erase(JSONObject state, String id) throws Exception {
    JSONObject selected = state.getJSONObject("events").optJSONObject(id);
    if (selected == null) throw new Exception("Record is already unavailable.");
    // Deleting an individual review keeps its source; deleting a source removes all its reviews.
    if (!isReview(selected)) {
      List<String> linked = new ArrayList<>();
      for (String candidate : Core.ids(state)) {
        JSONObject event = state.getJSONObject("events").getJSONObject(candidate);
        if (isReview(event) && id.equals(sourceId(event))) linked.add(candidate);
      }
      for (String candidate : linked) Core.erase(state, candidate);
    }
    Core.erase(state, id);
    Core.gc(state);
  }

  /** For range deletion/import cleanup; never resurrect orphaned interpretation text. */
  public static void pruneDeletedReviews(JSONObject state) throws Exception {
    List<String> remove = new ArrayList<>();
    for (String id : Core.ids(state)) {
      JSONObject event = state.getJSONObject("events").getJSONObject(id);
      if (!isReview(event)) continue;
      JSONObject source = state.getJSONObject("events").optJSONObject(sourceId(event));
      if (source == null || Core.deleted(state, source)) remove.add(id);
    }
    for (String id : remove) Core.erase(state, id);
    Core.gc(state);
  }
}
