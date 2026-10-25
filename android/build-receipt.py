"""Public build identity only. Never reads signing secrets or phone data."""
from pathlib import Path
import hashlib, json, os, re, subprocess
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parent
ns='{http://schemas.android.com/apk/res/android}'
manifest=ET.parse(root/'AndroidManifest.xml').getroot()
version=manifest.get(ns+'versionName')
apk=root/'build'/f'TotalRecall-{version}.apk'
inputs=sorted([root/'AndroidManifest.xml',root/'build.sh',root/'build-receipt.py']+
              list((root/'src').rglob('*.java'))+list((root/'res').rglob('*')))
hashes={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs if p.is_file()}
git=lambda *args: subprocess.check_output(['git',*args],cwd=root,text=True).strip()
signer=re.search(r'certificate SHA-256 digest: (\w+)',(root/'build/signature.txt').read_text()).group(1)
receipt={'format':'TotalRecallBuild/1','version':version,'version_code':int(manifest.get(ns+'versionCode')),
         'package':manifest.get('package'),'commit':git('rev-parse','HEAD'),
         'source_tree':git('rev-parse','HEAD^{tree}'),'dirty':bool(git('status','--porcelain')),
         'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'apk_bytes':apk.stat().st_size,
         'certificate_sha256':signer,'signing_kind':os.environ.get('RECALL_SIGNING_KIND','unverified-local'),
         'sdk':{'min':26,'compile':36,'target':36,'build_tools':'36.0.0'},'input_sha256':hashes}
(root/'build/build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
