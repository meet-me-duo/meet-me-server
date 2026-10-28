"""Independent issue154 approved-transition safety behavior; fixture services only."""
import copy
import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("issue154_transition", ROOT / "pipeline/transition_orchestrator.py")
TRANSITION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TRANSITION)
RULES = ("meet-me-production-db-credential-reconcile", "meet-me-production-db-credential-rotated")
COMMAND = "00000000-0000-4000-8000-000000000154"


def identity():
    source = "a" * 40
    return {"sourceSha": source, "buildRunId": "154", "buildRunAttempt": "1",
            "workflowRunId": "155", "workflowRunAttempt": "2", "releaseId": source + "-154-1",
            "imageDigest": "sha256:" + "b" * 64}


def operation():
    return {"identity": identity(), "action": "deploy", "approvalVerified": True}


class FixtureAdapter:
    """Records externally visible effects; it deliberately does not enforce core safety."""
    def __init__(self):
        self.rules = {name: "ENABLED" for name in RULES}
        self.records = {}
        self.events = []
        self.owner = None
        self.claim_allowed = True
        self.owner_lost = False
        self.lose_owner_after_pause = False
        self.pending = 0
        self.facts_error = False
        self.safe = True
        self.fail_pause = None
        self.fail_restore = None
        self.fail_save = False
        self.submit_error = None
        self.result_error = None
        self.host_verified = True
        self.result_status = "Success"
        self.submission_snapshot = None

    def claim_operation(self, op_id, bound):
        self.events.append(("claim", op_id))
        if not self.claim_allowed or (self.owner is not None and self.owner != op_id):
            return False
        self.owner = op_id
        return True

    def owns_operation(self, op_id):
        self.events.append(("ownership_read", op_id))
        return self.owner == op_id and not self.owner_lost

    def load_operation(self, op_id):
        self.events.append(("load", op_id))
        return copy.deepcopy(self.records.get(op_id))

    def save_operation(self, op_id, record):
        self.events.append(("persist", op_id))
        if self.fail_save:
            raise OSError("synthetic durable store unavailable")
        self.records[op_id] = copy.deepcopy(record)

    def read_rules(self):
        self.events.append(("read_rules",))
        return dict(self.rules)

    def write_rule(self, name, state):
        self.events.append(("write_rule", name, state))
        if state == "DISABLED" and self.fail_pause == name:
            raise OSError("synthetic pause error")
        if state == "ENABLED" and self.fail_restore == name:
            raise OSError("synthetic restoration error")
        self.rules[name] = state
        if self.lose_owner_after_pause and all(s == "DISABLED" for s in self.rules.values()):
            self.owner_lost = True

    def active_commands(self):
        self.events.append(("active_commands",))
        return self.pending

    def validate_facts(self):
        self.events.append(("validate_facts",))
        if self.facts_error:
            raise ValueError("synthetic unsafe fresh facts")

    def submit_deploy(self):
        self.submission_snapshot = copy.deepcopy(next(reversed(self.records.values()), None))
        self.events.append(("submit_deploy",))
        if self.submit_error is not None:
            raise self.submit_error
        return COMMAND

    def command_result(self, command_id):
        self.events.append(("command_result", command_id))
        if self.result_error is not None:
            raise self.result_error
        return {"status": self.result_status,
                "result": {"sourceSha": identity()["sourceSha"], "phase": "READY",
                           "imageMatches": True, "historyMatches": True}}

    def verify_host(self, result):
        self.events.append(("verify_host",))
        return self.host_verified

    def safe_to_restore(self):
        self.events.append(("safe_to_restore",))
        return self.safe

    def release_operation(self, op_id):
        self.events.append(("release", op_id))
        if self.owner == op_id:
            self.owner = None

    def rollback_release(self):
        # Forbidden effect spy: uncertain submission never authorizes an older writer.
        self.events.append(("rollback_release",))

    def count(self, event):
        return sum(item[0] == event for item in self.events)

    def enabled_writes(self):
        return [item for item in self.events if item[0] == "write_rule" and item[2] == "ENABLED"]


