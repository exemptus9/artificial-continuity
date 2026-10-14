"""Synthetic integration tests. No real speech recognition or user recordings."""
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import wave

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import audio_context as A
import capture_context as G
import context_store as C


class AudioTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.store = A.initialize(self.base / "private")
        self.audio = self.base / "synthetic-silence.wav"
        with wave.open(str(self.audio), "wb") as out:
            out.setnchannels(1); out.setsampwidth(2); out.setframerate(16000)
            out.writeframes(b"\x00\x00" * 48000)
        self.raw = json.dumps({"segments": [
            {"start": 0, "end": 1.5, "text": "Synthetic appointment capture."},
            {"start": 1.5, "end": 3, "text": "Preserve the original."}
        ]}).encode()

    def tearDown(self):
        self.temp.cleanup()

    def ingest(self, **kw):
        return A.ingest(self.store, self.audio, self.raw, approved=True, **kw)

    def test_original_timestamp_provenance_and_search(self):
        original = self.audio.read_bytes()
        receipt = self.ingest()
        self.assertEqual(self.audio.read_bytes(), original)
        self.assertEqual((self.store.objects / receipt["audio_sha256"]).read_bytes(), original)
        self.assertEqual(self.store.object(receipt["transcript_sha256"]), self.raw)
        result = self.store.search("appointment", G.Principal("owner"))
        self.assertEqual(result[0]["source_id"], receipt["source_id"])
        self.assertIn("00:00:01.500", result[0]["excerpt"])
        source = self.store.source(receipt["source_id"], G.Principal("owner"))["source"]
        self.assertEqual(source["review_state"], "unreviewed")
        self.assertEqual(source["source_kind"], "UNKNOWN")
        self.assertFalse(source["origin"]["audio_transcript_alignment_verified"])
        self.assertEqual(self.store.search("appointment", G.Principal("agent")), [])
        self.assertTrue(A.verify_audio(self.store, receipt["source_id"])["preserved_bytes_verified"])

    def test_retry_after_restart_is_noop(self):
        self.ingest(); journal = self.store.journal.read_bytes()
        self.store = G.Store(self.store.root)
        receipt = self.ingest()
        self.assertTrue(receipt["already_saved"])
        self.assertEqual(self.store.journal.read_bytes(), journal)

    def test_changed_transcript_preserves_both_versions(self):
        first = self.ingest()
        self.raw = self.raw.replace(b"appointment", b"meeting")
        second = self.ingest()
        self.assertNotEqual(first["source_id"], second["source_id"])
        self.assertEqual(len(self.store.state()[0]["sources"]), 2)

    def test_invalid_segments_do_not_commit(self):
        for segment in ({"start": -1, "end": 2, "text": "X"},
                        {"start": 2, "end": 1, "text": "X"},
                        {"start": float("nan"), "end": 1, "text": "X"},
                        {"start": True, "end": 1, "text": "X"},
                        {"start": 0, "end": 1, "text": ""}):
            with self.assertRaises(C.ContextError):
                A.ingest(self.store, self.audio, json.dumps({"segments": [segment]}).encode(), approved=True)
        self.assertEqual(self.store.state()[1]["revision"], 0)

    def test_empty_transcript_does_not_claim_success(self):
        with self.assertRaises(C.ContextError):
            A.ingest(self.store, self.audio, b'{"segments": []}', approved=True)

    def test_approval_required(self):
        with self.assertRaises(C.ContextError):
            A.ingest(self.store, self.audio, self.raw)
        self.assertEqual(self.store.state()[1]["revision"], 0)

    def test_cannot_replace_existing_store(self):
        original = self.store.journal.read_bytes()
        with self.assertRaises(C.ContextError): A.initialize(self.store.root)
        self.assertEqual(original, self.store.journal.read_bytes())

    def test_changed_audio_blocks_claim(self):
        original_hash = G.file_hash(self.audio)
        self.audio.write_bytes(b"changed")
        with self.assertRaises(C.ContextError): self.ingest(expected_audio=original_hash)
        self.assertEqual(self.store.state()[1]["revision"], 0)

    def test_corrupt_audio_is_detected(self):
        receipt = self.ingest()
        (self.store.objects / receipt["audio_sha256"]).write_bytes(b"changed")
        with self.assertRaises(C.ContextError): A.verify_audio(self.store, receipt["source_id"])

    def test_backup_restores_search_and_binary_sources(self):
        receipt = self.ingest()
        backup = self.base / "backup"
        self.store.backup(backup)
        self.assertTrue(G.Store.verify_backup(backup)["verified"])
        recovered = G.Store(backup)
        self.assertTrue(A.verify_audio(recovered, receipt["source_id"])["preserved_bytes_verified"])
        self.assertEqual(len(recovered.search("appointment", G.Principal("owner"))), 1)

    def test_speech_adapter_consumes_generator_and_defaults_offline(self):
        seen = {}
        class FakeModel:
            def __init__(self, model, **kw): seen.update(kw)
            def transcribe(self, filename, **kw):
                self_outer.assertEqual(G.file_hash(filename), G.file_hash(self_outer.audio))
                def segments():
                    seen["consumed"] = True
                    yield SimpleNamespace(start=0, end=1, text="Synthetic adapter output")
                return segments(), SimpleNamespace(language="en")
        self_outer = self
        with patch.dict(sys.modules, {"faster_whisper": SimpleNamespace(WhisperModel=FakeModel)}), \
             patch.object(A.importlib.metadata, "version", return_value="synthetic-test"):
            receipt = A.transcribe(self.store, self.audio, approved=True)
        self.assertTrue(seen["local_files_only"])
        self.assertTrue(seen["consumed"])
        self.assertEqual(receipt["segments"], 1)


if __name__ == "__main__":
    unittest.main()
