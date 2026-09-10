import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from archive_conversation_eval import build_archive, redact, write_archive


class ArchiveConversationEvalTest(unittest.TestCase):
    def test_redacts_credentials_but_keeps_token_counts(self):
        value = {
            "apiKey": "secret-value",
            "authorization": "Bearer abc",
            "promptTokens": 12,
            "text": "https://example.test/x?key=secret-value",
        }
        result = redact(value)
        self.assertEqual(result["apiKey"], "[REDACTED]")
        self.assertEqual(result["authorization"], "[REDACTED]")
        self.assertEqual(result["promptTokens"], 12)
        self.assertIn("[REDACTED]", result["text"])

    def test_writes_machine_manifest_and_snapshot(self):
        run = {
            "run": {
                "id": 153,
                "datasetVersion": "conversation-v1",
                "gitCommit": "abc123-dirty",
                "model": "test-model",
                "caseCount": 1,
                "completedCount": 1,
                "routeMatchedCount": 1,
                "toolMatchedCount": 1,
                "finalStatusMatchedCount": 1,
                "localityMatchedCount": 1,
                "status": "COMPLETED",
            },
            "caseResults": [{
                "caseId": 1,
                "turnOutputsJson": json.dumps({"turns": [{"route": "START_DECISION", "modelCalls": []}]}),
            }],
        }
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            archive = build_archive(run, {"failureCounts": {}, "failures": []}, "active", "fixture", "branch", "abc", 153)
            write_archive(archive, output)
            snapshot = json.loads((output / "run-153.json").read_text(encoding="utf-8"))
            manifest = (output / "manifest.jsonl").read_text(encoding="utf-8").strip().splitlines()
            self.assertEqual(snapshot["runId"], 153)
            self.assertEqual(snapshot["caseResults"][0]["turnTraceStatus"], "available")
            self.assertEqual(json.loads(manifest[0])["runId"], 153)
            self.assertTrue((output / "SUMMARY.md").exists())


if __name__ == "__main__":
    unittest.main()
