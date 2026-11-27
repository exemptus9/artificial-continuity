"""Owner filing and deletion for local and hosted archives."""
from contextlib import nullcontext
import fcntl
import json
import re
import secrets

from recall_archive import Failure,UID,archive_lock,now,sync_dir

DEFAULT_LABELS=["Forge Lexicon","Poem draft","Project decision","To do","Connection"]
SCHEMA="""
CREATE TABLE IF NOT EXISTS owner_reviews(kind TEXT,id TEXT,state TEXT,labels TEXT,name TEXT,writing TEXT,heard TEXT,updated INTEGER,PRIMARY KEY(kind,id));
CREATE TABLE IF NOT EXISTS recent_filing_undo(kind TEXT,id TEXT,token TEXT,state TEXT,labels TEXT,clear_seed INTEGER,PRIMARY KEY(kind,id));
CREATE TABLE IF NOT EXISTS item_stories(kind TEXT,id TEXT,body TEXT,rev INTEGER,PRIMARY KEY(kind,id));
CREATE TABLE IF NOT EXISTS recording_evidence(kind TEXT,id TEXT,offset INTEGER,mode TEXT,cues TEXT,PRIMARY KEY(kind,id,offset,mode));
CREATE TABLE IF NOT EXISTS owner_options(slot INTEGER PRIMARY KEY,config TEXT);
CREATE TABLE IF NOT EXISTS deleted_items(kind TEXT,id TEXT,PRIMARY KEY(kind,id));
"""
def options(db):
    row=db.execute("SELECT config FROM owner_options WHERE slot=1").fetchone()
    return json.loads(row[0]) if row else dict(labels=DEFAULT_LABELS,batch=3,order="newest")
def clean_text(value,maximum):
    if not isinstance(value,str) or len(value)>maximum or "\0" in value:raise Failure(400,"Invalid text length")
    return value.strip()
def save_options(arc,data):
    labels=data.get("labels");batch=data.get("batch");order=data.get("order")
    if not isinstance(labels,list) or not 1<=len(labels)<=14 or batch not in (3,5,10,20) or order not in ("oldest","newest"):
        raise Failure(400,"Choose 1–14 buttons and a supported review size and order")
    names=list(dict.fromkeys(clean_text(x,42) for x in labels))
    if "" in names:raise Failure(400,"A category needs a name")
    with arc.connection() as db:
        result=dict(labels=names,batch=batch,order=order)
        db.execute("INSERT OR REPLACE INTO owner_options VALUES(1,?)",(json.dumps(result),))
    return result
def identify(kind,uid):
    if kind not in ("clip","journal","segment") or not isinstance(uid,str) or not UID.fullmatch(uid):raise Failure(400,"Invalid archive item")
def require(db,kind,uid):
    identify(kind,uid)
    if kind=="clip":row=db.execute("SELECT sha FROM clips WHERE id=?",(uid,)).fetchone()
    else:row=db.execute("SELECT sha FROM archive_uploads WHERE kind=? AND id=? AND state='complete'",(kind,uid)).fetchone()
    if not row:raise Failure(404,"This item is no longer in the archive")
    return row[0]
def review(db,kind,uid):
    row=db.execute("SELECT state,labels,name,writing,heard FROM owner_reviews WHERE kind=? AND id=?",(kind,uid)).fetchone()
    if not row:return dict(state="inbox",labels=[],name="",writing="",heard="")
    return dict(state=row[0],labels=json.loads(row[1]),name=row[2],writing=row[3],heard=row[4])
