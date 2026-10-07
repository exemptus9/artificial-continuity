#!/usr/bin/env python3
"""Deterministic, local evidence reports. Publication and scheduling are separate."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path

FORMAT = 'ContinuityEvidence/1'
PUBLIC_KINDS = {'software', 'recovery', 'creative'}
METRICS = {
    'javascript_tests': 'JavaScript checks passed', 'python_tests': 'Python checks passed',
    'java_tests': 'Java host checks passed', 'ci_workflows': 'CI workflows passed',
    'restored_records': 'Archived database records restored and verified',
    'restored_collections': 'Database collections restored', 'exact_poem_reads': 'Exact archived poem reads verified',
    'canonical_files': 'Selected canonical text files verified', 'witnesses': 'Source witness hashes verified',
    'book_placements': 'Book placements verified', 'book_files': 'Book release files hash verified',
    'backup_members': 'Recovery members verified',
}

def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(',', ':'), allow_nan=False).encode()).hexdigest()

def read(path):
    def pairs(items):
        result = {}
        for k, v in items:
            if k in result: raise ValueError('Duplicate JSON key')
            result[k] = v
        return result
    return json.loads(Path(path).read_text(), object_pairs_hook=pairs)

def validate(data, root):
    if data.get('format') != FORMAT or not isinstance(data.get('events'), list):
        raise ValueError('Unsupported evidence format')
    root = Path(root).resolve(); seen = set()
    for event in data['events']:
        eid = event.get('id')
        if not isinstance(eid, str) or not eid or eid in seen: raise ValueError('Missing or duplicate event ID')
        seen.add(eid)
        at = datetime.datetime.fromisoformat(event['verified_at'].replace('Z', '+00:00'))
        if at.tzinfo is None: raise ValueError('Evidence timestamp requires timezone')
        if event['status'] not in {'VERIFIED', 'PREPARED', 'BLOCKED'}: raise ValueError('Invalid evidence status')
        if not event.get('sources'): raise ValueError('Evidence needs at least one source')
        for source in event['sources']:
            relative = Path(source['path'])
            path = root / relative
            if relative.is_absolute() or '..' in relative.parts or path.is_symlink():
                raise ValueError('Unsafe source path')
            resolved = path.resolve()
            if root not in resolved.parents or not resolved.is_file(): raise ValueError('Source outside evidence root')
            if any(p.is_symlink() for p in [path, *path.parents] if p != root): raise ValueError('Symlink source')
            if hashlib.sha256(path.read_bytes()).hexdigest() != source['sha256']:
                raise ValueError('Source hash mismatch')
        for name, value in event.get('metrics', {}).items():
            if name not in METRICS or type(value) is not int or value < 0 or value > 10**9:
                raise ValueError('Invalid metric')
    return data

def render(data, policy):
    if policy.get('format') != 'ContinuityPublicPolicy/1': raise ValueError('Unsupported public policy')
    approvals = policy.get('events', {})
    if not isinstance(approvals, dict): raise ValueError('Invalid policy')
    current = [e for e in data['events'] if not e.get('superseded_by')]
    private = ['# Proof of Progress — private', '', 'Generated from preserved structured evidence. Prepared work is not a submitted or published outcome.', '']
    public = ['# Proof of Progress', '', 'Verified software, archive recovery and creative-production milestones. Test candidates and review editions are not publication or device-acceptance claims.', '']
    exported = 0
    for event in sorted(current, key=lambda e: (e['verified_at'], e['id'])):
        private += [f"## {event['id']} — {event['status']}", '', event['summary'], '',
                    f"Project: {event['project_id']} · verified: {event['verified_at']}", '',
                    'Scope: ' + event.get('scope', 'Not recorded'), '']
        private += [f"- `{s['path']}` — SHA-256 `{s['sha256']}`" for s in event['sources']]
        private += ['']
        approval = approvals.get(event['id'])
        if not approval: continue
        # Policy is separate from imported records; record metadata cannot grant publication.
        if event['kind'] not in PUBLIC_KINDS or event['status'] != 'VERIFIED':
            raise ValueError('Public policy attempts to export a protected or unverified event')
        if approval.get('event_sha256') != digest(event): raise ValueError('Public approval is stale')
        selected = approval.get('metrics')
        if not isinstance(selected, list) or not selected or len(set(selected)) != len(selected):
            raise ValueError('Public policy needs unique approved metrics')
        for name in selected:
            if name not in METRICS or name not in event.get('metrics', {}): raise ValueError('Unknown public metric')
            # Only static template text and bounded integers cross this boundary.
            public.append(f"- {METRICS[name]}: **{event['metrics'][name]}**.")
        exported += 1
    private += ['No background synchronization or external publication is implied.', '']
    public += ['', 'These figures describe separate checks and source populations; they should not be added together as unique works or accomplishments.', '']
    return '\n'.join(private), '\n'.join(public), exported

def generate(data_path, policy_path, root, out):
    data = validate(read(data_path), root); policy = read(policy_path)
    private, public, exported = render(data, policy)
    out = Path(out); out.mkdir(parents=True, exist_ok=True)
    files = {'PROOF_OF_PROGRESS_PRIVATE.md': private, 'PROOF_OF_PROGRESS_PUBLIC_SAFE.md': public}
    for name, content in files.items(): (out/name).write_text(content)
    manifest = {'format': 'ContinuityProofReceipt/1', 'evidence_sha256': digest(data),
                'policy_sha256': digest(policy), 'events': len(data['events']), 'public_events': exported,
                'publication_performed': False, 'files': {name: hashlib.sha256(text.encode()).hexdigest() for name, text in files.items()}}
    (out/'PROOF_GENERATION_RECEIPT.json').write_text(json.dumps(manifest, indent=2)+'\n')
    return manifest

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence', type=Path, required=True); p.add_argument('--policy', type=Path, required=True)
    p.add_argument('--source-root', type=Path, required=True); p.add_argument('--out', type=Path, required=True)
    a = p.parse_args(); print(json.dumps(generate(a.evidence, a.policy, a.source_root, a.out), indent=2))
