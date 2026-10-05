#!/usr/bin/env python3
"""Demonstrate exact synthetic capture, restart, retrieval and isolated restore."""
import argparse
import hashlib
import json
from pathlib import Path
import uuid

import context_store as C
import capture_context as G

TEXT = 'Synthetic release fixture\r\nFirst — “quoted” α\n\n    indented line\n\tTabbed line\nFinal...'

def run(destination, browser_export=None):
    destination = Path(destination).absolute()
    if destination.exists():
        raise C.ContextError('Choose a new isolated destination; existing data is never overwritten.')
    G.private_directory(destination)
    baseline = destination / 'synthetic-baseline.json'
    C.json_write(baseline, C.init_store({'projects':[{'id':'DEMO','name':'Synthetic release check','state':'ACTIVE','confidence':'C'}],'sources':[]}))
    store = G.Store.initialize(destination / 'working', baseline)
    owner = G.Principal('owner')
    req = {'format':G.FORMAT,'capture_id':str(uuid.uuid4()),'text':TEXT,'title':'Synthetic release fixture',
           'project_id':'DEMO','speaker':'user','source_kind':'USER_ASSERTION','visibility':'private',
           'observed_at':G.now(),'device_id':'synthetic-host','mechanism':'explicit-release-check'}
    store.spool(req)
    saved = store.flush(True)[0]
    store = G.Store(destination / 'working')
    assert store.source(saved['source_id'], owner)['original_text'] == TEXT
    assert store.search('Synthetic release fixture', owner)[0]['source_id'] == saved['source_id']
    assert store.accept(req, True)['already_saved']
    try:
        store.accept({**req,'text':'Conflicting update'}, True)
    except C.ContextError: conflict_rejected=True
    else: raise AssertionError('Conflict was not rejected')
    corrected=store.accept({**req,'capture_id':str(uuid.uuid4()),'text':TEXT.replace('First','Corrected first'),
                            'origin':{'corrects_source_id':saved['source_id']}},True)
    imported=None
    if browser_export:
        raw=Path(browser_export).read_bytes()
        imported=store.import_browser(raw,'DEMO',True)
        assert all(r['already_saved'] for r in store.import_browser(raw,'DEMO',True)['receipts'])
        assert store.object(imported['archive_sha256'])==raw
    store.backup(destination / 'restored')
    backup=G.Store.verify_backup(destination / 'restored')
    restored=G.Store(destination / 'restored')
    original=restored.source(saved['source_id'],owner)['original_text']
    assert original.encode()==TEXT.encode()
    assert restored.state()[1]['store_sha256']==store.state()[1]['store_sha256']
    result={'status':'COMPLETE — VERIFIED','fixture':'synthetic only','original_sha256':hashlib.sha256(TEXT.encode()).hexdigest(),
            'source_id':saved['source_id'],'correction_id':corrected['source_id'],'timestamp':req['observed_at'],
            'capture_reload_retrieve_export_restore_equal':True,'conflicting_update_rejected':conflict_rejected,
            'repeat_import_idempotent':True,'browser_import':imported,'backup':backup,
            'device_scope':'execution-host only; physical Android/laptop NOT RUN','working_store_encrypted':False}
    C.json_write(destination / 'RECEIPT.json',result)
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--destination',required=True);p.add_argument('--browser-export')
    a=p.parse_args()
    try: print(json.dumps(run(a.destination,a.browser_export),ensure_ascii=False,indent=2))
    except (C.ContextError,OSError,AssertionError) as e: p.exit(2,str(e)+'\n')
