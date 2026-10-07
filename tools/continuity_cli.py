#!/usr/bin/env python3
"""Provider-neutral CLI for Continuity semantic operations."""
import argparse, json, sys
from pathlib import Path
import continuity_semantics as S

def save(path,value): S.atomic_json(path,value,True)
def main(argv=None):
    ap=argparse.ArgumentParser(description=__doc__); sub=ap.add_subparsers(dest="cmd",required=True)
    p=sub.add_parser("init"); p.add_argument("ledger",type=Path); p.add_argument("--created-at")
    p=sub.add_parser("verify"); p.add_argument("ledger",type=Path)
    p=sub.add_parser("append"); p.add_argument("ledger",type=Path); p.add_argument("record",type=Path); p.add_argument("--expected-hash",required=True); p.add_argument("--approve",action="store_true")
    for name in ("find-entity","provenance","resume"):
        p=sub.add_parser(name); p.add_argument("ledger",type=Path); p.add_argument("value")
    p=sub.add_parser("find-conflicts"); p.add_argument("ledger",type=Path); p.add_argument("--project")
    p=sub.add_parser("changes"); p.add_argument("ledger",type=Path); p.add_argument("--since"); p.add_argument("--until"); p.add_argument("--project")
    p=sub.add_parser("project-state"); p.add_argument("ledger",type=Path); p.add_argument("project_id")
    p=sub.add_parser("export"); p.add_argument("ledger",type=Path)
    a=ap.parse_args(argv)
    try:
        if a.cmd=="init":
            store=S.init_ledger(a.created_at); S.atomic_json(a.ledger,store,False); out=S.replay(store)[1]
        else:
            store=S.read_json(a.ledger); projection,head=S.replay(store)
            if a.cmd=="verify": out={**head,"valid":True,"counts":{k:len(projection[k]) for k in ("sources","entities","claims","relationships","artifacts","work")}}
            elif a.cmd=="append":
                rec=S.read_json(a.record); new,changed=S.append(store,rec,a.expected_hash,a.approve)
                if changed: save(a.ledger,new)
                out={**S.replay(new)[1],"changed":changed}
            elif a.cmd=="find-entity": out=S.find_entity(projection,a.value)
            elif a.cmd=="provenance": out=S.get_provenance(projection,a.value)
            elif a.cmd=="resume":\n                out=S.resume_packet(store,a.value)\n                if a.markdown:\n                    print(S.resume_markdown(out),end=""); return 0
            elif a.cmd=="find-conflicts": out=S.conflicts(projection,a.project)
            elif a.cmd=="changes": out=S.query_changes(projection,a.since,a.until,a.project)
            elif a.cmd=="project-state":
                pid=a.project_id; entity=projection["entities"].get(pid)
                if not entity: raise S.ContinuityError("project not found")
                out={"project":entity,"resume":S.resume_packet(store,pid)}
            elif a.cmd=="export": out={"format":"ContinuitySemanticExport/1","ledger":head,"projection":projection}
        print(json.dumps(out,ensure_ascii=False,indent=2)); return 0
    except (S.ContinuityError,OSError,ValueError,TypeError,KeyError,json.JSONDecodeError) as e:
        print(f"Continuity CLI error: {e}",file=sys.stderr); return 2
if __name__=="__main__": raise SystemExit(main())
