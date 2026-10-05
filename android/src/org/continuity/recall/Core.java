package org.continuity.recall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.json.*;

/** Shared platform-independent archive rules. No I/O, network, or interpretation. */
public final class Core {
  public static final String STORE = "TotalRecallStore/1",
      EVENT = "TotalRecallEvent/1",
      ARCHIVE = "TotalRecallArchive/1";
  public static final int MAX_TEXT = 500000,
      MAX_BLOB = 5 * 1024 * 1024,
      MAX_TOTAL = 20 * 1024 * 1024,
      MAX_EVENTS = 3000,
      MAX_ARCHIVE = 32 * 1024 * 1024;

  public static JSONObject obj(Object... kv) throws Exception {
    JSONObject o = new JSONObject();
    for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
    return o;
  }

  public static String canonical(Object o) throws Exception {
    if (o == null || o == JSONObject.NULL) return "null";
    if (o instanceof JSONObject) {
      JSONObject j = (JSONObject) o;
      List<String> keys = new ArrayList<>();
      Iterator<String> it = j.keys();
      while (it.hasNext()) keys.add(it.next());
      Collections.sort(keys);
      StringBuilder b = new StringBuilder("{");
      for (String k : keys) {
        if (b.length() > 1) b.append(',');
        b.append(quote(k)).append(':').append(canonical(j.get(k)));
      }
      return b.append('}').toString();
    }
    if (o instanceof JSONArray) {
      JSONArray a = (JSONArray) o;
      StringBuilder b = new StringBuilder("[");
      for (int i = 0; i < a.length(); i++) {
        if (i > 0) b.append(',');
        b.append(canonical(a.get(i)));
      }
      return b.append(']').toString();
    }
    if (o instanceof String) return quote((String) o);
    if (o instanceof Boolean || o instanceof Integer || o instanceof Long) return o.toString();
    throw new Exception("Unsupported JSON value (only integral numbers supported)");
  }

