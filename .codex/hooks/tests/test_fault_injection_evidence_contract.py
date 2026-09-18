from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).parents[3]
SCHEMA_PATH = REPO_ROOT / ".tdd" / "schema" / "fault-injection-evidence.schema.json"
EVIDENCE_PATH = REPO_ROOT / ".tdd" / "verification" / "issue-10-persistence.json"


class FaultInjectionEvidenceContractTest(unittest.TestCase):
    def test_schema_and_issue_10_summary_are_well_formed(self) -> None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        evidence = json.loads(EVIDENCE_PATH.read_text(encoding="utf-8"))

        self.assertEqual(set(schema["required"]), set(evidence) - {"$schema"})
        self.assertRegex(evidence["workItem"], r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
        self.assertRegex(evidence["sourceRevision"], r"^[0-9a-f]{40}(?:[0-9a-f]{24})?$")
        self.assertRegex(evidence["recordedAt"], r"^\d{4}-\d{2}-\d{2}T.+Z$")
        self.assertTrue(evidence["injections"])
        self.assertTrue(all(injection["result"] == "DETECTED" for injection in evidence["injections"]))

        for collection in ("sourceFiles", "testFiles"):
            self.assertTrue(evidence[collection])
            for file_fingerprint in evidence[collection]:
                self.assertTrue((REPO_ROOT / file_fingerprint["path"]).is_file())
                self.assertIsNotNone(re.fullmatch(r"[0-9a-f]{64}", file_fingerprint["sha256"]))


if __name__ == "__main__":
    unittest.main()
