from __future__ import annotations

import json
import re
import unittest
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).parents[3]
SCHEMA_PATH = REPO_ROOT / ".tdd" / "schema" / "red-evidence.schema.json"
LIFECYCLE_PATH = REPO_ROOT / ".tdd" / "README.md"
GITIGNORE_PATH = REPO_ROOT / ".gitignore"


class RedEvidenceContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))

    def test_accepts_minimal_valid_summary(self) -> None:
        valid_summary = {
            "workItem": "issue-6-schema-contract",
            "sourceRevision": "a" * 40,
            "testFiles": [
                {
                    "path": "src/test/kotlin/com/meetme/ExampleTest.kt",
                    "sha256": "b" * 64,
                }
            ],
            "command": ["./gradlew", "test", "--tests", "com.meetme.ExampleTest"],
            "failedTests": ["com.meetme.ExampleTest.rejects invalid input"],
            "expectedFailureCategory": "MISSING_BEHAVIOR",
            "recordedAt": "2026-09-17T15:30:00Z",
        }

        self.assert_valid(valid_summary)

    def test_rejects_changed_test_hash_shape(self) -> None:
        invalid_summary = {
            "workItem": "issue-6-schema-contract",
            "sourceRevision": "a" * 40,
            "testFiles": [
                {
                    "path": "src/test/kotlin/com/meetme/ExampleTest.kt",
                    "sha256": "not-a-sha-256",
                }
            ],
            "command": ["./gradlew", "test"],
            "failedTests": ["com.meetme.ExampleTest.rejects invalid input"],
            "expectedFailureCategory": "MISSING_BEHAVIOR",
            "recordedAt": "2026-09-17T15:30:00Z",
        }

        with self.assertRaisesRegex(AssertionError, "sha256"):
            self.assert_valid(invalid_summary)

    def test_rejects_unexpected_fields(self) -> None:
        invalid_summary = {
            "workItem": "issue-6-schema-contract",
            "sourceRevision": "a" * 40,
            "testFiles": [
                {
                    "path": "src/test/kotlin/com/meetme/ExampleTest.kt",
                    "sha256": "b" * 64,
                }
            ],
            "command": ["./gradlew", "test"],
            "failedTests": ["com.meetme.ExampleTest.rejects invalid input"],
            "expectedFailureCategory": "MISSING_BEHAVIOR",
            "recordedAt": "2026-09-17T15:30:00Z",
            "rawOutput": "must never be committed",
        }

        with self.assertRaisesRegex(AssertionError, "rawOutput"):
            self.assert_valid(invalid_summary)

    def test_documents_invalidation_and_retention(self) -> None:
        lifecycle = LIFECYCLE_PATH.read_text(encoding="utf-8")

        self.assertIn("테스트 파일의 내용이 바뀌면 기존 요약은 무효", lifecycle)
        self.assertIn("병합 후에도 저장소에 유지", lifecycle)

    def test_ignores_local_raw_evidence_directory(self) -> None:
        gitignore_lines = GITIGNORE_PATH.read_text(encoding="utf-8").splitlines()

        self.assertIn("/.codex/tdd-evidence/", gitignore_lines)

    def assert_valid(self, document: dict[str, Any]) -> None:
        self._validate(self.schema, document, "$")

    def _validate(self, schema: dict[str, Any], value: Any, path: str) -> None:
        expected_type = schema.get("type")
        if expected_type == "object":
            self.assertIsInstance(value, dict, path)
            properties = schema.get("properties", {})
            required = schema.get("required", [])
            for field in required:
                self.assertIn(field, value, f"{path}.{field}")
            if schema.get("additionalProperties") is False:
                unexpected = set(value) - set(properties)
                self.assertFalse(unexpected, f"{path}: unexpected fields {sorted(unexpected)}")
            for field, child in value.items():
                if field in properties:
                    self._validate(properties[field], child, f"{path}.{field}")
        elif expected_type == "array":
            self.assertIsInstance(value, list, path)
            self.assertGreaterEqual(len(value), schema.get("minItems", 0), path)
            if schema.get("uniqueItems"):
                serialized = [json.dumps(item, sort_keys=True) for item in value]
                self.assertEqual(len(serialized), len(set(serialized)), path)
            for index, item in enumerate(value):
                self._validate(schema["items"], item, f"{path}[{index}]")
        elif expected_type == "string":
            self.assertIsInstance(value, str, path)
            self.assertGreaterEqual(len(value), schema.get("minLength", 0), path)
            if "pattern" in schema:
                self.assertIsNotNone(re.fullmatch(schema["pattern"], value), path)
            if "enum" in schema:
                self.assertIn(value, schema["enum"], path)


if __name__ == "__main__":
    unittest.main()
