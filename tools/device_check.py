#!/usr/bin/env python3
"""Validate a synthetic device-trial export; device identity is operator supplied."""
import argparse, hashlib, json
from pathlib import Path
from release_smoke import TEXT
from capture_context import now
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('export',type=Path);p.add_argument('--device-label',required=True);p.add_argument('--receipt',required=True,type=Path)
a=p.parse_args();raw=a.export.read_bytes();data=json.loads(raw)
if data.get('format')!='ContinuityBackup/2':p.exit(2,'Expected the browser workspace backup format.\n')
matches=[s for s in data.get('state',{}).get('sources',[]) if s.get('text')==TEXT]
if not matches:p.exit(2,'FAIL: exact fixture absent. Preserve the export and original bytes.\n')
receipt={'status':'EXACT FIXTURE VERIFIED IN SUPPLIED EXPORT','device_label':a.device_label,'device_identity_basis':'operator supplied; not independently attested','checked_at':now(),'export_sha256':hashlib.sha256(raw).hexdigest(),'original_sha256':hashlib.sha256(TEXT.encode()).hexdigest(),'source_ids':[s['id'] for s in matches],'native_interception':'NOT TESTED','cross_device_sync':'NOT IMPLEMENTED'}
if a.receipt.exists():p.exit(2,'Choose a new receipt path; prior evidence is never replaced.\n')
a.receipt.write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))
