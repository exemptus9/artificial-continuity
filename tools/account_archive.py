#!/usr/bin/env python3
"""Bounded, offline ChatGPT export adapter for the existing Continuity core.

Raw ZIP/JSON objects and immutable import reports live in the same object store
and journal as captures. Nothing is published or sent to a model. Reports are
derivatives, never original conversation transcripts or account-wide censuses.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path, PurePosixPath
import tempfile
import zipfile

import context_store as C
import capture_context as G

FORMAT = 'ContinuityAccountImport/1'
EXPORT = 'ContinuityAccountExport/1'
REPORT = 'ContinuityAccountReport/1'
MAX_FILE = 1_000_000_000
MAX_JSON = 64_000_000
MAX_NODES = 100_000


def strict_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise C.ContextError('Duplicate JSON key; refusing ambiguous source.')
        result[key] = value
    return result


def stamp(value):
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return None
    try:
        d = datetime.fromtimestamp(value, timezone.utc)
        return d.isoformat() if 1970 <= d.year < 2200 else None
    except (ValueError, OverflowError, OSError):
        return None


def identifier(value):
    return value if isinstance(value, str) and 0 < len(value) <= 500 else ''


def read_export(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_FILE:
        raise C.ContextError('Use a regular export file up to 1 GB.')
    original_hash = G.file_hash(path)
    member = None
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            if len(z.infolist()) > 100_000:
                raise C.ContextError('ZIP has too many members.')
            choices = [x for x in z.infolist() if PurePosixPath(x.filename).name == 'conversations.json' and not x.is_dir()]
            if len(choices) != 1:
                raise C.ContextError('ZIP must have exactly one conversations.json member.')
            info = choices[0]; name = PurePosixPath(info.filename)
            if name.is_absolute() or '..' in name.parts or '\\' in info.filename or info.flag_bits & 1:
                raise C.ContextError('Unsafe or encrypted conversation member.')
            if info.file_size > MAX_JSON or info.file_size > max(1, info.compress_size) * 1000:
                raise C.ContextError('Conversation JSON exceeds 64 MB or compression-ratio limit.')
            with z.open(info) as stream:
                raw = stream.read(MAX_JSON + 1)
            member = info.filename
    else:
        with path.open('rb') as stream:
            raw = stream.read(MAX_JSON + 1)
    if len(raw) > MAX_JSON:
        raise C.ContextError('Conversation JSON exceeds 64 MB; no partial import performed.')
    if G.file_hash(path) != original_hash:
        raise C.ContextError('Export changed while reading.')
    try:
        value = json.loads(raw.decode('utf-8-sig'), object_pairs_hook=strict_pairs,
                           parse_constant=lambda x: (_ for _ in ()).throw(ValueError('Non-finite JSON number')))
        chats = value if isinstance(value, list) else value.get('conversations') if isinstance(value, dict) else None
        if not isinstance(chats, list) or not 1 <= len(chats) <= 10_000:
            raise C.ContextError('Expected 1–10,000 mapping conversations.')
        # Reject unpaired Unicode before any object or journal write.
        json.dumps(value, ensure_ascii=False, allow_nan=False).encode('utf-8')
    except (ValueError, UnicodeError, RecursionError) as exc:
        raise C.ContextError('Malformed or unsupported UTF-8 JSON.') from exc
    return {'path': path, 'sha256': original_hash, 'json_sha256': G.raw_hash(raw),
            'json_bytes': raw, 'member': member, 'chats': chats}


def text_body(message):
    content = message.get('content')
    if not isinstance(content, dict):
        return None, 1
    parts = content.get('parts')
    if isinstance(parts, list):
        texts = [p for p in parts if isinstance(p, str)]
        return ('\n'.join(texts) if texts else None), sum(not isinstance(p, str) for p in parts)
    if isinstance(content.get('text'), str):
        return content['text'], 0
    return None, 1


def catalog(export):
    counts = Counter(identifier(c.get('id') or c.get('conversation_id')) for c in export['chats'] if isinstance(c, dict))
    rows = []; total = 0
    for i, chat in enumerate(export['chats']):
        if not isinstance(chat, dict) or not isinstance(chat.get('mapping'), dict) or not chat['mapping']:
            raise C.ContextError('Conversation %s lacks a supported nonempty mapping.' % i)
        mapping = chat['mapping']; total += len(mapping)
        if total > MAX_NODES or any(not identifier(k) for k in mapping):
            raise C.ContextError('Message-node limit or identifier validation failed.')
        cid = identifier(chat.get('id') or chat.get('conversation_id'))
        scope = ['provider', cid] if cid and counts[cid] == 1 else ['file', export['json_sha256'], i]
        warnings = [] if scope[0] == 'provider' else ['Missing or repeated conversation ID: identity is file-scoped, not merged across exports.']
        parents = set(); graph_ok = True
        for key, node in mapping.items():
            if not isinstance(node, dict) or node.get('message') is not None and not isinstance(node['message'], dict):
                raise C.ContextError('Malformed mapping node.')
            parent = node.get('parent')
            if parent is not None:
                if not isinstance(parent, str) or parent not in mapping:
                    graph_ok = False
                else:
                    parents.add(parent)
        done = set()
        for start in mapping:
            seen = set(); k = start
            while isinstance(k, str) and k in mapping and k not in done:
                if k in seen:
                    graph_ok = False; break
                seen.add(k); k = mapping[k].get('parent')
            done.update(seen)
        if not graph_ok:
            warnings.append('Broken or cyclic parent graph: raw source preserved; no linear conversation order asserted.')
        rows.append({'index': i, 'conversation_id': cid, 'identity_scope': scope,
                     'title': str(chat.get('title') or 'Untitled conversation')[:200],
                     'created_at': stamp(chat.get('create_time')), 'updated_at': stamp(chat.get('update_time')),
                     'current_node': chat.get('current_node') if isinstance(chat.get('current_node'), str) and chat['current_node'] in mapping else None,
                     'branch_tips': [k for k in mapping if k not in parents], 'graph_valid': graph_ok,
                     'warnings': warnings, 'message_count': sum(n.get('message') is not None for n in mapping.values())})
    return rows


def message_identity(row, node_id, message):
    return C.digest([row['identity_scope'], node_id, identifier(message.get('id')) or node_id])


def put_bytes(store, raw):
    # The existing journal writer is intentionally bounded at 32 MB. Large raw
    # originals use streaming object storage, without weakening that boundary.
    with tempfile.NamedTemporaryFile(dir=store.root, prefix='account-intake-', delete=True) as f:
        f.write(raw); f.flush()
        return store.put_file(f.name, expected=G.raw_hash(raw))


def ingest(store, path, selected=None, approved=False, expected=None, project=None):
    if not approved:
        raise C.ContextError('Explicit private-import approval is required.')
    export = read_export(path); rows = catalog(export)
    chosen = set(range(len(rows))) if selected is None else set(selected)
    if not chosen or any(type(i) is not int or i < 0 or i >= len(rows) for i in chosen):
        raise C.ContextError('Select valid zero-based conversation indices.')
    journal = C.read_json(store.journal); data, head = C.load_store(journal)
    expected = expected or head['store_sha256']
    if expected != head['store_sha256']:
        raise C.ContextError('Stale context: re-read before importing.')
    if project is not None and project not in {p['id'] for p in data['projects']}:
        raise C.ContextError('Unknown project.')
    key = C.digest([export['sha256'], sorted(chosen), project])
    report_id = 'ACCOUNT.REPORT.' + key
    known = {s['id']: s for s in data['sources']}
    if report_id in known:
        report = json.loads(store.object(known[report_id]['sha256']))
        for digest in (export['sha256'], export['json_sha256']):
            path = store.objects / digest
            if path.is_symlink() or not path.is_file() or G.file_hash(path) != digest:
                raise C.ContextError('Original export object missing or corrupt; restore before retry.')
        for row in report['conversations']:
            for m in row['messages']:
                if m['source_id']:
                    store.object(known[m['source_id']]['sha256'])
        report['already_imported'] = True
        return report
    at = G.now(); records = []; pending_objects = {}; by_identity = {}
    for source in data['sources']:
        identity = source.get('origin', {}).get('account_identity')
        if identity:
            by_identity.setdefault(identity, []).append(source['id'])
    report = {'format': FORMAT, 'export_sha256': export['sha256'], 'json_sha256': export['json_sha256'],
              'imported_at': at, 'selected_indices': sorted(chosen), 'project_id': project,
              'conversations': [], 'added_messages': 0, 'reused_messages': 0,
              'account_total': None, 'account_completeness': None, 'publication': 'unapproved',
              'scope': 'Coverage is relative to this supplied file. Calendar gaps, deleted chats and unseen exports cannot be inferred.',
              'encryption': 'Working objects, journal and backups are plaintext. Use an encrypted disk/container for sensitive archives.'}
    for row in rows:
        summary = dict(row); summary['selected'] = row['index'] in chosen; summary['messages'] = []
        for node_id, node in export['chats'][row['index']]['mapping'].items():
            message = node.get('message')
            if message is None:
                continue
            identity = message_identity(row, node_id, message)
            witness = C.digest(node); sid = 'CHATGPT.MSG.' + C.digest([identity, witness])
            role = message.get('author', {}).get('role') if isinstance(message.get('author'), dict) else None
            body, omitted = text_body(message)
            ref = {'node_id': node_id, 'message_id': identifier(message.get('id')) or None,
                   'parent_id': node.get('parent'), 'role': role, 'observed_at': stamp(message.get('create_time')),
                   'identity': identity, 'witness_sha256': witness, 'source_id': None,
                   'omitted_parts': omitted, 'state': 'unselected'}
            metadata = message.get('metadata') or {}
            internal = role not in ('user', 'assistant') or not isinstance(metadata, dict)
            if isinstance(metadata, dict) and metadata.get('is_visually_hidden_from_conversation') is True:
                internal = True
            if role == 'assistant' and message.get('channel') not in (None, 'final', 'all'):
                internal = True
            if not summary['selected']:
                pass
            elif internal:
                ref['state'] = 'raw_only_internal'
            elif body is None or not body.strip():
                ref['state'] = 'raw_only_nontext'
            elif '\x00' in body or len(body.encode('utf-8')) > G.MAX_TEXT_BYTES:
                ref['state'] = 'raw_only_oversized_or_unsupported'
            elif sid in known:
                ref.update(state='indexed', source_id=sid); report['reused_messages'] += 1
                if store.object(known[sid]['sha256']) != body.encode('utf-8'):
                    raise C.ContextError('Existing message object differs; restore before import.')
            else:
                raw = body.encode('utf-8'); digest = G.raw_hash(raw); pending_objects[digest] = raw
                source = {'id': sid, 'format': G.SOURCE_FORMAT, 'name': row['title'],
                          'locator': 'continuity-object:sha256:' + digest, 'sha256': digest, 'byte_count': len(raw),
                          'media_type': 'text/plain; charset=utf-8', 'project_id': project, 'visibility': 'private',
                          'source_kind': 'USER_ASSERTION' if role == 'user' else 'MODEL_SUMMARY',
                          'speaker': role, 'observed_at': ref['observed_at'], 'received_at': at,
                          'publication_status': 'unapproved', 'approved_for_publication_at': None,
                          'review_state': 'unreviewed', 'creator_role': 'account-adapter',
                          'origin': {'provider': 'chatgpt', 'account_identity': identity, 'conversation_id': row['conversation_id'],
                                     'node_id': node_id, 'message_id': ref['message_id'], 'parent_id': ref['parent_id'],
                                     'export_sha256': export['sha256'], 'json_sha256': export['json_sha256'],
                                     'conversation_index': row['index'], 'witness_sha256': witness,
                                     'variants': by_identity.get(identity, []), 'omitted_parts': omitted},
                          'verification': 'Derived text projection of original mapping node; string parts joined with LF. Raw export controls exact structure. Imported authorship/time/claims unverified; variants are not silently superseded.'}
                records.append(source); known[sid] = source
                by_identity.setdefault(identity, []).append(sid)
                ref.update(state='indexed', source_id=sid); report['added_messages'] += 1
            summary['messages'].append(ref)
        report['conversations'].append(summary)
    report['counts'] = dict(Counter(m['state'] for r in report['conversations'] for m in r['messages']))
    raw_report = json.dumps(report, indent=2, ensure_ascii=False, allow_nan=False).encode('utf-8')
    for digest, name, format_, size in [(export['sha256'], 'Original account export', EXPORT, export['path'].stat().st_size),
                                      (export['json_sha256'], 'Original conversation JSON', EXPORT, len(export['json_bytes'])),
                                      (G.raw_hash(raw_report), 'Account import coverage report', REPORT, len(raw_report))]:
        sid = report_id if format_ == REPORT else 'ACCOUNT.OBJECT.' + digest
        if sid in known:
            continue
        source = {'id': sid, 'format': format_, 'name': name, 'sha256': digest, 'byte_count': size,
                  'locator': 'continuity-object:sha256:' + digest, 'visibility': 'private',
                  'source_kind': 'MODEL_SUMMARY' if format_ == REPORT else 'SOURCE_FACT',
                  'authority_scope': 'Adapter-derived import coverage' if format_ == REPORT else 'Exact supplied bytes only; not truth of embedded claims',
                  'publication_status': 'unapproved', 'received_at': at}
        records.append(source); known[sid] = source
    proposal = {'id': 'ACCOUNT.IMPORT.' + key, 'at': at, 'actor': 'owner',
                'note': 'Preserved supplied account export and private message witnesses; no project decisions or publication approval inferred.',
                'evidence_grade': 'C', 'source_refs': [report_id], 'source_records': records, 'updates': [], 'additions': []}
    candidate, _ = C.append(journal, proposal, expected, True)
    if len(json.dumps(candidate, ensure_ascii=False, indent=2).encode('utf-8')) + 1 > C.MAX_BYTES:
        raise C.ContextError('Import would exceed the 32 MB journal capacity; no partial import. Select fewer conversations.')
    store.put_file(export['path'], expected=export['sha256'])
    put_bytes(store, export['json_bytes'])
    for digest, raw in pending_objects.items():
        store.put_object(raw)
    put_bytes(store, raw_report)
    # Concurrent journal changes reject the whole proposal. Immutable orphan
    # objects are recoverable, never counted as an acknowledged import.
    C.apply_file(store.journal, proposal, expected, True)
    return report


def coverage(store):
    data, head = store.state(); reports = []; by_export = {}; identities = {}
    for source in data['sources']:
        if source.get('format') != REPORT:
            continue
        report = json.loads(store.object(source['sha256']))
        reports.append(report)
        group = by_export.setdefault(report['export_sha256'], {'export_sha256': report['export_sha256'], 'conversations': {}})
        for row in report['conversations']:
            existing = group['conversations'].setdefault(row['index'], {**row, 'messages': {}})
            existing['selected'] = existing['selected'] or row['selected']
            for m in row['messages']:
                prior = existing['messages'].get(m['node_id'])
                if prior is None or m['state'] == 'indexed' or prior['state'] == 'unselected':
                    existing['messages'][m['node_id']] = m
                if m['state'] == 'indexed':
                    identities.setdefault(m['identity'], set()).add(m['witness_sha256'])
    outputs = []
    for group in by_export.values():
        rows = []
        for row in group['conversations'].values():
            messages = list(row.pop('messages').values())
            rows.append({**row, 'indexed_messages': sum(m['state'] == 'indexed' for m in messages),
                         'remaining': [m for m in messages if m['state'] != 'indexed']})
        outputs.append({'export_sha256': group['export_sha256'], 'conversations': rows})
    return {'format': 'ContinuityAccountCoverage/1', 'journal_sha256': head['store_sha256'],
            'exports': outputs, 'distinct_message_identities': len(identities),
            'message_witnesses': sum(map(len, identities.values())),
            'variant_identities': sum(len(v) > 1 for v in identities.values()),
            'account_total': None, 'account_completeness': None,
            'limitation': 'Only supplied files are measurable. Raw-only/internal/media nodes are retained but not searchable text. Variants are preserved without choosing an authoritative version.'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='command', required=True)
    preview = sub.add_parser('preview'); preview.add_argument('file')
    imp = sub.add_parser('import'); imp.add_argument('--root', required=True); imp.add_argument('file')
    imp.add_argument('--index', type=int, action='append'); imp.add_argument('--project')
    imp.add_argument('--expected-head'); imp.add_argument('--approve-private', action='store_true')
    cov = sub.add_parser('coverage'); cov.add_argument('--root', required=True)
    args = p.parse_args()
    try:
        if args.command == 'preview':
            e = read_export(args.file)
            result = {'format': 'ContinuityAccountPreview/1', 'sha256': e['sha256'],
                      'json_bytes': len(e['json_bytes']), 'zip_member': e['member'], 'conversations': catalog(e),
                      'notice': 'Read-only preview. No private copy, indexing, publication or account access performed.'}
        elif args.command == 'import':
            result = ingest(G.Store(args.root), args.file, args.index, args.approve_private, args.expected_head, args.project)
        else:
            result = coverage(G.Store(args.root))
        print(json.dumps(result, indent=2, ensure_ascii=False))
    except (C.ContextError, OSError, zipfile.BadZipFile) as exc:
        p.exit(2, 'Not completed: ' + str(exc) + '\n')


if __name__ == '__main__':
    main()
