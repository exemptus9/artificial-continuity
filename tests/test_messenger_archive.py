"""Synthetic Messenger fixtures; no private records belong in public CI."""
import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import capture_context as G
import context_store as C
import messenger_archive as M


def html(body='First line<br /> \n<br />  Second &amp; 😀', sender='Example Owner', stamp='Sep 09, 2023 8:34:13am', extra=''):
    return '<html><head><meta charset="utf-8"><title>Synthetic thread</title></head><body><div class="_a6-g"><div class="_a6-h">' + sender + '</div><div class="_a6-p"><div><div></div><div>' + body + '</div><div>' + extra + '</div><div></div></div></div><div class="_a72d">' + stamp + '</div></div></body></html>'


class MessengerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
        original = self.root / 'journal.json'
        C.json_write(original, C.init_store({'projects': [{'id': 'PX', 'name': 'Synthetic', 'state': 'ACTIVE', 'confidence': 'C'}], 'sources': []}))
        self.store = G.Store.initialize(self.root / 'vault', original)
        self.owner = G.Principal('owner')
        self.path = self.write(html())

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, value, name='message.html'):
        path = self.root / name
        path.write_bytes(value.encode('utf-8') if isinstance(value, str) else value)
        return path

    def ingest(self, path=None, **kw):
        return M.ingest(self.store, path or self.path, ['Example Owner'], approved=True, **kw)

    def test_exact_raw_bytes_unicode_spaces_stanzas_entities_and_timezone(self):
        raw = self.path.read_bytes(); report = self.ingest(); row = report['messages'][0]
        self.assertEqual(self.store.object(G.raw_hash(raw)), raw)
        self.assertEqual(row['body'], 'First line\n \n\n  Second & 😀')
        self.assertEqual(row['body_sha256'], G.raw_hash(row['body'].encode()))
        self.assertIsNone(row['observed_at'])
        self.assertEqual(row['datetime_local_unspecified_zone'], '2023-09-09T08:34:13')
        self.assertEqual(row['timezone_provenance'], 'not-recorded')
        self.assertFalse(row['style_eligible']); self.assertFalse(row['publication_approved'])
        self.assertEqual(M.verify_report(self.store, report)['messages'], 1)

    def test_determinism_idempotence_and_exact_jsonl_round_trip(self):
        before = M.read_export(self.path, ['Example Owner'])
        report = self.ingest(); head = self.store.state()[1]
        self.assertTrue(self.ingest()['already_imported']); self.assertEqual(self.store.state()[1], head)
        after = M.read_export(self.path, ['Example Owner'])
        self.assertEqual(before['messages'], after['messages'])
        exported = [json.loads(line) for line in M.export_jsonl(self.store).splitlines()]
        self.assertEqual(exported, report['messages'])

    def test_duplicate_occurrences_and_distinct_files_are_independent_witnesses(self):
        fragment = html('Identical words')
        content = fragment + fragment
        r1 = self.ingest(self.write(content)); self.assertEqual(len(r1['messages']), 2)
        self.assertNotEqual(r1['messages'][0]['id'], r1['messages'][1]['id'])
        r2 = self.ingest(self.write(fragment, 'second.html'))
        self.assertNotEqual(r1['messages'][0]['id'], r2['messages'][0]['id'])
        self.assertEqual(len(self.store.search('Identical words', self.owner)), 3)

    def test_safe_html_excludes_active_contents_without_mutating_original(self):
        payload = html('Safe<script>DROP TABLE source; fetch("https://example.invalid")</script><img src="javascript:alert(1)" onerror="fetch(1)"><br>End')
        self.write(payload); report = self.ingest(); row = report['messages'][0]
        self.assertEqual(row['body'], 'Safe\nEnd')
        self.assertIn('active_html_retained_raw_only', row['contamination_flags'])
        self.assertIn(b'<script>', self.store.object(report['source_file_sha256']))
        self.assertEqual(row['attachments'][0]['uri'], 'javascript:alert(1)')
        self.assertFalse(row['attachments'][0]['fetched'])

    def test_relative_and_malformed_link_references_are_inert_evidence(self):
        body = '<a href="../files/missing.txt" download>Document</a><a href="http://[">Malformed reference</a>'
        row = self.ingest(self.write(html(body)))['messages'][0]
        self.assertIn('http://[', row['links'])
        self.assertEqual(row['attachments'][0]['uri'], '../files/missing.txt')
        self.assertFalse(row['attachments'][0]['fetched'])

    def test_preview_and_ai_content_cannot_become_user_style(self):
        payload = html('As an AI language model I quote ChatGPT', extra='<a href="https://example.invalid">External preview</a>')
        report = self.ingest(self.write(payload)); row = report['messages'][0]
        self.assertNotIn('External preview', row['body'])
        self.assertIn('External preview', row['link_previews'])
        self.assertEqual(row['authorship'], 'unverified_self_attributed')
        self.assertIn('possible_copied_or_discussed_ai', row['contamination_flags'])
        self.assertFalse(row['style_eligible'])
        bot = self.ingest(self.write(html('Synthetic response', 'Meta AI'), 'bot.html'))['messages'][0]
        self.assertEqual(bot['authorship'], 'AI_GENERATED'); self.assertFalse(bot['style_eligible'])

    def test_platform_disclaimer_and_copied_chatbot_marker_are_not_user_composition(self):
        notice = self.ingest(self.write(html('Messages are generated by AI. Some may be inaccurate.'), 'notice.html'))['messages'][0]
        self.assertEqual(notice['classification'], 'platform_system_event')
        self.assertEqual(notice['authorship'], 'PLATFORM_GENERATED')
        copied = self.ingest(self.write(html('View other drafts<br>Analysis of a synthetic term'), 'copied.html'))['messages'][0]
        self.assertIn('possible_copied_or_discussed_ai', copied['contamination_flags'])
        self.assertFalse(copied['style_eligible'])

    def test_archive_metadata_and_corrupt_timestamp_retained_without_message_loss(self):
        payload = html('Message', stamp='corrupt-date') + '<div class="_a6-g"><div class="_a6-p">Word effects</div></div>'
        report = self.ingest(self.write(payload))
        self.assertEqual(report['counts']['message_entries'], 1)
        self.assertEqual(report['counts']['archive_metadata_entries'], 1)
        self.assertEqual(report['messages'][0]['timestamp_original'], 'corrupt-date')
        self.assertIn('unparsed_timestamp_preserved_verbatim', report['messages'][0]['warnings'])

    def test_json_attachments_reactions_replies_and_utc_preserved(self):
        fixture = {'title': 'Synthetic JSON thread', 'messages': [{'sender_name': 'Example Owner', 'timestamp_ms': 1720000000123,
                   'content': 'Line\r\n\r\n  😀', 'photos': [{'uri': '../photos/missing.jpg'}],
                   'reactions': [{'reaction': '😀', 'actor': 'Example Person'}], 'reply_to_message_id': 'previous',
                   'is_unsent': False, 'future_field': {'opaque': True}}]}
        path = self.write(json.dumps(fixture, ensure_ascii=False), 'message.json')
        row = self.ingest(path)['messages'][0]
        self.assertEqual(row['body'], fixture['messages'][0]['content'])
        self.assertEqual(row['observed_at'], '2024-07-03T09:46:40.123+00:00')
        self.assertEqual(row['reactions'], fixture['messages'][0]['reactions'])
        self.assertEqual(row['reply_to'], 'previous')
        self.assertEqual(row['original_message_metadata']['future_field'], {'opaque': True})
        self.assertFalse(row['attachments'][0]['fetched'])

    def test_malformed_html_recovered_but_unknown_json_encoding_rejected(self):
        report = self.ingest(self.write(html('Body').replace('</body>', '</unbalanced>')))
        self.assertTrue(any('Malformed HTML' in v for v in report['details']['warnings']))
        with self.assertRaises(C.ContextError):
            self.ingest(self.write(b'{"messages":[],"messages":[]}', 'duplicate.json'))
        with self.assertRaises(C.ContextError):
            self.ingest(self.write(b'<div>\xff</div>', 'bad.html'))

    def test_json_unpaired_surrogates_rejected_before_any_write(self):
        for field in ('content', 'sender_name', 'attachment_metadata'):
            fixture = {'messages': [{'sender_name': 'Example Owner', field: '\ud800'}]}
            before = self.store.state()[1]
            with self.assertRaises(C.ContextError):
                self.ingest(self.write(json.dumps(fixture), 'invalid.json'))
            self.assertEqual(self.store.state()[1], before)

    def test_deleted_json_null_content_preserved_in_raw(self):
        fixture = {'messages': [{'sender_name': 'Example Owner', 'content': None, 'is_unsent': True}]}
        row = self.ingest(self.write(json.dumps(fixture), 'deleted.json'))['messages'][0]
        self.assertEqual(row['body'], '')
        self.assertEqual(row['classification'], 'platform_system_event')
        self.assertFalse(row['style_eligible'])

    def test_stale_head_and_failed_cas_cannot_commit_partial_import(self):
        start = self.store.state()[1]
        with self.assertRaises(C.ContextError):
            self.ingest(expected='0' * 64)
        self.assertEqual(self.store.state()[1], start)
        with patch.object(C, 'apply_file', side_effect=C.ContextError('concurrent write')):
            with self.assertRaises(C.ContextError):
                self.ingest()
        self.assertEqual(self.store.state()[1], start)
        self.assertEqual(self.store.search('First', self.owner), [])

    def test_authorship_or_project_metadata_change_is_not_silent_overwrite(self):
        self.ingest()
        with self.assertRaises(C.ContextError):
            M.ingest(self.store, self.path, [], approved=True)
        with self.assertRaises(C.ContextError):
            self.ingest(project='PX')

    def test_agent_access_denied_lineage_checks_original_integrity(self):
        report = self.ingest(project='PX'); sid = report['messages'][0]['id']
        self.assertEqual(self.store.search('First', G.Principal('agent', frozenset({'PX'}))), [])
        with self.assertRaises(C.ContextError):
            M.lineage(self.store, sid, G.Principal('agent', frozenset({'PX'})))
        origin = M.lineage(self.store, sid, self.owner)
        self.assertEqual(origin['original_sha256'], report['source_file_sha256'])
        (self.store.objects / report['source_file_sha256']).write_bytes(b'changed')
        with self.assertRaises(C.ContextError):
            M.lineage(self.store, sid, self.owner)

    def test_verified_backup_recovery_and_latest_batch_rollback(self):
        report = self.ingest(); backup = self.root / 'backup'; self.store.backup(backup)
        self.assertTrue(G.Store.verify_backup(backup)['verified'])
        restored = G.Store.initialize(self.root / 'restored', backup / 'Context_Store.json')
        for path in (backup / 'objects').iterdir():
            restored.put_file(path, expected=path.name)
        self.assertEqual(M.export_jsonl(restored), M.export_jsonl(self.store))
        digest = self.store.state()[1]['store_sha256']
        with self.assertRaises(C.ContextError):
            M.rollback_latest(self.store, report['batch_id'], digest, False)
        M.rollback_latest(self.store, report['batch_id'], digest, True)
        self.assertEqual(M.reports(self.store), [])
        self.assertEqual(self.store.search('First', self.owner), [])
        self.assertTrue(self.ingest()['messages'])


if __name__ == '__main__':
    unittest.main()
