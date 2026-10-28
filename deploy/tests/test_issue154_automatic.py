"""Independent normal AUTO_READY orchestration: no maintenance mutations."""
import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


PIPELINE = module("issue154_automatic_pipeline", ROOT / "pipeline/release_pipeline.py")
FIXTURES = module("issue154_automatic_fixtures", ROOT / "tests/test_issue154_transition.py")


class Issue154AutomaticTests(unittest.TestCase):
    def run_operation(self, adapter, bound=None):
        return PIPELINE.run_automatic(FIXTURES.identity() if bound is None else bound, adapter)

    def test_normal_release_submits_exactly_once_without_pausing_or_enabling_rules(self):
        adapter = FIXTURES.FixtureAdapter()
        result = self.run_operation(adapter)
        self.assertEqual("APPLIED", result["deploymentState"])
        self.assertEqual("APPLIED", result["outcome"])
        self.assertEqual(FIXTURES.COMMAND, result["commandId"])
        self.assertEqual(1, adapter.count("submit_deploy"))
        self.assertEqual(0, adapter.count("write_rule"))
        self.assertEqual(0, adapter.count("rollback_release"))
        self.assertEqual(0, adapter.count("safe_to_restore"))
        self.assertEqual(1, adapter.count("release"))

    def test_normal_path_preserves_existing_disabled_rule_state(self):
        adapter = FIXTURES.FixtureAdapter()
        adapter.rules[FIXTURES.RULES[0]] = "DISABLED"
        result = self.run_operation(adapter)
        self.assertEqual("APPLIED", result["deploymentState"])
        self.assertEqual("DISABLED", adapter.rules[FIXTURES.RULES[0]])
        self.assertEqual(0, adapter.count("write_rule"))

    def test_untrusted_identity_is_rejected_before_external_effects(self):
        for bound in (FIXTURES.identity() | {"extra": True}, FIXTURES.identity() | {"buildRunId": "0"},
                      FIXTURES.identity() | {"releaseId": "wrong"}):
            adapter = FIXTURES.FixtureAdapter()
            with self.subTest(bound=bound), self.assertRaises(ValueError):
                self.run_operation(adapter, bound)
            self.assertEqual(0, adapter.count("submit_deploy"))

    def test_pending_or_fresh_facts_failure_prevents_submission(self):
        for field, value in (("pending", 1), ("pending", True), ("facts_error", True)):
            adapter = FIXTURES.FixtureAdapter()
            setattr(adapter, field, value)
            try:
                result = self.run_operation(adapter)
            except ValueError:
                result = None
            with self.subTest(field=field):
                self.assertEqual(0, adapter.count("submit_deploy"))
                self.assertEqual(0, adapter.count("write_rule"))
                self.assertEqual(0, adapter.count("rollback_release"))
                if result is not None:
                    self.assertEqual("NOT_ATTEMPTED", result["deploymentState"])

    def test_missing_durable_intent_cannot_be_followed_by_submission(self):
        adapter = FIXTURES.FixtureAdapter()
        adapter.fail_save = True
        try:
            self.run_operation(adapter)
        except (ValueError, OSError):
            pass
        self.assertEqual(0, adapter.count("submit_deploy"))

    def test_ownership_conflict_prevents_submission(self):
        adapter = FIXTURES.FixtureAdapter()
        adapter.claim_allowed = False
        try:
            self.run_operation(adapter)
        except ValueError:
            pass
        self.assertEqual(0, adapter.count("submit_deploy"))
        self.assertEqual(0, adapter.count("write_rule"))

    def test_lost_send_reply_is_unknown_without_retry(self):
        adapter = FIXTURES.FixtureAdapter()
        adapter.submit_error = TimeoutError("synthetic send outcome unknown")
        first = self.run_operation(adapter)
        adapter.submit_error = None
        second = self.run_operation(adapter)
        self.assertEqual("UNKNOWN", first["deploymentState"])
        self.assertEqual("UNKNOWN", second["deploymentState"])
        self.assertEqual(1, adapter.count("submit_deploy"))
        self.assertEqual(0, adapter.count("write_rule"))

    def test_lost_known_command_reply_is_reconciled_not_resubmitted(self):
        adapter = FIXTURES.FixtureAdapter()
        adapter.result_error = TimeoutError("synthetic read unavailable")
        first = self.run_operation(adapter)
        adapter.result_error = None
        second = self.run_operation(adapter)
        self.assertEqual("UNKNOWN", first["deploymentState"])
        self.assertEqual("APPLIED", second["deploymentState"])
        self.assertEqual(1, adapter.count("submit_deploy"))
        self.assertEqual([FIXTURES.COMMAND, FIXTURES.COMMAND],
                         [item[1] for item in adapter.events if item[0] == "command_result"])

    def test_completed_operation_replay_does_not_deploy_again(self):
        adapter = FIXTURES.FixtureAdapter()
        self.run_operation(adapter)
        result = self.run_operation(adapter)
        self.assertEqual("APPLIED", result["deploymentState"])
        self.assertEqual(1, adapter.count("submit_deploy"))

    def test_nonterminal_or_failed_command_does_not_become_applied(self):
        for status in ("Pending", "InProgress", "Delayed", "Failed", "TimedOut", "Cancelled", "Unknown"):
            adapter = FIXTURES.FixtureAdapter()
            adapter.result_status = status
            with self.subTest(status=status):
                result = self.run_operation(adapter)
                self.assertEqual("UNKNOWN", result["deploymentState"])
                self.assertEqual(0, adapter.count("release"))
                self.assertEqual(0, adapter.count("write_rule"))

    def test_host_verification_requires_literal_true(self):
        for proof in (False, 1, "true", None):
            adapter = FIXTURES.FixtureAdapter()
            adapter.host_verified = proof
            with self.subTest(proof=proof):
                result = self.run_operation(adapter)
                self.assertEqual("UNKNOWN", result["deploymentState"])
                self.assertEqual(0, adapter.count("write_rule"))


if __name__ == "__main__":
    unittest.main()
