"""Fictional reviewed-audio vectors; isolated private stores, no user data."""
import contextlib
import copy
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import context_store as C
import capture_context as G
import reviewed_audio as A
FIXTURE = Path(__file__).parent / 'fixtures' / 'reviewed-audio-golden.json'

def fixture(two=False):
    payload = A.loads_handoff(FIXTURE.read_bytes())
    if two:
        e = copy.deepcopy(payload['excerpts'][0]); e['segment']['sequence'] = 2
        e['text'] = {'machine': 'Fictional unselected cactus.', 'reviewed': None, 'effective': 'Fictional unselected cactus.'}
        e['review']['note'] = ''
        e['review']['history'] = [{'revised_at': payload['exported_at'],
                                  'old': {'edited_text': None, 'verified': False, 'pinned': False, 'note': ''},
                                  'new': {'edited_text': None, 'verified': True, 'pinned': True, 'note': ''}}]
        e['id'] = A.excerpt_id(e['source']['sha256'], e['transcript']['fingerprint_sha256'], 2)
        e['revision_sha256'] = A.revision_digest(e); payload['excerpts'].append(e)
        payload['selection']['count'] = 2; payload['bundle_sha256'] = A.bundle_digest(payload['excerpts'])
    return payload

class ReviewedAudioTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.base = Path(self.tmp.name)
        original = self.base / 'original.json'
        self.baseline = {'projects': [{'id': 'FICTION', 'name': 'Fictional task', 'state': 'ACTIVE', 'confidence': 'C', 'sources': []}], 'sources': []}
        C.json_write(original, C.init_store(self.baseline))
        self.store = G.Store.initialize(self.base / 'private', original); self.owner = G.Principal('owner')
    def tearDown(self): self.tmp.cleanup()
    def test_select_import_search_recovery_and_idempotency(self):
        payload = fixture(True); raw = A.dumps_handoff(payload); chosen = payload['excerpts'][0]
        preview = self.store.audio_preview(raw); self.assertEqual(len(preview['excerpts']), 2)
        self.assertEqual(self.store.state()[1]['revision'], 0)
        result = self.store.import_audio(raw, [chosen['id']], 'FICTION', True)
        sid = result['receipts'][0]['source_id']; self.assertEqual(result['copied_sources'], 1)
        source = self.store.source(sid, self.owner)
        self.assertEqual(source['original_text'], chosen['text']['effective'])
        self.assertEqual(source['audio_provenance'], chosen)
        self.assertNotIn('audio_provenance', self.store.source(sid, self.owner, include_text=False))
        self.assertEqual(source['source']['publication_status'], 'unapproved')
        self.assertEqual(source['source']['source_kind'], 'UNKNOWN')
        self.assertEqual(self.store.state()[0]['projects'], self.baseline['projects'])
        self.assertTrue(self.store.search('reviewed', self.owner)); self.assertEqual(self.store.search('cactus', self.owner), [])
        self.assertFalse(any(b'cactus' in p.read_bytes() for p in self.store.objects.iterdir()))
        self.assertEqual(self.store.import_audio(raw, [chosen['id']], 'FICTION', True)['skipped_duplicates'], 1)
        # Export wall-clock is transport metadata, not a new reviewed source.
        payload['exported_at'] = '2026-11-03T01:00:00Z'
        self.assertEqual(self.store.import_audio(A.dumps_handoff(payload), [chosen['id']], 'FICTION', True)['skipped_duplicates'], 1)
        backup = self.base / 'backup'; self.store.backup(backup); G.Store.verify_backup(backup)
        restored = self.base / 'restored'; G.Store.restore_backup(backup, restored)
        recovered = G.Store(restored)
        self.assertEqual(recovered.source(sid, self.owner), source)
        self.assertTrue(recovered.search('reviewed', self.owner))
        self.assertEqual(recovered.import_audio(raw, [chosen['id']], 'FICTION', True)['skipped_duplicates'], 1)
    def test_explicit_selection_approval_private_scope_and_hashes(self):
        payload = fixture(); raw = A.dumps_handoff(payload); eid = payload['excerpts'][0]['id']; before = self.store.journal.read_bytes()
        for selected, approved in [([eid], False), ([], True), ([eid, eid], True), (['unknown'], True)]:
            with self.assertRaises(C.ContextError): self.store.import_audio(raw, selected, 'FICTION', approved)
        with self.assertRaises(C.ContextError): self.store.import_audio(raw, [eid], 'missing', True)
        self.assertEqual(self.store.journal.read_bytes(), before)
        payload['excerpts'][0]['text']['effective'] += 'tampered'
        with self.assertRaises(ValueError): self.store.import_audio(json.dumps(payload).encode(), [eid], None, True)
        self.assertEqual(self.store.journal.read_bytes(), before)
    def test_revised_reviews_coexist_and_remain_unreviewed_locally(self):
        payload = fixture(); e = payload['excerpts'][0]
        self.store.import_audio(A.dumps_handoff(payload), [e['id']], None, True)
        previous = copy.deepcopy(e['review']['history'][-1]['new']); e['text']['reviewed'] = e['text']['effective'] = 'Revised fictional audio evidence.'
        e['review']['history'].append({'revised_at': '2026-11-03T02:00:00Z', 'old': previous, 'new': {**previous, 'edited_text': e['text']['reviewed']}})
        e['revision_sha256'] = A.revision_digest(e); payload['bundle_sha256'] = A.bundle_digest(payload['excerpts'])
        self.assertEqual(self.store.import_audio(A.dumps_handoff(payload), [e['id']], None, True)['copied_sources'], 1)
        sources = self.store.state()[0]['sources']; self.assertEqual(len(sources), 2)
        self.assertTrue(all(s['review_state'] == 'unreviewed' and s['speaker'] == 'unknown' for s in sources))
    def test_backup_requires_exact_selected_provenance_object(self):
        p = fixture(); r = self.store.import_audio(A.dumps_handoff(p), [p['excerpts'][0]['id']], None, True)
        src = self.store.source(r['receipts'][0]['source_id'], self.owner)['source']
        (self.store.objects / src['origin']['excerpt_object_sha256']).unlink()
        with self.assertRaises(C.ContextError): self.store.backup(self.base / 'broken-backup')
    def test_cli_preview_and_approved_selection(self):
        p = fixture(); path = self.base / 'reviewed.json'; path.write_bytes(A.dumps_handoff(p)); output = io.StringIO()
        with contextlib.redirect_stdout(output): code = G.main(['--root', str(self.store.root), 'import-audio', str(path)])
        self.assertEqual(code, 0); self.assertEqual(self.store.state()[1]['revision'], 0)
        self.assertEqual(json.loads(output.getvalue())['excerpts'][0]['id'], p['excerpts'][0]['id'])
        with contextlib.redirect_stdout(io.StringIO()): code = G.main(['--root', str(self.store.root), 'import-audio', str(path), '--select', p['excerpts'][0]['id'], '--approve'])
        self.assertEqual(code, 0); self.assertEqual(self.store.state()[1]['revision'], 1)
    def test_private_agent_scope_never_inherits_review_claim(self):
        p = fixture(); r = self.store.import_audio(A.dumps_handoff(p), [p['excerpts'][0]['id']], 'FICTION', True)
        with self.assertRaises(C.ContextError): self.store.source(r['receipts'][0]['source_id'], G.Principal('agent'))
        self.assertEqual(self.store.search('reviewed', G.Principal('agent')), [])

if __name__ == '__main__': unittest.main()