class Issue154TransitionTests(unittest.TestCase):
    def run_operation(self, adapter, requested=None):
        return TRANSITION.run_transition(operation() if requested is None else requested, adapter)

    def test_approved_safe_transition_submits_once_and_restores_original_states(self):
        adapter = FixtureAdapter()
        result = self.run_operation(adapter)
        self.assertEqual("APPLIED", result["deploymentState"])
        self.assertEqual("RESTORED", result["rulesState"])
        self.assertEqual("APPLIED", result["outcome"])
        self.assertEqual(COMMAND, result["commandId"])
        self.assertEqual(1, adapter.count("submit_deploy"))
        self.assertEqual({name: "ENABLED" for name in RULES}, adapter.rules)
        self.assertEqual(1, adapter.count("release"))

    def test_no_verified_approval_means_no_rule_mutation_or_deployment(self):
        adapter = FixtureAdapter()
        result = self.run_operation(adapter, operation() | {"approvalVerified": False})
        self.assertEqual("NOT_ATTEMPTED", result["deploymentState"])
        self.assertEqual("APPROVAL_REQUIRED", result["outcome"])
        self.assertEqual(0, adapter.count("write_rule"))
        self.assertEqual(0, adapter.count("submit_deploy"))

    def test_truthy_approval_and_unbound_operation_are_rejected(self):
        bad = [operation() | {"approvalVerified": value} for value in ("true", 1, None)]
        bad += [operation() | {"action": "shell"}, operation() | {"extra": "field"}]
        for field, value in (("sourceSha", "bad"), ("workflowRunAttempt", 2),
                             ("releaseId", "a" * 40 + "-154-9")):
            bad.append(operation() | {"identity": identity() | {field: value}})
        for requested in bad:
            adapter = FixtureAdapter()
            with self.subTest(requested=requested), self.assertRaises(ValueError):
                self.run_operation(adapter, requested)
            self.assertEqual(0, adapter.count("write_rule"))
            self.assertEqual(0, adapter.count("submit_deploy"))

    def test_operation_ownership_conflict_cannot_touch_rules(self):
        adapter = FixtureAdapter()
        adapter.claim_allowed = False
        try:
            self.run_operation(adapter)
        except ValueError:
            pass
        self.assertEqual(0, adapter.count("write_rule"))
        self.assertEqual(0, adapter.count("submit_deploy"))

    def test_durable_journal_is_required_before_any_external_mutation(self):
        adapter = FixtureAdapter()
        adapter.fail_save = True
        try:
            self.run_operation(adapter)
        except (OSError, ValueError):
            pass
        self.assertEqual(0, adapter.count("write_rule"))
        self.assertEqual(0, adapter.count("submit_deploy"))

    def test_send_intent_is_durable_before_any_command_submission_effect(self):
        adapter = FixtureAdapter()
        self.run_operation(adapter)
        self.assertIsNotNone(adapter.submission_snapshot)
        self.assertEqual("UNKNOWN", adapter.submission_snapshot["deploymentState"])
        self.assertIsNone(adapter.submission_snapshot["commandId"])
        self.assertEqual(identity(), adapter.submission_snapshot["identity"])

    def test_originally_disabled_rule_is_preserved_not_enabled_on_success(self):
        adapter = FixtureAdapter()
        adapter.rules[RULES[0]] = "DISABLED"
        result = self.run_operation(adapter)
        self.assertEqual("APPLIED", result["deploymentState"])
        self.assertEqual("DISABLED", adapter.rules[RULES[0]])
        self.assertNotIn(("write_rule", RULES[0], "ENABLED"), adapter.events)

    def test_pending_command_or_bad_facts_prevents_deployment(self):
        for field, value in (("pending", 1), ("pending", True), ("facts_error", True)):
            adapter = FixtureAdapter()
            setattr(adapter, field, value)
            try:
                result = self.run_operation(adapter)
            except ValueError:
                result = None
            with self.subTest(field=field, value=value):
                self.assertEqual(0, adapter.count("submit_deploy"))
                if result is not None:
                    self.assertEqual("NOT_ATTEMPTED", result["deploymentState"])

    def test_partial_pause_failure_restores_changed_rules_when_no_deploy_was_submitted(self):
        adapter = FixtureAdapter()
        adapter.fail_pause = RULES[1]
        result = self.run_operation(adapter)
        self.assertEqual("NOT_ATTEMPTED", result["deploymentState"])
        self.assertNotEqual("APPLIED", result["outcome"])
        self.assertEqual(0, adapter.count("submit_deploy"))
        self.assertEqual({name: "ENABLED" for name in RULES}, adapter.rules)

    def test_before_submission_failed_facts_restore_only_when_host_safety_is_proven(self):
        adapter = FixtureAdapter()
        adapter.facts_error = True
        adapter.safe = False
        result = self.run_operation(adapter)
        self.assertEqual("NOT_ATTEMPTED", result["deploymentState"])
        self.assertEqual(0, adapter.count("submit_deploy"))
        self.assertEqual([], adapter.enabled_writes())
        self.assertNotEqual("APPLIED", result["outcome"])
        self.assertEqual("PAUSED", result["rulesState"])

    def test_partial_pause_without_safe_restoration_is_reported_as_partial_not_untouched(self):
        adapter = FixtureAdapter()
        adapter.fail_pause = RULES[1]
        adapter.safe = False
        result = self.run_operation(adapter)
        self.assertEqual("NOT_ATTEMPTED", result["deploymentState"])
        self.assertEqual("PARTIAL", result["rulesState"])
        self.assertEqual("DISABLED", adapter.rules[RULES[0]])
        self.assertEqual("ENABLED", adapter.rules[RULES[1]])
        self.assertEqual([], adapter.enabled_writes())

    def test_lost_submission_reply_or_interrupt_is_unknown_without_retry_or_enable(self):
        for error in (TimeoutError("synthetic reply lost"), KeyboardInterrupt()):
            adapter = FixtureAdapter()
            adapter.submit_error = error
            with self.subTest(error=type(error).__name__):
                result = self.run_operation(adapter)
                self.assertEqual("UNKNOWN", result["deploymentState"])
                self.assertEqual("RECOVERY_REQUIRED", result["outcome"])
                self.assertEqual(1, adapter.count("submit_deploy"))
                self.assertEqual([], adapter.enabled_writes())
                self.assertEqual(0, adapter.count("release"))
                self.assertEqual(0, adapter.count("rollback_release"))
                self.assertTrue(adapter.records)

    def test_failed_cancelled_pending_and_unverified_host_results_never_enable_rules(self):
        for status in ("Pending", "InProgress", "Delayed", "Failed", "Cancelled", "TimedOut", "Unknown"):
            adapter = FixtureAdapter()
            adapter.result_status = status
            with self.subTest(status=status):
                result = self.run_operation(adapter)
                self.assertEqual("UNKNOWN", result["deploymentState"])
                self.assertEqual([], adapter.enabled_writes())
                self.assertEqual(1, adapter.count("submit_deploy"))
                self.assertEqual(0, adapter.count("rollback_release"))
        for proof in (False, 1, "true", None):
            adapter = FixtureAdapter()
            adapter.host_verified = proof
            with self.subTest(hostVerified=proof):
                result = self.run_operation(adapter)
                self.assertEqual("UNKNOWN", result["deploymentState"])
                self.assertEqual([], adapter.enabled_writes())

    def test_known_command_reconciliation_queries_same_id_without_resubmission(self):
        adapter = FixtureAdapter()
        adapter.result_error = TimeoutError("synthetic query unavailable")
        first = self.run_operation(adapter)
        self.assertEqual("UNKNOWN", first["deploymentState"])
        adapter.result_error = None
        second = self.run_operation(adapter)
        self.assertEqual("APPLIED", second["deploymentState"])
        self.assertEqual(1, adapter.count("submit_deploy"))
        self.assertEqual([COMMAND, COMMAND], [e[1] for e in adapter.events if e[0] == "command_result"])
        self.assertEqual({name: "ENABLED" for name in RULES}, adapter.rules)

    def test_missing_command_id_cannot_be_bypassed_by_resume_or_same_operation_retry(self):
        adapter = FixtureAdapter()
        adapter.submit_error = TimeoutError("synthetic send outcome unknown")
        first = self.run_operation(adapter)
        self.assertEqual("UNKNOWN", first["deploymentState"])
        adapter.submit_error = None
        for action in ("deploy", "resume"):
            with self.subTest(action=action):
                result = self.run_operation(adapter, operation() | {"action": action})
                self.assertEqual("UNKNOWN", result["deploymentState"])
                self.assertEqual(1, adapter.count("submit_deploy"))
                self.assertEqual([], adapter.enabled_writes())

    def test_successful_command_with_restore_failure_preserves_applied_and_incomplete_state(self):
        adapter = FixtureAdapter()
        adapter.fail_restore = RULES[0]
        result = self.run_operation(adapter)
        self.assertEqual("APPLIED", result["deploymentState"])
        self.assertEqual("RESTORATION_REQUIRED", result["outcome"])
        self.assertNotEqual("RESTORED", result["rulesState"])
        self.assertEqual(1, adapter.count("submit_deploy"))

    def test_loss_of_ownership_after_pause_cannot_submit_or_restore_another_owner_rules(self):
        adapter = FixtureAdapter()
        adapter.lose_owner_after_pause = True
        result = self.run_operation(adapter)
        self.assertEqual(0, adapter.count("submit_deploy"))
        self.assertEqual([], adapter.enabled_writes())
        self.assertNotEqual("APPLIED", result["outcome"])

    def test_replaying_completed_operation_does_not_submit_another_deployment(self):
        adapter = FixtureAdapter()
        first = self.run_operation(adapter)
        second = self.run_operation(adapter)
        self.assertEqual("APPLIED", first["deploymentState"])
        self.assertEqual("APPLIED", second["deploymentState"])
        self.assertEqual(1, adapter.count("submit_deploy"))

    def test_malformed_or_rebound_durable_journal_is_rejected_without_effects(self):
        seed = FixtureAdapter()
        seed.result_error = TimeoutError("synthetic recorded operation")
        self.run_operation(seed)
        op_id, original = next(iter(seed.records.items()))
        mutations = [{field: value} for field, value in (
            ("phase", []), ("action", {}), ("outcome", []), ("rulesState", {}),
            ("deploymentState", []), ("reason", []), ("changedRules", [[]]),
            ("sequence", True), ("commandId", {}), ("operationId", "f" * 64))]
        different_identity = identity() | {"workflowRunAttempt": "3"}
        mutations.append({"identity": different_identity})
        for mutation in mutations:
            adapter = FixtureAdapter()
            adapter.rules = dict(seed.rules)
            adapter.records[op_id] = copy.deepcopy(original) | mutation
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.run_operation(adapter)
            self.assertEqual(0, adapter.count("write_rule"))
            self.assertEqual(0, adapter.count("submit_deploy"))
            self.assertEqual(0, adapter.count("command_result"))


if __name__ == "__main__":
    unittest.main()
