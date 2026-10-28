"""Independent final executor/attestation consistency; original CLI RED frozen."""
import copy
import json
import unittest

import test_issue154_completion_cli as fixtures
from test_issue154_completion import evidence


class Issue154CompletionCliAuditTests(unittest.TestCase):
    def fixture(self):
        value = fixtures.Issue154CompletionCliTests("test_valid_bound_complete_artifact_is_not_overwritten_by_fallback")
        value.setUp()
        self.addCleanup(value.doCleanups)
        return value

    def marker(self, fixture, observed=None):
        return fixtures.PIPELINE._module("release_completion").evaluate_completion(
            fixture.bound, evidence() if observed is None else observed)

    def test_honest_applied_incomplete_proof_survives_executor_failure(self):
        for mutation in ({"publicContractVerified": False},
                         {"publicContractVerified": False, "ruleStates": None}):
            f = self.fixture()
            f.plan["mode"] = "AUTO_READY"
            marker = self.marker(f, evidence() | mutation)
            with self.subTest(mutation=mutation):
                f.invoke({"NORMAL_RELEASE_RESULT": "failure", "TRANSITION_RELEASE_RESULT": "skipped"},
                         json.dumps(marker).encode())
                expected = dict(marker, deploymentComplete=False, outcome="RECOVERY_REQUIRED")
                self.assertEqual([expected], f.emitted)
                self.assertEqual("APPLIED", f.emitted[0]["deploymentState"])
                self.assertIs(f.emitted[0]["deploymentComplete"], False)

    def test_both_skipped_executors_conflict_with_a_claimed_applied_artifact(self):
        f = self.fixture()
        f.plan["mode"] = "AUTO_READY"
        marker = self.marker(f)
        f.invoke({"NORMAL_RELEASE_RESULT": "skipped", "TRANSITION_RELEASE_RESULT": "skipped"},
                 json.dumps(marker).encode())
        f.assert_false_bound_unknown_observations("UNKNOWN")

    def test_transition_plan_cannot_accept_an_auto_ready_artifact(self):
        f = self.fixture()
        marker = self.marker(f)
        f.invoke({"NORMAL_RELEASE_RESULT": "skipped", "TRANSITION_RELEASE_RESULT": "success"},
                 json.dumps(marker).encode())
        f.assert_false_bound_unknown_observations("UNKNOWN")

    def test_normal_executor_cannot_supply_a_different_action(self):
        f = self.fixture()
        f.plan["mode"] = "AUTO_READY"
        marker = self.marker(f, evidence() | {"action": "deploy"})
        f.invoke({"NORMAL_RELEASE_RESULT": "success", "TRANSITION_RELEASE_RESULT": "skipped"},
                 json.dumps(marker).encode())
        f.assert_false_bound_unknown_observations("UNKNOWN")

    def test_two_successful_lifecycle_executors_are_ambiguous_not_complete(self):
        f = self.fixture()
        f.plan["mode"] = "AUTO_READY"
        marker = self.marker(f)
        f.invoke({"NORMAL_RELEASE_RESULT": "success", "TRANSITION_RELEASE_RESULT": "success"},
                 json.dumps(marker).encode())
        f.assert_false_bound_unknown_observations("UNKNOWN")

    def test_matching_approved_transition_proof_can_complete(self):
        f = self.fixture()
        marker = self.marker(f, evidence() | {"action": "deploy", "mode": "TRANSITION_APPROVED"})
        f.invoke({"NORMAL_RELEASE_RESULT": "skipped", "TRANSITION_RELEASE_RESULT": "success"},
                 json.dumps(marker).encode(), expect_failure=False)
        self.assertEqual([marker], f.emitted)
        self.assertIs(f.emitted[0]["deploymentComplete"], True)


if __name__ == "__main__":
    unittest.main()