def classify(arc,data):
    kind,uid=data.get("kind"),data.get("id");action=data.get("action")
    with arc.connection() as db:
        db.execute("BEGIN IMMEDIATE");require(db,kind,uid)
        r=review(db,kind,uid)
        if action=="story":
            body=clean_text(data.get("body"),12000)
            old=db.execute("SELECT rev FROM item_stories WHERE kind=? AND id=?",(kind,uid)).fetchone()
            revision=old[0] if old else 0
            if type(data.get("revision"))!=int or data["revision"]!=revision:raise Failure(409,"Story changed. Reopen the item to see the saved version.")
            db.execute("INSERT OR REPLACE INTO item_stories VALUES(?,?,?,?)",(kind,uid,body,revision+1))
            return dict(body=body,revision=revision+1)
        receipt=None
        if action in ("add","remove","dismiss","reopen"):
            receipt=dict(state=r["state"],labels=list(r["labels"]),clearSeed=False)
        if action=="undo":
            token=data.get("before")
            if not isinstance(token,str) or len(token)>100:
                raise Failure(400,"Invalid undo receipt")
            saved=db.execute("SELECT state,labels,clear_seed FROM recent_filing_undo WHERE kind=? AND id=? AND token=?",(kind,uid,token)).fetchone()
            if not saved:
                raise Failure(409,"This filing can no longer be undone. Reopen the item to see its current state.")
            r["state"],r["labels"]=saved[0],json.loads(saved[1])
            if saved[2]:r["writing"]=""
        elif action in ("add","remove"):
            label=clean_text(data.get("label"),42)
            if not label:raise Failure(400,"Choose a category")
            if action=="add" and label not in r["labels"]:
                if len(r["labels"])>=32:raise Failure(400,"This item already has 32 categories")
                r["labels"].append(label)
                if label in ("Poem draft","To do","Project decision","Connection") and kind=="clip" and not r["writing"]:
                    source=db.execute("""SELECT coalesce(nullif(json_extract(metadata,'$.correction'),''),transcript) FROM clips WHERE id=?""",(uid,)).fetchone()
                    r["writing"]=(source[0] or "")[:40000]
                    receipt["clearSeed"]=bool(r["writing"])
            if action=="remove" and label in r["labels"]:r["labels"].remove(label)
            r["state"]="filed" if r["labels"] else "inbox"
        elif action in ("dismiss","reopen"):r["state"]="dismissed" if action=="dismiss" else "inbox"
        elif action=="edit":
            for key,limit in (("name",180),("writing",40000),("heard",180)):
                r[key]=clean_text(data.get(key,""),limit)
        else:raise Failure(400,"Unknown review action")
        db.execute("DELETE FROM recent_filing_undo WHERE kind=? AND id=?",(kind,uid))
        db.execute("INSERT OR REPLACE INTO owner_reviews VALUES(?,?,?,?,?,?,?,?)",(kind,uid,r["state"],json.dumps(r["labels"]),r["name"],r["writing"],r["heard"],now()))
        if receipt is not None:
            r["undo"]=secrets.token_urlsafe(24)
            db.execute("INSERT INTO recent_filing_undo VALUES(?,?,?,?,?,?)",(kind,uid,r["undo"],receipt["state"],json.dumps(receipt["labels"]),receipt["clearSeed"]))
            db.execute("DELETE FROM recent_filing_undo WHERE rowid IN (SELECT rowid FROM recent_filing_undo ORDER BY rowid DESC LIMIT -1 OFFSET 128)")
        return r
