#!/usr/bin/env python3
"""Total Recall native archive adapter, manual transfer to existing Continuity core.

Only synthetic fixtures should enter plaintext Continuity working stores until
those stores gain independently verified encryption. Does not enable phone sync,
change project status, follow source instructions, or fetch URLs.
"""
from __future__ import annotations
import argparse, base64, getpass, hashlib, json, os, re, sys, uuid
from pathlib import Path
from datetime import datetime, timezone
import context_store as C
import capture_context as G

MAX_ARCHIVE = 48 * 1024 * 1024
MAX_BLOB = 5 * 1024 * 1024
MAX_TOTAL = 20 * 1024 * 1024
MAX_EVENTS = 3000
AAD = b'TotalRecall sealed v1'

def parse(raw):
    if len(raw)>MAX_ARCHIVE: raise C.ContextError('Archive size limit')
    return json.loads(raw.decode('utf-8',errors='strict'),object_pairs_hook=C._pairs,
                      parse_constant=lambda x: (_ for _ in ()).throw(C.ContextError('Non-finite number')))

def decrypt(raw, password=None):
    value = parse(raw)
    if value.get('format') != 'TotalRecallSealed/1':
        raise C.ContextError('Expected a protected native backup. Clear JSON is accepted only by the explicit test-fixture API.')
    if set(value) != {'format','kdf','iterations','salt','cipher','data'} or value['kdf']!='PBKDF2-HMAC-SHA256' or value['iterations']!=210000 or value['cipher']!='AES-256-GCM':
        raise C.ContextError('Unsupported encryption parameters')
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    salt=base64.b64decode(value['salt'],validate=True); data=base64.b64decode(value['data'],validate=True)
    if len(salt)!=16 or len(data)<28: raise C.ContextError('Malformed encryption envelope')
    password=password if password is not None else getpass.getpass('Backup passphrase: ')
    # Java PBKDF2WithHmacSHA256 and Python use UTF-8 for non-ASCII passphrases.
    key=hashlib.pbkdf2_hmac('sha256',password.encode('utf-8'),salt,210000,32)
    return parse(AESGCM(key).decrypt(data[:12],data[12:],AAD))

def in_ranges(ranges,t):
    return any(r['start_ms']<=t<r['end_ms'] for r in ranges)

def validate(bundle):
    if not isinstance(bundle,dict) or set(bundle)!={'format','payload','payload_sha256'} or bundle['format']!='TotalRecallBundle/1':raise C.ContextError('Unsupported bundle')
    body=bundle['payload']
    if body.get('format')!='TotalRecallArchive/1' or C.digest(body)!=bundle['payload_sha256']:raise C.ContextError('Archive hash/schema mismatch')
    rows=body['records'];blobs=body['blobs'];deleted=set(body['deleted_ids']);ranges=body['delete_ranges']
    if len(rows)>MAX_EVENTS or len(deleted)>20000 or len(ranges)>2000:raise C.ContextError('Count limit')
    for id_ in deleted: uuid.UUID(id_)
    for r in ranges:
        if type(r['start_ms']) is not int or type(r['end_ms']) is not int or not 0<=r['start_ms']<r['end_ms']:raise C.ContextError('Invalid deletion range')
    binary={};total=0
    for h,b in blobs.items():
        if not re.fullmatch('[a-f0-9]{64}',h):raise C.ContextError('Unsafe attachment identity')
        raw=base64.b64decode(b,validate=True);total+=len(raw)
        if len(raw)>MAX_BLOB or total>MAX_TOTAL or G.raw_hash(raw)!=h:raise C.ContextError('Attachment hash/size mismatch')
        binary[h]=raw
    seen=set();accepted=[]
    for row in rows:
        e=row['record'];id_=e['id'];uuid.UUID(id_);uuid.UUID(e['device_id'])
        fields={'schema','id','device_id','source_type','capture_method','event_ms','observed_ms','ingested_ms','event_at','observed_at','ingested_at','timezone','offset_seconds','privacy_scope','consent_policy_version','evidence','app','text','text_sha256','attachments','metadata','integrity_boundary'}
        if set(e)!=fields:raise C.ContextError('Unknown or missing event fields')
        if id_ in seen or e['schema']!='TotalRecallEvent/1' or C.digest(e)!=row['record_sha256']:raise C.ContextError('Record ID/hash/schema mismatch')
        seen.add(id_)
        text=e['text'];raw=text.encode('utf-8',errors='strict')
        if len(raw)>500000 or '\x00' in text or G.raw_hash(raw)!=e['text_sha256'] or e['privacy_scope']!='private':raise C.ContextError('Invalid text or privacy')
        if e['source_type'] not in {'note','link','image','file','audio','auto.usage','auto.notification'}:raise C.ContextError('Unknown source')
        for field in ['event_ms','observed_ms','ingested_ms']:
            if type(e[field]) is not int or e[field]<0:raise C.ContextError('Invalid time')
        for field in ['event_at','observed_at','ingested_at']:C.timestamp(e[field])
        if len(e['attachments'])>4:raise C.ContextError('Attachment count limit')
        for a in e['attachments']:
            if len(binary[a['sha256']])!=a['bytes']:raise C.ContextError('Missing/corrupt attachment')
        if e['source_type'].startswith('auto.'):
            allowed={'transition','event_type','instance','source_identity_sha256'} if e['source_type']=='auto.usage' else {'notification_identity_sha256','transition','grouped','group_summary','post_time_ms','removed_reason','content_status'}
            if text or e['attachments'] or set(e['metadata'])-allowed:raise C.ContextError('Automatic payload not permitted')
        annotation=row['annotation']
        if annotation and annotation.get('project_id') and not re.fullmatch(r'P[0-9]{1,4}',annotation['project_id']):raise C.ContextError('Invalid project annotation')
        if id_ not in deleted and not in_ranges(ranges,e['event_ms']):accepted.append(row)
    return body,accepted,binary

