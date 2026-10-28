"""Explicit final executor-failure decision: retain observed APPLIED effects."""
import json
import unittest

import test_issue154_completion_cli as fixtures
from test_issue154_completion import evidence


class Issue154ExecutorFailureTests(unittest.TestCase):
    def fixture(self):
        f = fixtures.Issue154CompletionCliTests("test_valid_bound_complete_artifact_is_not_overwritten_by_fallback")
        f.setUp()
        self.addCleanup(f.doCleanups)
        return f

    def test_normal_executor_failure_keeps_all_applied_observations_but_not_completion(self):
        for status in ("failure", "cancelled"):
            f = self.fixture()
            f.plan["mode"] = "AUTO_READY"
            marker = fixtures.PIPELINE._module("release_completion").evaluate_completion(f.bound, evidence())
            with self.subTest(status=status):
                f.invoke({"NORMAL_RELEASE_RESULT": status, "TRANSITION_RELEASE_RESULT": "skipped"},
                         json.dumps(marker).encode())
                expected = dict(marker, deploymentComplete=False, outcome="RECOVERY_REQUIRED")
                self.assertEqual([expected], f.emitted)
                self.assertEqual("APPLIED", f.emitted[0]["deploymentState"])
                self.assertIs(f.emitted[0]["hostVerified"], True)
                self.assertIs(f.emitted[0]["publicContractVerified"], True)
                self.assertIs(f.emitted[0]["rulesRestored"], True)
                self.assertEqual(0, f.emitted[0]["pendingCommands"])

    def test_transition_executor_failure_keeps_all_applied_observations_but_not_completion(self):
        for status in ("failure", "cancelled"):
            f = self.fixture()
            marker = fixtures.PIPELINE._module("release_completion").evaluate_completion(
                f.bound, evidence() | {"action": "deploy", "mode": "TRANSITION_APPROVED"})
            with self.subTest(status=status):
                f.invoke({"NORMAL_RELEASE_RESULT": "skipped", "TRANSITION_RELEASE_RESULT": status},
                         json.dumps(marker).encode())
                expected = dict(marker, deploymentComplete=False, outcome="RECOVERY_REQUIRED")
                self.assertEqual([expected], f.emitted)
                self.assertEqual("APPLIED", f.emitted[0]["deploymentState"])
                self.assertIs(f.emitted[0]["hostVerified"], True)
                self.assertIs(f.emitted[0]["publicContractVerified"], True)
                self.assertIs(f.emitted[0]["rulesRestored"], True)
                self.assertEqual(0, f.emitted[0]["pendingCommands"])


if __name__ == "__main__":
    unittest.main()