def inbox(arc,shelf,offset):
    from recall_web import BASE_SQL
    if not 0<=offset<=10000000 or len(shelf)>55:raise Failure(400,"Invalid review page")
    with arc.connection() as db:
        opt=options(db)
        labels=set(opt["labels"])
        for row in db.execute("SELECT DISTINCT j.value FROM owner_reviews,json_each(labels) j"):labels.add(row[0])
        if shelf=="inbox":condition="a.kind='clip' AND coalesce(r.state,'inbox')='inbox'";params=[]
        elif shelf in ("filed","dismissed"):condition="r.state=?";params=[shelf]
        elif shelf.startswith("label/"):condition="EXISTS(SELECT 1 FROM json_each(r.labels) WHERE value=?)";params=[shelf[6:]]
        else:raise Failure(400,"Invalid review shelf")
        source=BASE_SQL.split("UNION ALL",1)[0] if shelf=="inbox" else BASE_SQL
        query=" FROM ("+source+") a LEFT JOIN owner_reviews r ON r.id=a.id AND r.kind=a.kind WHERE "+condition
        direction="ASC" if opt["order"]=="oldest" else "DESC"
        rows=db.execute("SELECT a.id,a.kind,a.title,a.startedAtMillis,a.durationMs,substr(a.content,1,240),json_extract(a.metadata,'$.category')"+query+" ORDER BY a.startedAtMillis "+direction+",a.id,a.kind LIMIT ? OFFSET ?",[*params,opt["batch"],offset])
        result=[dict(id=x[0],kind=x[1],title=x[2],startedAtMillis=x[3],durationMs=x[4],snippet=x[5],category=x[6],review=review(db,x[1],x[0])) for x in rows]
        return dict(items=result,total=db.execute("SELECT count(*)"+query,params).fetchone()[0],options=opt,shelves=sorted(labels,key=str.casefold))
def delete(arc,data):
    kind,uid=data.get("kind"),data.get("id");identify(kind,uid)
    digest=data.get("sha256","")
    if not isinstance(digest,str) or not re.fullmatch("[a-f0-9]{64}",digest):raise Failure(400,"Confirm the original item before deleting")
    cloud=getattr(arc,"media_store",None)
    stripe=cloud.stripe(("wav-" if kind=="clip" else "")+uid) if cloud else nullcontext()
    with stripe,archive_lock(arc.root),arc.connection() as db:
        db.execute("PRAGMA secure_delete=ON");db.execute("BEGIN IMMEDIATE")
        if db.execute("SELECT 1 FROM deleted_items WHERE kind=? AND id=?",(kind,uid)).fetchone():return {"deleted":True}
        if require(db,kind,uid)!=digest:raise Failure(409,"Original item changed. Review it again")
        if cloud:
            table="cloud_wavs" if kind=="clip" else "cloud_media"
            obj=db.execute("SELECT object_key FROM "+table+" WHERE id=?",(uid,)).fetchone()
            if obj:
                head=cloud._head(obj[0])
                if head:
                    if head.get("Metadata",{}).get("archive")!=cloud.instance:raise Failure(409,"Storage ownership changed")
                    cloud._call(cloud.s3.delete_object,Key=obj[0])
                db.execute("DELETE FROM "+table+" WHERE id=?",(uid,))
        for table in ("owner_reviews","item_stories","recording_evidence","recent_filing_undo"):
            db.execute("DELETE FROM "+table+" WHERE kind=? AND id=?",(kind,uid))
        db.execute("INSERT INTO deleted_items VALUES(?,?)",(kind,uid))
        if kind=="clip":
            for table,key in (("observations","clip_id"),("clip_original_sizes","id"),("clips","id")):
                db.execute("DELETE FROM "+table+" WHERE "+key+"=?",(uid,))
            rows=db.execute("SELECT id FROM archive_uploads WHERE kind='clip' AND (journal=? OR (journal='' AND substr(id,1,?)=?))",(uid,len(uid)+1,uid+"-")).fetchall()
            db.executemany("INSERT OR IGNORE INTO deleted_items VALUES('upload',?)",rows)
            db.executemany("DELETE FROM archive_uploads WHERE id=?",rows)
        else:
            db.execute("DELETE FROM archive_uploads WHERE kind=? AND id=?",(kind,uid))
            (arc.files/uid).unlink(missing_ok=True);sync_dir(arc.files)
        cache=arc.root/"browser-previews"
        if cache.is_dir():
            with (cache/"cache.lock").open("a") as lock:
                fcntl.flock(lock,fcntl.LOCK_EX);(cache/(digest+".m4a")).unlink(missing_ok=True)
    return {"deleted":True}
