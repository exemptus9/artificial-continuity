package org.continuity.recall;

import org.json.*;

public class DurableTextTests {
  interface Check { void run() throws Exception; }
  static int passed;
  static final long NOW = 1792965000000L;
  static void yes(boolean condition) throws Exception {
    if (!condition) throw new Exception("Assertion failed");
  }
  static void test(String name, Check check) throws Exception {
    check.run(); passed++; System.out.println("PASS " + name);
  }
  static void error(JSONObject state, String part) throws Exception {
    try { DurableTextTest.verify(state); }
    catch (Exception e) { yes(e.getMessage().contains(part)); return; }
    throw new Exception("Expected failure: " + part);
  }
  static JSONObject save(JSONObject state, String text, boolean bind) throws Exception {
    JSONObject e = Core.event(state, "note", "synthetic-host-test", text, NOW, NOW,
        "synthetic", null, new JSONArray(), new JSONObject());
    Core.add(state, e, new JSONObject());
    if (bind) DurableTextTest.saved(state, e);
    return e;
  }
  public static void main(String[] args) throws Exception {
    test("no prepared test explains the next step", () -> error(Core.initial(NOW), "Prepare"));
    test("draft is not a saved record", () -> {
      JSONObject s = Core.initial(NOW);
      s.put("draft", DurableTextTest.prepare(s, "draft-only"));
      error(s, "still a draft");
    });
    test("exact Unicode text survives JSON reload and binds stable ID", () -> {
      JSONObject s = Core.initial(NOW);
      String text = DurableTextTest.prepare(s, "round-trip");
      JSONObject e = save(s, text, true);
      JSONObject disk = new JSONObject(s.toString());
      yes(DurableTextTest.verify(disk).getString("id").equals(e.getString("id")));
      yes(DurableTextTest.verify(disk).getString("text").equals(text));
    });
    test("old 0.7.0 exact record verifies despite another nonce mention", () -> {
      JSONObject s = Core.initial(NOW);
      String text = DurableTextTest.prepare(s, "legacy-unique");
      JSONObject e = save(s, text, false);
      save(s, "A separate note about legacy-unique", false);
      yes(DurableTextTest.verify(s).getString("id").equals(e.getString("id")));
    });
    test("legitimate duplicate captures stay separate and first ID is stable", () -> {
      JSONObject s = Core.initial(NOW);
      String text = DurableTextTest.prepare(s, "duplicate");
      JSONObject first = save(s, text, true), second = save(s, text, true);
      yes(!first.getString("id").equals(second.getString("id")));
      yes(s.getJSONObject("events").length() == 2);
      yes(DurableTextTest.verify(s).getString("id").equals(first.getString("id")));
    });
    test("edited test phrase gets actionable failure without changing note", () -> {
      JSONObject s = Core.initial(NOW);
      String text = DurableTextTest.prepare(s, "edited");
      JSONObject e = save(s, text + "edited", true);
      error(s, "without edits");
      yes(s.getJSONObject("events").getJSONObject(e.getString("id")).getString("text")
          .equals(text + "edited"));
    });
    test("new preparation clears stale PASS and binding but preserves saved original", () -> {
      JSONObject s = Core.initial(NOW);
      JSONObject original = save(s, DurableTextTest.prepare(s, "first"), true);
      s.getJSONObject("wizard").put("durable-text", "PASS");
      DurableTextTest.prepare(s, "second");
      yes(!s.getJSONObject("wizard").has("durable-text"));
      yes(!s.getJSONObject("wizard").has("durable_record_id"));
      yes(s.getJSONObject("events").has(original.getString("id")));
      error(s, "latest prepared");
    });
    test("deleted bound record cannot be substituted with identical text", () -> {
      JSONObject s = Core.initial(NOW);
      String text = DurableTextTest.prepare(s, "deleted");
      JSONObject original = save(s, text, true);
      save(s, text, true);
      s.getJSONObject("events").remove(original.getString("id"));
      error(s, "no longer");
    });
    test("text corruption fails even if stored hash still matches", () -> {
      JSONObject s = Core.initial(NOW);
      JSONObject e = save(s, DurableTextTest.prepare(s, "corrupt-text"), true);
      s.getJSONObject("events").getJSONObject(e.getString("id")).put("text", "changed");
      error(s, "hash comparison");
    });
    test("hash corruption fails even if text still matches", () -> {
      JSONObject s = Core.initial(NOW);
      JSONObject e = save(s, DurableTextTest.prepare(s, "corrupt-hash"), true);
      s.getJSONObject("events").getJSONObject(e.getString("id")).put("text_sha256", "bad");
      error(s, "hash comparison");
    });
    System.out.println(passed + " durable-text checks passed");
  }
}
