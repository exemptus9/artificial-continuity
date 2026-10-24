package org.continuity.recall;

import java.time.LocalDate;
import java.time.ZoneId;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TemporalEngineTests {
  static int passed = 0;
  static void check(boolean ok, String label) throws Exception {
    if (!ok) throw new Exception("FAIL: " + label);
    System.out.println("PASS " + label);
    passed++;
  }
  static JSONObject event(JSONObject s, String text, long time) throws Exception {
    JSONObject e = Core.event(s, "note", "synthetic", text, time, time,
        "synthetic", null, new JSONArray(), new JSONObject());
    Core.add(s, e, new JSONObject());
    return e;
  }
  public static void main(String[] args) throws Exception {
    long now = System.currentTimeMillis();
    JSONObject state = Core.initial(now);
    check(!TemporalEngine.explicitCapture("what do you remember about it"), "recall not auto-save");
    check(TemporalEngine.explicitCapture("Remember the broken glove latch"), "capture intent");
    check(!TemporalEngine.interpret(state, "What did I say about glove?", now, false).saveUtterance,
        "queries private by default");
    check(TemporalEngine.interpret(state, "Remember the compartment latch", now, false).saveUtterance,
        "explicit capture saved by caller");
    JSONObject original = TemporalEngine.save(state, "Remember glove compartment latch sticks",
        now - 1000);
    event(state, "Mosaic poetry draft on Tuesday", now - 500);
    event(state, "Glove latch isn't releasing", now);
    TemporalEngine.Reply recalled = TemporalEngine.interpret(state,
        "What did I say about glove latch?", now, false);
    check(recalled.sourceIds.size() == 2, "retrieve two provenance sources");
    check(recalled.text.contains("You recorded"), "retrieval explicitly attributed");
    check(!recalled.text.contains("poetry"), "irrelevant data filtered");
    check(recalled.sourceIds.contains(original.getString("id")), "stable record ID retained");
    check(TemporalEngine.interpret(state, "Last note", now, false).sourceIds.size() == 1,
        "latest note with record ID");
    String date = LocalDate.now(ZoneId.systemDefault()).toString();
    check(TemporalEngine.daySummary(state, date, false).sourceIds.size() == 3,
        "date summary cites sources");
    check(TemporalEngine.lookup(state, "unseen elephant", false).sourceIds.isEmpty(),
        "no fabricated matches");
    JSONObject auto = Core.event(state, "auto.usage", "synthetic", "", now, now,
        "synthetic", "usage:" + now, new JSONArray(), Core.obj("transition", "resumed",
        "event_type", 1, "instance", 0, "source_identity_sha256",
        Core.hash(Core.utf8("test"))));
    check(!TemporalEngine.manualText(auto), "automatic observation not claimed as words");
    Core.pause(state, true, now + 2000);
    check(!TemporalEngine.interpret(state, "remember secret", now, false).saveUtterance,
        "pause overrides voice capture");
    boolean threw = false;
    try { TemporalEngine.save(state, "secret", now + 3000); }
    catch (Exception ex) { threw = true; }
    check(threw, "paused vault rejects persistence");
    check(TemporalEngine.interpret(Core.initial(now), "unrecognized", now, false)
        .text.contains("offline build"), "no fake LLM connection");
    System.out.println("Temporal engine checks: " + passed);
  }
}
