package org.continuity.recall;

import java.time.*;
import java.util.*;
import org.json.*;

/** Synthetic regression tests for the in-place 0.8 iteration. */
public final class IterationTests {
  static int count;
  static void yes(String name, boolean value) throws Exception {
    if (!value) throw new Exception(name);count++;System.out.println("PASS " + name);
  }
  static JSONObject add(JSONObject s, String type, String text, long now) throws Exception {
    JSONObject e=Core.event(s,type,"synthetic-test",text,now,now,"synthetic",null,new JSONArray(),new JSONObject());
    Core.add(s,e,new JSONObject());return e;
  }
  public static void main(String[] args) throws Exception {
    long now=Instant.parse("2026-10-25T21:00:00Z").toEpochMilli();ZoneId zone=ZoneId.of("America/New_York");
    JSONObject s=Core.initial(now);
    JSONObject note=add(s,"note","SYNTHETIC lighthouse\n\npoem e\u0301 / é / 🧠",now-10000);
    JSONObject link=add(s,"link","https://example.invalid/poem",now-9000);
    JSONObject file=add(s,"file","selected document",now-8000);
    JSONObject old=add(s,"note","yesterday lighthouse",now-86400000L);
    String id=note.getString("id"),originalHash=Core.digest(note);
    s.getJSONObject("annotations").getJSONObject(id).put("project_id","P29").put("important",true);
    yes("Inbox shows untouched captures",MemoryWorkbench.filteredRecords(s,"",0,Long.MAX_VALUE,"","Inbox").size()==4);
    yes("Links view selects only links",MemoryWorkbench.filteredRecords(s,"",0,Long.MAX_VALUE,"","Links").get(0).getString("id").equals(link.getString("id")));
    yes("Files view keeps selected file",MemoryWorkbench.filteredRecords(s,"",0,Long.MAX_VALUE,"","Files").get(0).getString("id").equals(file.getString("id")));
    yes("Important filter intersects project scope",MemoryWorkbench.filteredRecords(s,"",0,Long.MAX_VALUE,"P30","Important").isEmpty());
    yes("Date filter excludes yesterday",MemoryWorkbench.filteredRecords(s,"lighthouse",now-20000,now+1,"P29","Important").size()==1);
    JSONObject review=MemoryWorkbench.review(s,id,"P29","next_step",true,"Draft the final stanza",now);
    yes("Review removes source from unreviewed inbox",MemoryWorkbench.filteredRecords(s,"",0,Long.MAX_VALUE,"P29","Inbox").isEmpty());
    JSONArray threads=MemoryWorkbench.desk(s);
    yes("Thread next step points to a real owner review",threads.getJSONObject(0).getString("next_step_id").equals(review.getString("id")));
    yes("Review leaves original content and metadata exact",Core.digest(s.getJSONObject("events").getJSONObject(id)).equals(originalHash));
    List<String> expanded=MemoryWorkbench.contextIds(s,Arrays.asList(review.getString("id"),id));
    yes("Context puts original before interpretation and deduplicates dependencies",expanded.size()==2&&expanded.get(0).equals(id));
    JSONArray snapshot=new JSONArray().put(Core.obj("id",id,"record_sha256",Core.digest(note),
        "annotation",Core.copy(s.getJSONObject("annotations").getJSONObject(id))));
    MemoryWorkbench.validateContextSnapshot(s,snapshot);
    yes("Unchanged context is eligible for an explicit share",true);
    s.getJSONObject("annotations").getJSONObject(id).put("important",false);
    boolean stale=false;try{MemoryWorkbench.validateContextSnapshot(s,snapshot);}catch(Exception expected){stale=true;}
    yes("Organization changes invalidate an already previewed packet",stale);
    s.getJSONObject("annotations").getJSONObject(id).put("important",true);
    JSONObject restored=Core.restore(Core.initial(now),Core.exportArchive(s,Core.ids(s),now));
    yes("Restore retains review source hash",MemoryWorkbench.visible(restored,restored.getJSONObject("events").getJSONObject(review.getString("id"))));
    Core.pause(s,true,now);
    String before=Core.digest(s);
    TemporalEngine.Reply found=TemporalEngine.recall(s,"find lighthouse today",now,zone);
    yes("Read-only Ask works while capture is paused",found.sourceIds.contains(id)&&!found.saveUtterance);
    TemporalEngine.Reply command=TemporalEngine.recall(s,"remember another poem",now,zone);
    yes("Ask never turns a capture command into a saved note",!command.saveUtterance&&command.text.contains("nothing was saved"));
    yes("Asking leaves entire store byte-equivalent",Core.digest(s).equals(before));
    yes("What did I capture yesterday resolves only that day",TemporalEngine.recall(s,"What did I capture yesterday?",now,zone).sourceIds.equals(Arrays.asList(old.getString("id"))));
    Core.pause(s,false,now+1);
    Core.erase(s,id);
    stale=false;try{MemoryWorkbench.validateContextSnapshot(s,snapshot);}catch(Exception expected){stale=true;}
    yes("Deletion invalidates an already previewed packet",stale);
    yes("Deleting a source also removes linked reviews",!s.getJSONObject("events").has(review.getString("id")));
    boolean denied=false;try{MemoryWorkbench.contextIds(s,Arrays.asList(review.getString("id")));}catch(Exception expected){denied=true;}
    yes("A stale context selection cannot share deleted interpretation",denied);
    yes("Reimport cannot resurrect source or its review",!Core.restore(s,Core.exportArchive(restored,Core.ids(restored),now)).getJSONObject("events").has(review.getString("id")));
    System.out.println(count+" iteration checks passed");
  }
}