def preview(bundle):
    body,rows,binary=validate(bundle)
    return {'format':'TotalRecallImportPreview/1','eligible_records':len(rows),'attachments':len(binary),
            'bytes':sum(map(len,binary.values())),'privacy':'private','target_storage':'plaintext Continuity working store',
            'sync':False,'requires_explicit_non_sensitive_test_copy':True,'source_types':sorted({r['record']['source_type'] for r in rows})}

def ingest(store,bundle,*,project='P29',approved=False,non_sensitive=False):
    body,rows,blobs=validate(bundle)
    if not approved or not non_sensitive:raise C.ContextError('Explicit approval and non-sensitive test designation required; target core is plaintext')
    data,_=store.state()
    if project not in {p['id'] for p in data['projects']}:raise C.ContextError('Unknown project')
    # Old copies with tombstones cannot silently reappear. Current ledger is local and authoritative.
    ledger_path=store.root/'phone-deletions.json'
    if ledger_path.is_symlink():raise C.ContextError('Unsafe deletion ledger')
    ledger=C.read_json(ledger_path) if ledger_path.exists() else {'deleted_ids':[],'delete_ranges':[]}
    blocked=set(ledger['deleted_ids'])|set(body['deleted_ids']);ranges=ledger['delete_ranges']+body['delete_ranges']
    # This adapter does not rewrite historical journals to erase past imports.
    # Refuse partial deletion claims; owner must rebuild/migrate the destination or keep transfer local.
    known={s.get('capture_id') for s in data['sources']}
    if known & blocked:raise C.ContextError('Deletion intersects already imported sources. Remote deletion unsupported; no new transfer applied.')
    for s in data['sources']:
        t=s.get('origin',{}).get('event_ms')
        if t is not None and in_ranges(ranges,t):raise C.ContextError('Time deletion intersects previous import; destination rebuild required')
    C.json_write(ledger_path,{'deleted_ids':sorted(blocked),'delete_ranges':ranges})
    receipts=[]
    for row in rows:
        e=row['record'];id_=e['id']
        if id_ in blocked or in_ranges(ranges,e['event_ms']):continue
        # Export time, changing annotations and batch hashes must not perturb retry identity.
        event_raw=C.canonical_bytes(e)
        event_h=store.put_object(event_raw)
        for a in e['attachments']:store.put_object(blobs[a['sha256']])
        is_text=bool(e['text'].strip())
        body_text=e['text'] if is_text else ('[Total Recall metadata representation; original event is a separate hashed object]\n'+json.dumps({'event_id':id_,'source_type':e['source_type'],'metadata':e['metadata'],'attachments':e['attachments']},ensure_ascii=False,sort_keys=True))
        req={'format':G.FORMAT,'capture_id':id_,'text':body_text,'title':'Phone '+e['source_type']+' '+e['event_at'],
             'project_id':project,'visibility':'private','source_kind':'UNKNOWN','speaker':'unknown',
             'observed_at':e['observed_at'],'device_id':e['device_id'],'mechanism':e['capture_method'],
             'source_app':e['app'],'origin':{'adapter':'total-recall-native/1','event_id':id_,'event_sha256':event_h,
             'event_ms':e['event_ms'],'text_is_original':is_text,'original_text_sha256':e['text_sha256'],
             'attachment_sha256':[a['sha256'] for a in e['attachments']], 'privacy_scope':'private',
             'trust':'untrusted-source-data','phone_consent_does_not_grant_model_or_public_access':True}}
        receipts.append(store.accept(req,True,'capture-adapter'))
    return {'format':'TotalRecallImportReceipt/1','receipts':receipts,'manual_transfer':True,'remote_sync':False,'core_storage':'plaintext','publication_approved':False}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('archive',type=Path);p.add_argument('--root',type=Path);p.add_argument('--project',default='P29');p.add_argument('--approve-non-sensitive-test-copy',action='store_true');p.add_argument('--receipt',type=Path)
    a=p.parse_args()
    try:
        bundle=decrypt(a.archive.read_bytes());result=preview(bundle)
        if a.approve_non_sensitive_test_copy:
            if a.root is None:raise C.ContextError('Existing Continuity root required')
            result=ingest(G.Store(a.root),bundle,project=a.project,approved=True,non_sensitive=True)
        if a.receipt:C.json_write(a.receipt,result)
        print(json.dumps(result,ensure_ascii=False,indent=2));return 0
    except Exception as e:
        print('Phone archive rejected: '+type(e).__name__+'. Original and existing store retained.',file=sys.stderr);return 2
if __name__=='__main__':raise SystemExit(main())
