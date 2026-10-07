#!/usr/bin/env python3
"""Evidence-backed semantic/event layer for Continuity (stdlib only)."""
import copy, hashlib, json, re
from datetime import datetime, timezone

FORMAT="ContinuitySemanticLedger/1"; RESUME_FORMAT="ContinuityResumePacket/1"
ID=re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,191}$")
CANON={"possible","observed","provisional","user-confirmed","canonical","superseded","disputed"}
WORK={"idea","planned","started","implemented","tested","verified","released","deprecated"}
ORIGIN={"user-confirmed","source-observed","imported","inferred","generated"}
KINDS={"project","work","person","organization","file","system","task","concept","artifact","other"}
TYPES={"source_recorded","entity_recorded","entity_renamed","claim_recorded","claim_resolution","relationship_recorded","artifact_recorded","work_state_set","decision_recorded","event_recorded"}
class ContinuityError(ValueError): pass

def _bytes(v): return json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(",",":"),allow_nan=False).encode()
def digest(v): return hashlib.sha256(_bytes(v)).hexdigest()
def now(): return datetime.now(timezone.utc).isoformat(timespec="seconds")
def _id(v): return isinstance(v,str) and ID.fullmatch(v) is not None
def _time(v):
    try:
        d=datetime.fromisoformat(v.replace("Z","+00:00")); assert d.tzinfo
    except Exception as e: raise ContinuityError("timestamp requires timezone") from e
def _copy(v): return json.loads(json.dumps(v,ensure_ascii=False,allow_nan=False))
def read_json(path):
    from pathlib import Path
    return json.loads(Path(path).read_text(encoding="utf-8"))

def atomic_json(path,value,replace=True):
    from pathlib import Path
    path=Path(path)
    if path.exists() and not replace: raise ContinuityError("destination exists")
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+"\n",encoding="utf-8")

def init_ledger(created_at=None):
    created_at=created_at or now(); _time(created_at); return {"format":FORMAT,"created_at":created_at,"events":[]}
def record(type,id,payload,source_refs=None,actor="owner",at=None):
    return {"id":id,"type":type,"at":at or now(),"actor":actor,"source_refs":source_refs or [],"payload":payload}

def _validate(r):
    if not isinstance(r,dict) or not _id(r.get("id")) or r.get("type") not in TYPES: raise ContinuityError("bad record")
    _time(r.get("at")); refs=r.get("source_refs",[]); x=r.get("payload")
    if not r.get("actor") or not isinstance(refs,list) or not all(_id(z) for z in refs) or not isinstance(x,dict): raise ContinuityError("bad record envelope")
    t=r["type"]
    if t=="source_recorded":
        if not _id(x.get("id")) or not x.get("locator"): raise ContinuityError("source needs id/locator")
        if x.get("observed_at"): _time(x["observed_at"])
    elif t=="entity_recorded":
        if not _id(x.get("id")) or x.get("kind") not in KINDS or not x.get("name"): raise ContinuityError("bad entity")
    elif t=="entity_renamed":
        if not _id(x.get("entity_id")) or not x.get("name"): raise ContinuityError("bad rename")
    elif t=="claim_recorded":
        if not _id(x.get("id")) or not _id(x.get("subject_id")) or not x.get("predicate") or "value" not in x or x.get("status") not in CANON or x.get("origin") not in ORIGIN: raise ContinuityError("bad claim")
        if not 0<=x.get("confidence",1)<=1: raise ContinuityError("bad confidence")
        for k in ("valid_from","valid_to"):
            if x.get(k): _time(x[k])
    elif t=="claim_resolution":
        if not _id(x.get("winner")) or not all(_id(z) for z in x.get("superseded",[])): raise ContinuityError("bad resolution")
    elif t=="relationship_recorded":
        if not all(_id(x.get(k)) for k in ("id","subject_id","object_id")) or not x.get("predicate") or x.get("status","observed") not in CANON: raise ContinuityError("bad relationship")
    elif t=="artifact_recorded":
        if not _id(x.get("id")) or not _id(x.get("project_id")) or not x.get("name") or not x.get("locator"): raise ContinuityError("bad artifact")
    elif t=="work_state_set":
        if not _id(x.get("id")) or not _id(x.get("project_id")) or x.get("state") not in WORK or not x.get("summary"): raise ContinuityError("bad work state")
        if x["state"] in {"tested","verified","released"} and not x.get("evidence_refs"): raise ContinuityError("evidence required")
    elif t=="decision_recorded":
        if not _id(x.get("id")) or not _id(x.get("project_id")) or not x.get("key") or "value" not in x or x.get("status","canonical") not in CANON: raise ContinuityError("bad decision")
    elif t=="event_recorded":
        if not _id(x.get("project_id")) or not x.get("event_type"): raise ContinuityError("bad event")

