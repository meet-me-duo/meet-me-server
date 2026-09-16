from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


HOOK_PATH = Path(__file__).parents[1] / "tdd_guard.py"
SPEC = importlib.util.spec_from_file_location("tdd_guard", HOOK_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {HOOK_PATH}")
TDD_GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TDD_GUARD)


class GitCommitDetectionTest(unittest.TestCase):
    def test_detects_direct_commit(self) -> None:
        self.assertTrue(TDD_GUARD.is_git_commit("git commit -m test"))

    def test_detects_commit_after_another_command(self) -> None:
        self.assertTrue(TDD_GUARD.is_git_commit("git add . && git commit -m test"))

    def test_ignores_commit_text_that_is_not_a_command(self) -> None:
        self.assertFalse(TDD_GUARD.is_git_commit("echo git commit"))


class TestChangeRequirementTest(unittest.TestCase):
    def test_blocks_production_change_without_test_change(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "without a corresponding Kotlin test"):
            TDD_GUARD.require_test_change({"src/main/kotlin/App.kt"})

    def test_accepts_production_change_with_test_change(self) -> None:
        TDD_GUARD.require_test_change(
            {
                "src/main/kotlin/App.kt",
                "src/test/kotlin/AppTest.kt",
            }
        )

    def test_accepts_non_production_change_without_test_change(self) -> None:
        TDD_GUARD.require_test_change({"docs/ARCHITECTURE.md"})


if __name__ == "__main__":
    unittest.main()
