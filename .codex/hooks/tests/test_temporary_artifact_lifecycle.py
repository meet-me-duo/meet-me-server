from __future__ import annotations

import re
import subprocess
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).parents[3]

# Existing recovery/failure evidence remains frozen until its follow-up is closed.
# New work records its concise evidence in the PR; temporary files stay ignored.
RETAINED_EVIDENCE = {
    ".tdd/red/issue190-readonly-reconcile-diagnostics.json",
    ".tdd/red/issue192-failed-operation-closure.json",
    ".tdd/verification/issue190-reconcile-diagnostics.json",
}
PERMANENT_TDD_FILES = {
    ".tdd/README.md",
    ".tdd/PUBLICATION.md",
    ".tdd/schema/red-evidence.schema.json",
    ".tdd/schema/fault-injection-evidence.schema.json",
}


class TemporaryArtifactLifecycleTest(unittest.TestCase):
    def test_current_tree_does_not_track_temporary_work_item_outputs(self) -> None:
        result = subprocess.run(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
            cwd=REPO_ROOT,
            check=True,
            capture_output=True,
        )
        violations = []
        for raw_path in result.stdout.split(b"\0"):
            if not raw_path:
                continue
            path = raw_path.decode("utf-8")
            # Check the index even when its file was deleted only in the worktree:
            # otherwise an unrelated commit could retain a forbidden artifact.
            if path.startswith(".tdd/"):
                if path not in PERMANENT_TDD_FILES | RETAINED_EVIDENCE:
                    violations.append(path)
            elif path.startswith("_handoff/"):
                violations.append(path)
            elif path.startswith("docs/") and re.match(
                r"(?i)^issue.*\.md$", Path(path).name
            ):
                violations.append(path)
            elif path.startswith(("docs/verification/", "docs/openapi/")) and re.match(
                r"(?i)^issue.*\.json$", Path(path).name
            ):
                violations.append(path)

        self.assertEqual(
            len(violations),
            0,
            "Keep temporary work-item outputs in ignored .scratch/ or "
            ".codex/tdd-evidence/ and remove them after use; record durable "
            "evidence in the PR. First unexpected paths: "
            + ", ".join(sorted(violations)[:10]),
        )


if __name__ == "__main__":
    unittest.main()