def _blank(): return {"sources":{},"entities":{},"claims":{},"relationships":{},"artifacts":{},"work":{},"decisions":{},"events":[]}
def _refs(p,r):
    missing=[z for z in r.get("source_refs",[]) if z not in p["sources"]]
    if missing: raise ContinuityError("missing source: "+",".join(missing))
def _apply(p,r):
    _validate(r); t=r["type"]; x=r["payload"]
    if t!="source_recorded": _refs(p,r)
    if t=="source_recorded":
        if x["id"] in p["sources"]: raise ContinuityError("duplicate source")
        p["sources"][x["id"]]=_copy(x)
    elif t=="entity_recorded":
        if x["id"] in p["entities"]: raise ContinuityError("duplicate entity")
        p["entities"][x["id"]]={**_copy(x),"aliases":list(x.get("aliases",[])),"name_history":[{"name":x["name"],"at":r["at"],"record_id":r["id"]}]}
    elif t=="entity_renamed":
        e=p["entities"].get(x["entity_id"])
        if not e: raise ContinuityError("unknown entity")
        if e["name"]!=x["name"] and e["name"] not in e["aliases"]: e["aliases"].append(e["name"])
        e["name"]=x["name"]; e["name_history"].append({"name":x["name"],"at":r["at"],"record_id":r["id"]})
    elif t=="claim_recorded":
        if x["id"] in p["claims"] or x["subject_id"] not in p["entities"]: raise ContinuityError("duplicate/unknown claim")
        p["claims"][x["id"]]={**_copy(x),"source_refs":list(r["source_refs"]),"asserted_at":r["at"],"record_id":r["id"]}
    elif t=="claim_resolution":
        w=p["claims"].get(x["winner"])
        if not w: raise ContinuityError("unknown winner")
        w["status"]="canonical"; w["resolution_record_id"]=r["id"]
        for cid in x.get("superseded",[]):
            old=p["claims"].get(cid)
            if not old or (old["subject_id"],old["predicate"])!=(w["subject_id"],w["predicate"]): raise ContinuityError("invalid resolution set")
            old["status"]="superseded"; old["superseded_by"]=w["id"]
    elif t=="relationship_recorded":
        if x["id"] in p["relationships"] or x["subject_id"] not in p["entities"] or x["object_id"] not in p["entities"]: raise ContinuityError("bad relationship identity")
        p["relationships"][x["id"]]={**_copy(x),"source_refs":list(r["source_refs"]),"asserted_at":r["at"],"record_id":r["id"]}
    elif t=="artifact_recorded":
        if x["id"] in p["artifacts"]: raise ContinuityError("duplicate artifact")
        p["artifacts"][x["id"]]={**_copy(x),"source_refs":list(r["source_refs"]),"recorded_at":r["at"],"record_id":r["id"]}
    elif t=="work_state_set":
        old=p["work"].get(x["id"]); hist=[] if not old else old["history"]
        p["work"][x["id"]]={**_copy(x),"history":hist+[{"state":x["state"],"at":r["at"],"record_id":r["id"],"evidence_refs":list(x.get("evidence_refs",[]))}],"source_refs":list(r["source_refs"]),"updated_at":r["at"],"record_id":r["id"]}
    elif t=="decision_recorded": p["decisions"].setdefault(x["project_id"],[]).append({**_copy(x),"source_refs":list(r["source_refs"]),"decided_at":r["at"],"record_id":r["id"]})

def replay(store,upto=None):
    if store.get("format")!=FORMAT or not isinstance(store.get("events"),list): raise ContinuityError("bad ledger")
    _time(store.get("created_at")); events=store["events"] if upto is None else store["events"][:upto]
    parent=digest({"format":FORMAT,"created_at":store["created_at"]}); p=_blank(); seen=set()
    for i,e in enumerate(events,1):
        wrapper={"revision":i,"parent":parent,"record":e.get("record")}
        if e.get("revision")!=i or e.get("parent")!=parent or e.get("sha256")!=digest(wrapper): raise ContinuityError("hash chain mismatch")
        if e["record"]["id"] in seen: raise ContinuityError("duplicate record id")
        _apply(p,e["record"]); p["events"].append(_copy(e["record"])); seen.add(e["record"]["id"]); parent=e["sha256"]
    return p,{"revision":len(events),"head_sha256":parent,"store_sha256":digest(store)}