  public static String quote(String text) {
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"':
          b.append("\\\"");
          break;
        case '\\':
          b.append("\\\\");
          break;
        case '\b':
          b.append("\\b");
          break;
        case '\f':
          b.append("\\f");
          break;
        case '\n':
          b.append("\\n");
          break;
        case '\r':
          b.append("\\r");
          break;
        case '\t':
          b.append("\\t");
          break;
        default:
          if (c < 32) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          else b.append(c);
      }
    }
    return b.append('"').toString();
  }

  public static byte[] utf8(String s) throws Exception {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i >= s.length() || !Character.isLowSurrogate(s.charAt(i)))
          throw new Exception("Unpaired Unicode surrogate");
      } else if (Character.isLowSurrogate(c)) throw new Exception("Unpaired Unicode surrogate");
    }
    return s.getBytes(StandardCharsets.UTF_8);
  }

  public static String decodeUtf8(byte[] raw) throws Exception {
    return java.nio.charset.StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(raw))
        .toString();
  }

  public static String hash(byte[] raw) throws Exception {
    byte[] b = MessageDigest.getInstance("SHA-256").digest(raw);
    StringBuilder s = new StringBuilder();
    for (byte v : b) s.append(String.format(Locale.ROOT, "%02x", v & 255));
    return s.toString();
  }

  public static String digest(Object o) throws Exception {
    return hash(utf8(canonical(o)));
  }

  public static JSONObject copy(JSONObject o) throws Exception {
    return new JSONObject(o.toString());
  }

  public static JSONArray copyArray(JSONArray a) throws Exception {
    return new JSONArray(a.toString());
  }

  public static String iso(long ms) {
    return Instant.ofEpochMilli(ms).toString();
  }

  public static JSONObject initial(long now) throws Exception {
    return obj(
        "format",
        STORE,
        "device_id",
        UUID.randomUUID().toString(),
        "created_ms",
        now,
        "events",
        new JSONObject(),
        "annotations",
        new JSONObject(),
        "blobs",
        new JSONObject(),
        "deleted_ids",
        new JSONArray(),
        "delete_ranges",
        new JSONArray(),
        "outbox",
        new JSONObject(),
        "draft",
        "",
        "wizard",
        new JSONObject(),
        "policy",
        obj(
            "version",
            1,
            "paused",
            false,
            "usage",
            false,
            "notifications",
            false,
            "usage_allow",
            new JSONObject(),
            "notification_allow",
            new JSONObject(),
            "exclusions",
            new JSONArray(),
            "usage_cursor",
            now,
            "usage_observed",
            0L,
            "notification_observed",
            0L,
            "usage_since",
            now,
            "notification_since",
            now,
            "usage_status",
            "Off",
            "notification_status",
            "Off",
            "gaps",
            new JSONArray(),
            "retention_days",
            7));
  }

  public static JSONObject event(
      JSONObject state,
      String type,
      String method,
      String text,
      long when,
      long observed,
      String app,
      String identity,
      JSONArray attachments,
      JSONObject metadata)
      throws Exception {
    String id =
        identity == null
            ? UUID.randomUUID().toString()
            : UUID.nameUUIDFromBytes(utf8(state.getString("device_id") + ":" + identity))
                .toString();
    ZoneId zone = ZoneId.systemDefault();
    JSONObject e =
        obj(
            "schema",
            EVENT,
            "id",
            id,
            "device_id",
            state.getString("device_id"),
            "source_type",
            type,
            "capture_method",
            method,
            "event_ms",
            when,
            "observed_ms",
            observed,
            "ingested_ms",
            observed,
            "event_at",
            iso(when),
            "observed_at",
            iso(observed),
            "ingested_at",
            iso(observed),
            "timezone",
            zone.getId(),
            "offset_seconds",
            zone.getRules().getOffset(Instant.ofEpochMilli(when)).getTotalSeconds(),
            "privacy_scope",
            "private",
            "consent_policy_version",
            state.getJSONObject("policy").getInt("version"),
            "evidence",
            type.startsWith("auto.") ? "OS_OBSERVATION" : "USER_SUPPLIED",
            "app",
            app,
            "text",
            text,
            "text_sha256",
            hash(utf8(text)),
            "attachments",
            attachments,
            "metadata",
            metadata,
            "integrity_boundary",
            "Received Unicode string encoded UTF-8 without normalization; attachments are received"
                + " original bytes. Does not verify upstream transformations or reality.");
    validateEvent(e);
    return e;
  }

  public static void validateEvent(JSONObject e) throws Exception {
    Set<String> fields =
        new HashSet<>(
            Arrays.asList(
                "schema",
                "id",
                "device_id",
                "source_type",
                "capture_method",
                "event_ms",
                "observed_ms",
                "ingested_ms",
                "event_at",
                "observed_at",
                "ingested_at",
                "timezone",
                "offset_seconds",
                "privacy_scope",
                "consent_policy_version",
                "evidence",
                "app",
                "text",
                "text_sha256",
                "attachments",
                "metadata",
                "integrity_boundary"));
    Iterator<String> names = e.keys();
    while (names.hasNext())
      if (!fields.contains(names.next())) throw new Exception("Unknown event field");
    if (e.length() != fields.size()) throw new Exception("Missing event field");
    if (!EVENT.equals(e.getString("schema"))) throw new Exception("Unsupported event schema");
    if (!UUID.fromString(e.getString("id")).toString().equals(e.getString("id")))
      throw new Exception("Invalid event ID");
    UUID.fromString(e.getString("device_id"));
    String type = e.getString("source_type");
    if (!Arrays.asList("note", "link", "file", "image", "audio", "auto.usage", "auto.notification")
        .contains(type)) throw new Exception("Unknown source type");
    if (!"private".equals(e.getString("privacy_scope")))
      throw new Exception("Only private events supported");
    byte[] raw = utf8(e.getString("text"));
    if (raw.length > MAX_TEXT
        || e.getString("text").indexOf('\0') >= 0
        || !hash(raw).equals(e.getString("text_sha256")))
      throw new Exception("Invalid text or text hash");
    if (e.getString("app").length() > 200 || e.getString("capture_method").length() > 100)
      throw new Exception("Metadata limit");
    long t = e.getLong("event_ms"), o = e.getLong("observed_ms");
    if (t < 0
        || o < 0
        || !iso(t).equals(e.getString("event_at"))
        || !iso(o).equals(e.getString("observed_at"))) throw new Exception("Invalid time");
    ZoneId.of(e.getString("timezone"));
    if (Math.abs(e.getInt("offset_seconds")) > 64800) throw new Exception("Invalid UTC offset");
    JSONArray at = e.getJSONArray("attachments");
    if (at.length() > 4) throw new Exception("Attachment limit");
    for (int i = 0; i < at.length(); i++) {
      JSONObject a = at.getJSONObject(i);
      if (!a.getString("sha256").matches("[a-f0-9]{64}")
          || a.getLong("bytes") < 0
          || a.getLong("bytes") > MAX_BLOB
          || a.getString("display_name").length() > 300
          || a.getString("mime").length() > 150) throw new Exception("Invalid attachment");
    }
    if (type.startsWith("auto.") && (!e.getString("text").isEmpty() || at.length() != 0))
      throw new Exception("Automatic collection is metadata only");
    if (utf8(canonical(e.getJSONObject("metadata"))).length > 8000)
      throw new Exception("Metadata limit");
    String revision = e.getJSONObject("metadata").optString("revision_of", "");
    if (!revision.isEmpty()) {
      UUID.fromString(revision);
      if (revision.equals(e.getString("id"))) throw new Exception("Self-referencing revision");
    }
    if (type.startsWith("auto.")) {
      Set<String> metaFields =
          new HashSet<>(
              Arrays.asList(
                  type.equals("auto.usage")
                      ? new String[] {
                        "transition", "event_type", "instance", "source_identity_sha256"
                      }
                      : new String[] {
                        "notification_identity_sha256",
                        "transition",
                        "grouped",
                        "group_summary",
                        "post_time_ms",
                        "removed_reason",
                        "content_status"
                      }));
      Iterator<String> it = e.getJSONObject("metadata").keys();
      while (it.hasNext())
        if (!metaFields.contains(it.next()))
          throw new Exception("Prohibited automatic metadata field");
    }
  }

  public static boolean inRanges(JSONArray a, long t) throws Exception {
    for (int i = 0; i < a.length(); i++) {
      JSONObject r = a.getJSONObject(i);
      if (t >= r.getLong("start_ms") && t < r.optLong("end_ms", Long.MAX_VALUE)) return true;
    }
    return false;
  }

  public static boolean contains(JSONArray a, String id) {
    for (int i = 0; i < a.length(); i++) if (id.equals(a.optString(i))) return true;
    return false;
  }

  public static boolean deleted(JSONObject s, JSONObject e) throws Exception {
    return contains(s.getJSONArray("deleted_ids"), e.getString("id"))
        || inRanges(s.getJSONArray("delete_ranges"), e.getLong("event_ms"));
  }

  public static boolean allowed(JSONObject s, JSONObject e) throws Exception {
    JSONObject p = s.getJSONObject("policy");
    if (p.getBoolean("paused") || deleted(s, e)) return false;
    String type = e.getString("source_type");
    if (!type.startsWith("auto.")) return true;
    boolean usage = type.equals("auto.usage");
    if (!p.getBoolean(usage ? "usage" : "notifications")) return false;
    JSONObject allow = p.getJSONObject(usage ? "usage_allow" : "notification_allow");
    String app = e.getString("app");
    long t = e.getLong("event_ms");
    return allow.has(app)
        && t >= allow.getLong(app)
        && t >= p.optLong(usage ? "usage_since" : "notification_since", 0)
        && !inRanges(p.getJSONArray("exclusions"), t);
  }

  public static boolean add(JSONObject s, JSONObject e, JSONObject newBlobs) throws Exception {
    validateEvent(e);
    if (!allowed(s, e)) throw new Exception("Capture paused, disabled, excluded, or deleted");
    JSONObject events = s.getJSONObject("events");
    String id = e.getString("id");
    if (events.has(id)) {
      if (!digest(events.getJSONObject(id)).equals(digest(e)))
        throw new Exception("Event identity conflict");
      return false;
    }
    if (events.length() >= MAX_EVENTS)
      throw new Exception("Record limit reached; export and review retention");
    JSONObject blobs = s.getJSONObject("blobs");
    JSONArray at = e.getJSONArray("attachments");
    long total = 0;
    Iterator<String> ks = blobs.keys();
    while (ks.hasNext()) total += Base64.getDecoder().decode(blobs.getString(ks.next())).length;
    for (int i = 0; i < at.length(); i++) {
      JSONObject a = at.getJSONObject(i);
      String h = a.getString("sha256");
      String b64 = blobs.has(h) ? blobs.getString(h) : newBlobs.getString(h);
      byte[] bytes = Base64.getDecoder().decode(b64);
      if (bytes.length != a.getLong("bytes") || !hash(bytes).equals(h))
        throw new Exception("Attachment hash/size mismatch");
      if (!blobs.has(h)) total += bytes.length;
    }
    if (total > MAX_TOTAL)
      throw new Exception("20 MiB attachment capacity reached; export before removing data");
    for (int i = 0; i < at.length(); i++) {
      String h = at.getJSONObject(i).getString("sha256");
      if (!blobs.has(h)) blobs.put(h, newBlobs.getString(h));
    }
    events.put(id, e);
    s.getJSONObject("annotations")
        .put(id, obj("project_id", "", "important", false, "reviewed", false));
    s.getJSONObject("outbox").put(id, "pending-manual-transfer");
    return true;
  }

  public static void pause(JSONObject s, boolean value, long now) throws Exception {
    JSONObject p = s.getJSONObject("policy");
    if (value == p.getBoolean("paused")) return;
    if (value) p.put("pause_start", now);
    else {
      p.getJSONArray("exclusions").put(obj("start_ms", p.getLong("pause_start"), "end_ms", now));
      p.remove("pause_start");
    }
    p.put("paused", value);
    if (!value) {
      p.put("usage_since", now);
      p.put("notification_since", now);
    }
    p.put("usage_cursor", now);
    p.put("version", p.getInt("version") + 1);
  }

  public static void forget(JSONObject s, long start, long end) throws Exception {
    if (start < 0 || end <= start) throw new Exception("Invalid deletion range");
    s.getJSONArray("delete_ranges").put(obj("start_ms", start, "end_ms", end));
    JSONObject ev = s.getJSONObject("events");
    List<String> ids = new ArrayList<>();
    Iterator<String> it = ev.keys();
    while (it.hasNext()) {
      String id = it.next();
      if (deleted(s, ev.getJSONObject(id))) ids.add(id);
    }
    for (String id : ids) erase(s, id);
    gc(s);
    s.put("draft", "");
    s.put("wizard", new JSONObject());
  }

  public static void erase(JSONObject s, String id) throws Exception {
    if (!contains(s.getJSONArray("deleted_ids"), id)) s.getJSONArray("deleted_ids").put(id);
    s.getJSONObject("events").remove(id);
    s.getJSONObject("annotations").remove(id);
    s.getJSONObject("outbox").remove(id);
  }

  public static void gc(JSONObject s) throws Exception {
    Set<String> used = new HashSet<>();
    Iterator<String> it = s.getJSONObject("events").keys();
    while (it.hasNext()) {
      JSONArray a = s.getJSONObject("events").getJSONObject(it.next()).getJSONArray("attachments");
      for (int i = 0; i < a.length(); i++) used.add(a.getJSONObject(i).getString("sha256"));
    }
    JSONObject b = s.getJSONObject("blobs");
    List<String> remove = new ArrayList<>();
    it = b.keys();
    while (it.hasNext()) {
      String h = it.next();
      if (!used.contains(h)) remove.add(h);
    }
    for (String h : remove) b.remove(h);
  }

  public static JSONObject exportArchive(JSONObject s, List<String> selected, long now)
      throws Exception {
    if (s.getJSONObject("policy").getBoolean("paused"))
      throw new Exception("Resume before exporting or sharing");
    JSONArray records = new JSONArray();
    JSONObject blobs = new JSONObject();
    for (String id : selected) {
      JSONObject e = s.getJSONObject("events").getJSONObject(id);
      records.put(
          obj(
              "record",
              e,
              "record_sha256",
              digest(e),
              "annotation",
              s.getJSONObject("annotations").optJSONObject(id)));
      JSONArray a = e.getJSONArray("attachments");
      for (int i = 0; i < a.length(); i++) {
        String h = a.getJSONObject(i).getString("sha256");
        blobs.put(h, s.getJSONObject("blobs").getString(h));
      }
    }
    JSONObject body =
        obj(
            "format",
            ARCHIVE,
            "exported_ms",
            now,
            "device_id",
            s.getString("device_id"),
            "records",
            records,
            "blobs",
            blobs,
            "deleted_ids",
            s.getJSONArray("deleted_ids"),
            "delete_ranges",
            s.getJSONArray("delete_ranges"),
            "exclusions",
            s.getJSONObject("policy").getJSONArray("exclusions"),
            "privacy",
            "private",
            "automatic_consent_imported",
            false);
    body = copy(body);
    return obj("format", "TotalRecallBundle/1", "payload", body, "payload_sha256", digest(body));
  }

  public static List<String> ids(JSONObject s) throws Exception {
    List<String> result = new ArrayList<>();
    Iterator<String> it = s.getJSONObject("events").keys();
    while (it.hasNext()) result.add(it.next());
    return result;
  }

  /** Isolated merge: never changes input, never imports capture permissions, deletion wins. */
  public static JSONObject restore(JSONObject current, JSONObject bundle) throws Exception {
    if (!"TotalRecallBundle/1".equals(bundle.getString("format")))
      throw new Exception("Unsupported bundle");
    JSONObject b = bundle.getJSONObject("payload");
    if (!ARCHIVE.equals(b.getString("format"))
        || !digest(b).equals(bundle.getString("payload_sha256")))
      throw new Exception("Archive integrity failure");
    if (utf8(bundle.toString()).length > MAX_ARCHIVE) throw new Exception("Archive too large");
    JSONArray rows = b.getJSONArray("records");
    if (rows.length() > MAX_EVENTS) throw new Exception("Record limit");
    JSONObject next = copy(current);
    JSONArray deleted = b.getJSONArray("deleted_ids"), ranges = b.getJSONArray("delete_ranges");
    if (deleted.length() > 20000 || ranges.length() > 2000)
      throw new Exception("Deletion policy limit");
    for (int i = 0; i < deleted.length(); i++) {
      String id = deleted.getString(i);
      UUID.fromString(id);
      if (!contains(next.getJSONArray("deleted_ids"), id)) next.getJSONArray("deleted_ids").put(id);
    }
    for (int i = 0; i < ranges.length(); i++) {
      JSONObject r = ranges.getJSONObject(i);
      if (r.getLong("start_ms") < 0 || r.getLong("end_ms") <= r.getLong("start_ms"))
        throw new Exception("Invalid deletion range");
      boolean exists = false;
      JSONArray nr = next.getJSONArray("delete_ranges");
      for (int k = 0; k < nr.length(); k++)
        if (digest(nr.getJSONObject(k)).equals(digest(r))) exists = true;
      if (!exists) nr.put(r);
    }
    JSONObject blobs = b.getJSONObject("blobs");
    Iterator<String> bi = blobs.keys();
    long total = 0;
    while (bi.hasNext()) {
      String h = bi.next();
      if (!h.matches("[a-f0-9]{64}")) throw new Exception("Unsafe attachment identity");
      byte[] raw = Base64.getDecoder().decode(blobs.getString(h));
      total += raw.length;
      if (raw.length > MAX_BLOB || total > MAX_TOTAL || !hash(raw).equals(h))
        throw new Exception("Invalid attachment bytes");
    }
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i), e = row.getJSONObject("record");
      validateEvent(e);
      String id = e.getString("id");
      if (!seen.add(id) || !digest(e).equals(row.getString("record_sha256")))
        throw new Exception("Duplicate ID or record hash mismatch");
      JSONArray at = e.getJSONArray("attachments");
      for (int k = 0; k < at.length(); k++) {
        JSONObject a = at.getJSONObject(k);
        byte[] raw = Base64.getDecoder().decode(blobs.getString(a.getString("sha256")));
        if (raw.length != a.getLong("bytes")) throw new Exception("Attachment length mismatch");
      }
      if (deleted(next, e)) continue;
      JSONObject events = next.getJSONObject("events");
      if (events.has(id)) {
        if (!digest(events.getJSONObject(id)).equals(digest(e)))
          throw new Exception("Existing ID conflict");
        continue;
      }
      events.put(id, e);
      JSONObject annotation = row.optJSONObject("annotation");
      if (annotation == null)
        annotation = obj("project_id", "", "reviewed", false, "important", false);
      validateAnnotation(annotation);
      next.getJSONObject("annotations").put(id, annotation);
      next.getJSONObject("outbox").put(id, "pending-manual-transfer");
      for (int k = 0; k < at.length(); k++) {
        String h = at.getJSONObject(k).getString("sha256");
        next.getJSONObject("blobs").put(h, blobs.getString(h));
      }
    }
    for (String id : ids(next))
      if (deleted(next, next.getJSONObject("events").getJSONObject(id))) erase(next, id);
    if (next.getJSONObject("events").length() > MAX_EVENTS)
      throw new Exception("Merged record limit");
    gc(next);
    total = 0;
    bi = next.getJSONObject("blobs").keys();
    while (bi.hasNext())
      total += Base64.getDecoder().decode(next.getJSONObject("blobs").getString(bi.next())).length;
    if (total > MAX_TOTAL) throw new Exception("Merged attachment limit");
    return next;
  }

  public static void validateAnnotation(JSONObject a) throws Exception {
    String p = a.getString("project_id");
    if (!p.isEmpty() && !p.matches("P[0-9]{1,4}"))
      throw new Exception("Use an established project ID such as P29");
    a.getBoolean("important");
    a.getBoolean("reviewed");
  }
}
