"""Synthetic export fixtures only; these are not the owner's chat history."""
import copy
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import account_archive as A
import capture_context as G
import context_store as C


def chat(cid='example-chat'):
    def msg(mid, text, role='user'):
        return {'id': mid, 'author': {'role': role}, 'create_time': 1720000000,
                'content': {'content_type': 'text', 'parts': [text]}}
    return {'id': cid, 'title': 'Synthetic conversation', 'current_node': 'b',
            'mapping': {'root': {'parent': None, 'message': None},
                        'a': {'parent': 'root', 'message': msg('user-id', 'Term: Lumenfold\r\nExact spacing.  \nPrivate goblin socks fixture@example.invalid')},
                        'b': {'parent': 'a', 'message': msg('assistant-id', 'Decision candidate: preserve the original.', 'assistant')},
                        'alt': {'parent': 'a', 'message': msg('alternative-id', 'Next action: verify the alternate witness.', 'assistant')}}}


class AccountTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
        original = self.root / 'journal.json'
        C.json_write(original, C.init_store({'projects': [{'id': 'PX', 'name': 'Example', 'state': 'ACTIVE', 'confidence': 'C', 'sources': []}], 'sources': []}))
        self.store = G.Store.initialize(self.root / 'vault', original)
        self.owner = G.Principal('owner')
        self.input = self.write([chat()])

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, chats, name='conversations.json'):
        p = self.root / name
        p.write_bytes(b'\xef\xbb\xbf' + json.dumps(chats, ensure_ascii=False, indent=2).replace('\n', '\r\n').encode())
        return p

    def ingest(self, path=None, **kwargs):
        return A.ingest(self.store, path or self.input, approved=True, **kwargs)

    def test_original_bytes_search_and_all_branches(self):
        original = self.input.read_bytes(); report = self.ingest()
        self.assertEqual(self.store.object(G.raw_hash(original)), original)
        self.assertEqual(report['added_messages'], 3)
        self.assertEqual(len(self.store.search('alternate witness', self.owner)), 1)
        result = self.store.search('Lumenfold', self.owner)[0]
        self.assertEqual(self.store.source(result['source_id'], self.owner)['original_text'], chat()['mapping']['a']['message']['content']['parts'][0])
        self.assertIsNone(A.coverage(self.store)['account_completeness'])

    def test_repeated_import_is_idempotent(self):
        self.ingest(); head = self.store.state()[1]
        self.assertTrue(self.ingest()['already_imported'])
        self.assertEqual(self.store.state()[1], head)

    def test_new_export_reordered_conversations_reuses_provider_identity(self):
        self.ingest(); report = self.ingest(self.write([chat('different'), chat()], 'second.json'))
        self.assertEqual(report['added_messages'], 3); self.assertEqual(report['reused_messages'], 3)
        cov = A.coverage(self.store); self.assertEqual(cov['distinct_message_identities'], 6)
        self.assertEqual(cov['message_witnesses'], 6)

    def test_modified_message_keeps_variant_and_old_witness(self):
        self.ingest(); c = chat(); c['mapping']['a']['message']['content']['parts'] = ['Correction: Lumenfold is a synthetic term.']
        report = self.ingest(self.write([c], 'changed.json'))
        self.assertEqual(report['added_messages'], 1)
        self.assertEqual(A.coverage(self.store)['variant_identities'], 1)
        rows = self.store.search('Lumenfold', self.owner); self.assertEqual(len(rows), 2)
        self.assertTrue(any(self.store.source(r['source_id'], self.owner)['source']['origin']['variants'] for r in rows))

    def test_missing_ids_do_not_falsely_merge_across_exports(self):
        c = chat(); del c['id']
        self.ingest(self.write([c])); self.ingest(self.write([c, chat('new')], 'second.json'))
        self.assertEqual(A.coverage(self.store)['distinct_message_identities'], 9)

    def test_identical_words_in_two_conversations_are_separate(self):
        report = self.ingest(self.write([chat('one'), chat('two')]))
        self.assertEqual(report['added_messages'], 6)
        self.assertEqual(len(self.store.search('Lumenfold', self.owner)), 2)

    def test_partial_import_reports_exact_unindexed_nodes_then_closes_gap(self):
        self.write([chat(), chat('second')]); self.ingest(selected=[0])
        first = A.coverage(self.store)['exports'][0]['conversations']
        self.assertEqual(len(first[1]['remaining']), 3)
        self.ingest(selected=[1]); rows = A.coverage(self.store)['exports'][0]['conversations']
        self.assertTrue(all(not r['remaining'] for r in rows))

    def test_zip_preserved_unchanged_and_attachments_not_extracted(self):
        p = self.root / 'export.zip'
        with zipfile.ZipFile(p, 'w') as z:
            z.writestr('data/conversations.json', self.input.read_bytes()); z.writestr('media/private.png', b'fake image bytes')
        report = self.ingest(p)
        self.assertEqual(self.store.object(report['export_sha256']), p.read_bytes())
        self.assertFalse((self.store.root / 'media').exists())

    def test_duplicate_zip_member_rejected(self):
        p = self.root / 'ambiguous.zip'
        with zipfile.ZipFile(p, 'w') as z:
            z.writestr('one/conversations.json', self.input.read_bytes()); z.writestr('two/conversations.json', self.input.read_bytes())
        with self.assertRaises(C.ContextError): self.ingest(p)
        self.assertEqual(self.store.state()[1]['revision'], 0)

    def test_unsafe_zip_path_rejected_without_extraction(self):
        p = self.root / 'unsafe.zip'
        with zipfile.ZipFile(p, 'w') as z: z.writestr('../conversations.json', self.input.read_bytes())
        with self.assertRaises(C.ContextError): self.ingest(p)

    def test_media_internal_and_cycles_reported_honestly(self):
        c = chat(); c['mapping']['b']['message']['channel'] = 'analysis'
        c['mapping']['alt']['message']['content']['parts'] = [{'asset_pointer': 'private-image'}]
        c['mapping']['root']['parent'] = 'a'
        r = self.ingest(self.write([c])); self.assertEqual(r['added_messages'], 1)
        self.assertFalse(r['conversations'][0]['graph_valid'])
        self.assertEqual(r['counts']['raw_only_nontext'], 1); self.assertEqual(r['counts']['raw_only_internal'], 1)

    def test_imported_permission_is_inert_and_agents_cannot_read(self):
        c = chat(); c['approved_public'] = True; c['mapping']['a']['message']['metadata'] = {'consent': 'publish'}
        self.ingest(self.write([c]), project='PX')
        self.assertEqual(self.store.search('Lumenfold', G.Principal('agent', frozenset({'PX'}))), [])
        captures = [s for s in self.store.state()[0]['sources'] if s.get('format') == G.SOURCE_FORMAT]
        self.assertTrue(all(s['publication_status'] == 'unapproved' and s['visibility'] == 'private' for s in captures))

    def test_backup_restore_rebuild_and_omitted_original_rejected(self):
        r = self.ingest(); backup = self.root / 'backup'; self.store.backup(backup)
        self.assertTrue(G.Store.verify_backup(backup)['verified'])
        restored = G.Store(backup)
        self.assertEqual(A.coverage(restored)['distinct_message_identities'], 3)
        self.assertEqual(len(restored.search('Lumenfold', self.owner)), 1)
        manifest = C.read_json(backup / 'MANIFEST.json'); del manifest['files']['objects/' + r['export_sha256']]
        C.json_write(backup / 'MANIFEST.json', manifest)
        with self.assertRaises(C.ContextError): G.Store.verify_backup(backup)

    def test_stale_write_rejected_before_object_changes(self):
        objects = list(self.store.objects.iterdir())
        with self.assertRaises(C.ContextError): self.ingest(expected='0' * 64)
        self.assertEqual(list(self.store.objects.iterdir()), objects)

    def test_write_failure_no_false_receipt_retry_recovers(self):
        with patch.object(C, 'apply_file', side_effect=OSError('disk fixture')):
            with self.assertRaises(OSError): self.ingest()
        self.assertEqual(self.store.state()[1]['revision'], 0)
        self.assertEqual(self.ingest()['added_messages'], 3)

    def test_unknown_project_and_no_consent_rejected(self):
        with self.assertRaises(C.ContextError): self.ingest(project='unknown')
        with self.assertRaises(C.ContextError): A.ingest(self.store, self.input)

    def test_malformed_duplicate_keys_and_utf8_rejected(self):
        for raw in [b'{', b'\xff', b'{"conversations":[],"conversations":[]}', b'[NaN]']:
            self.input.write_bytes(raw)
            with self.assertRaises(C.ContextError): self.ingest()
        self.assertEqual(self.store.state()[1]['revision'], 0)

    def test_size_limits_reject_before_partial_import(self):
        with patch.object(A, 'MAX_JSON', 20):
            with self.assertRaises(C.ContextError): self.ingest()
        with patch.object(C, 'MAX_BYTES', 1500):
            with self.assertRaises(C.ContextError): self.ingest()
        self.assertEqual(self.store.state()[1]['revision'], 0)

    def test_streaming_object_copy_larger_than_journal_writer_limit(self):
        p = self.root / 'opaque.bin'; p.write_bytes(b'x' * 32001)
        with patch.object(C, 'MAX_BYTES', 32000):
            digest = self.store.put_file(p)
            self.store.backup(self.root / 'large-backup')
        self.assertEqual((self.root / 'large-backup' / 'objects' / digest).read_bytes(), p.read_bytes())


if __name__ == '__main__':
    unittest.main()