def append(store,r,expected_hash,approved=False):
    p,h=replay(store); _validate(r)
    if not approved: raise ContinuityError("explicit approval required")
    if expected_hash!=h["store_sha256"]: raise ContinuityError("stale ledger")
    for e in store["events"]:
        if e["record"]["id"]==r["id"]:
            if e["record"]==r: return _copy(store),False
            raise ContinuityError("record id conflict")
    _apply(_copy(p),r); w={"revision":h["revision"]+1,"parent":h["head_sha256"],"record":_copy(r)}; out=_copy(store); out["events"].append({**w,"sha256":digest(w)}); replay(out); return out,True

def find_entity(p,q):
    q=q.casefold().strip(); exact=[]; fuzzy=[]
    for e in p["entities"].values():
        names=[e["id"],e["name"],*e.get("aliases",[])]
        if any(q==n.casefold() for n in names): exact.append(e)
        elif q and all(t in " ".join(names).casefold() for t in q.split()): fuzzy.append(e)
    return _copy(exact or fuzzy)
def conflicts(p,project_id=None):
    g={}
    for c in p["claims"].values():
        if c["status"]=="superseded" or (project_id and c["subject_id"]!=project_id): continue
        g.setdefault((c["subject_id"],c["predicate"]),[]).append(c)
    return [{"subject_id":s,"predicate":pr,"claim_ids":[r["id"] for r in rows],"values":[r["value"] for r in rows]} for (s,pr),rows in g.items() if len(rows)>1 and len({json.dumps(r["value"],sort_keys=True) for r in rows})>1]
def get_provenance(p,oid):
    for group in ("claims","relationships","artifacts","work"):
        if oid in p[group]:
            o=_copy(p[group][oid]); refs=o.get("source_refs",o.get("evidence_refs",[])); return {"object_type":group,"object":o,"sources":[_copy(p["sources"][s]) for s in refs if s in p["sources"]]}
    raise ContinuityError("object not found")
def query_changes(p,since=None,until=None,project_id=None):
    if since:_time(since)
    if until:_time(until)
    out=[]
    for r in p["events"]:
        if since and r["at"]<since or until and r["at"]>until: continue
        x=r["payload"]; related=x.get("project_id")==project_id or x.get("id")==project_id or x.get("subject_id")==project_id
        if project_id is None or related: out.append(_copy(r))
    return out
def _best(rows,key="status"):
    rank={"canonical":5,"user-confirmed":4,"observed":3,"provisional":2,"possible":1,"disputed":0,"superseded":-1}; return sorted(rows,key=lambda x:(rank.get(x.get(key),0),x.get("asserted_at",x.get("decided_at","")),x["id"]),reverse=True)[0]
def resume_packet(store,pid):
    p,h=replay(store); project=p["entities"].get(pid)
    if not project or project["kind"]!="project": raise ContinuityError("project not found")
    cg={}
    for c in p["claims"].values():
        if c["subject_id"]==pid and c["status"]!="superseded": cg.setdefault(c["predicate"],[]).append(c)
    claims=sorted([_copy(_best(v)) for v in cg.values()],key=lambda x:x["predicate"])
    dg={}
    for d in p["decisions"].get(pid,[]):
        if d.get("status")!="superseded": dg.setdefault(d["key"],[]).append(d)
    decisions=sorted([_copy(_best(v)) for v in dg.values()],key=lambda x:x["key"])
    work=sorted([_copy(w) for w in p["work"].values() if w["project_id"]==pid],key=lambda x:(x["updated_at"],x["id"])); done=[w for w in work if w["state"] in {"tested","verified","released"}]; active=[w for w in work if w["state"] not in {"verified","released","deprecated"}]
    arts=sorted([_copy(a) for a in p["artifacts"].values() if a["project_id"]==pid],key=lambda x:(x["recorded_at"],x["id"])); ev=set()
    for z in claims+decisions+arts+work: ev.update(z.get("source_refs",z.get("evidence_refs",[])))
    return {"format":RESUME_FORMAT,"generated_at":now(),"ledger":h,"project":{"id":pid,"name":project["name"],"aliases":project.get("aliases",[]),"name_history":project["name_history"]},"objective":next((c["value"] for c in claims if c["predicate"]=="objective"),None),"canonical_claims":claims,"canonical_decisions":decisions,"last_verified_accomplishment":done[-1] if done else None,"unresolved_conflicts":conflicts(p,pid),"artifacts":arts,"evidence":[_copy(p["sources"][s]) for s in sorted(ev) if s in p["sources"]],"do_not_redo":[{"work_id":w["id"],"summary":w["summary"],"state":w["state"],"evidence_refs":w.get("evidence_refs",[])} for w in done],"next_actions":[{"work_id":w["id"],"summary":w["summary"],"state":w["state"]} for w in active]}
