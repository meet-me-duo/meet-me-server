"""Independent failed-operation closure contract; synthetic data, no external IO.

Accepted operator requirements: preserve the attempted failure, require fresh full
baseline evidence, fence the old owner, and close only this exact reviewed intent.
Expected records below are authored from that contract, not emitted by the SUT.
"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("failed_closure_contract", ROOT / "pipeline/release_pipeline.py")
PIPELINE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PIPELINE)
COMMAND = "00000000-0000-4000-8000-000000000154"
RULES = ("meet-me-production-db-credential-reconcile", "meet-me-production-db-credential-rotated")
SENTINEL = "synthetic-sensitive-exception-must-not-enter-output"
PLAN_FIELDS = {"protocol", "target", "identity", "operationId", "originalJournal", "originalJournalEtag",
               "originalOwner", "originalOwnerEtag", "evidence", "recoveryHolder", "approvalDigest"}
RESULT_FIELDS = {"protocol", "operationId", "outcome", "deploymentComplete", "ownerReleased"}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def identity():
    return {"sourceSha": "a" * 40, "buildRunId": "154", "buildRunAttempt": "1",
            "workflowRunId": "155", "workflowRunAttempt": "2", "releaseId": "a" * 40 + "-154-1",
            "imageDigest": "sha256:" + "b" * 64}


def operation_id():
    return digest(identity())


def context():
    return {"accountId": "123456789012", "region": "ap-northeast-2", "instanceId": "i-0123456789abcdef0",
            "bucket": "synthetic-closure-artifacts", "manifestSha256": "c" * 64}


def journal():
    return {"protocol": "meet-me-automatic-operation-v1", "identity": identity(), "operationId": operation_id(),
            "sequence": 4, "deploymentState": "UNKNOWN", "commandId": COMMAND,
            "originalRules": {name: "ENABLED" for name in RULES}, "outcome": "RECOVERY_REQUIRED",
            "rulesState": "UNTOUCHED"}


def owner():
    return {"protocol": "meet-me-release-owner-v1", "identity": identity(), "operationId": operation_id(),
            "holder": "d" * 32, "released": False}


def evidence():
    bound = identity()
    return {"commandStatus": "Failed", "workflowStatus": "completed", "workflowRunId": bound["workflowRunId"],
            "workflowRunAttempt": bound["workflowRunAttempt"], "pendingCommands": 0,
            "rules": {name: "ENABLED" for name in RULES},
            "hostProof": {"protocol": "meet-me-failed-closure-host-v1", "sourceSha": bound["sourceSha"],
                          "releaseId": bound["releaseId"], "buildRunId": bound["buildRunId"],
                          "buildRunAttempt": bound["buildRunAttempt"], "imageDigest": bound["imageDigest"],
                          "phase": "READY", "guardTrusted": True, "historyMatches": True, "health": "healthy",
                          "schemaFingerprint": "e" * 64, "guardFingerprint": "f" * 64,
                          "historyDigest": "1" * 64}}


def reseal_plan(value):
    seed = {k: copy.deepcopy(v) for k, v in value.items()
            if k not in {"protocol", "recoveryHolder", "approvalDigest"}}
    value["recoveryHolder"] = digest(seed)[:32]
    value["approvalDigest"] = digest({k: v for k, v in value.items() if k != "approvalDigest"})
    return value


def reviewed_plan():
    return reseal_plan({"protocol": "meet-me-failed-closure-plan-v1", "target": context(), "identity": identity(),
                       "operationId": operation_id(), "originalJournal": journal(),
                       "originalJournalEtag": '"journal-1"', "originalOwner": owner(),
                       "originalOwnerEtag": '"owner-1"', "evidence": evidence()})


def intended_records(plan):
    intent = {"protocol": "meet-me-failed-closure-intent-v1", "operationId": plan["operationId"],
              "identity": plan["identity"], "planDigest": plan["approvalDigest"], "target": plan["target"],
              "journalDigest": digest(plan["originalJournal"]), "journalEtag": plan["originalJournalEtag"],
              "originalOwnerDigest": digest(plan["originalOwner"]), "originalOwnerEtag": plan["originalOwnerEtag"],
              "recoveryHolder": plan["recoveryHolder"], "commandId": plan["originalJournal"]["commandId"],
              "evidenceDigest": digest(plan["evidence"]), "plan": copy.deepcopy(plan)}
    closure = {"protocol": "meet-me-failed-operation-closure-v1", "operationId": plan["operationId"],
               "identity": plan["identity"], "commandId": plan["originalJournal"]["commandId"],
               "outcome": "FAILED_CLOSED", "deploymentComplete": False, "intentDigest": digest(intent),
               "planDigest": plan["approvalDigest"], "journalDigest": digest(plan["originalJournal"]),
               "recoveryHolder": plan["recoveryHolder"], "evidenceDigest": digest(plan["evidence"])}
    fenced = plan["originalOwner"] | {"holder": plan["recoveryHolder"]}
    released = fenced | {"released": True}
    return intent, closure, fenced, released


class FakeClosureAdapter:
    """Atomic conditional object store; faults model delivery and response loss."""
    def __init__(self):
        self.target = context()
        self.proof = evidence()
        self.snapshot = {"journal": journal(), "journalEtag": '"journal-1"', "owner": owner(),
                         "ownerEtag": '"owner-1"', "intent": None, "closure": None}
        self.initial_journal = copy.deepcopy(self.snapshot["journal"])
        self.events = []
        self.faults = {}
        self.hooks = {}
        self.owner_version = 1
        self.evidence_reads = 0

    def event(self, name, phase="before"):
        self.events.append((name, phase))
        hook = self.hooks.pop((name, phase), None)
        if hook:
            hook(self)
        if self.faults.pop((name, phase), False):
            raise RuntimeError(SENTINEL)

    def closure_context(self):
        self.event("context")
        return copy.deepcopy(self.target)

    def read_closure_snapshot(self, op_id):
        if op_id != operation_id():
            raise ValueError("FOREIGN_OPERATION")
        self.event("snapshot")
        result = copy.deepcopy(self.snapshot)
        self.event("snapshot", "after")
        return result

    def closure_evidence(self, bound, command_id):
        if bound != identity() or command_id != COMMAND:
            raise ValueError("FOREIGN_IDENTITY_OR_COMMAND")
        self.evidence_reads += 1
        self.event("evidence")
        result = copy.deepcopy(self.proof)
        self.event("evidence", "after")
        return result

    def put_closure_intent(self, op_id, value):
        self.event("put_intent")
        if op_id != operation_id() or self.snapshot["intent"] is not None:
            raise RuntimeError("CONDITIONAL_WRITE_FAILED")
        self.snapshot["intent"] = copy.deepcopy(value)
        self.event("put_intent", "after")

    def fence_closure_owner(self, op_id, value, expected_etag):
        self.event("fence_owner")
        self.write_owner(op_id, value, expected_etag)
        self.event("fence_owner", "after")

    def put_failed_closure(self, op_id, value):
        self.event("put_closure")
        if op_id != operation_id() or self.snapshot["closure"] is not None:
            raise RuntimeError("CONDITIONAL_WRITE_FAILED")
        self.snapshot["closure"] = copy.deepcopy(value)
        self.event("put_closure", "after")

    def release_closure_owner(self, op_id, value, expected_etag):
        self.event("release_owner")
        self.write_owner(op_id, value, expected_etag)
        self.event("release_owner", "after")

    def write_owner(self, op_id, value, expected_etag):
        if op_id != operation_id() or expected_etag != self.snapshot["ownerEtag"]:
            raise RuntimeError("CONDITIONAL_WRITE_FAILED")
        self.snapshot["owner"] = copy.deepcopy(value)
        self.owner_version += 1
        self.snapshot["ownerEtag"] = '"owner-' + str(self.owner_version) + '"'

    def mutate_journal(self, field="sequence", value=5):
        self.snapshot["journal"][field] = value
        self.snapshot["journalEtag"] = '"journal-concurrent"'

    def mutations(self):
        return [name for name, phase in self.events if phase == "before" and name in
                {"put_intent", "fence_owner", "put_closure", "release_owner"}]

    def __getattr__(self, name):
        if name in {"save_operation", "submit_deploy", "write_rule", "cancel_command", "rollback", "restart"}:
            raise AssertionError("Failed closure cannot invoke " + name)
        raise AttributeError(name)


class FailedOperationClosureContract(unittest.TestCase):
    def setUp(self):
        self.adapter = FakeClosureAdapter()
        self.plan = reviewed_plan()

    def api(self, name):
        function = getattr(PIPELINE, name, None)
        self.assertTrue(callable(function), "Missing accepted failed-operation closure requirement: " + name)
        return function

    def plan_now(self):
        return self.api("plan_failed_closure")(identity(), operation_id(), self.adapter)

    def apply(self, plan=None, approval=None):
        value = self.plan if plan is None else plan
        return self.api("close_failed_operation")(copy.deepcopy(value),
                                                  value["approvalDigest"] if approval is None else approval,
                                                  self.adapter)

    def assert_result(self, result, closed):
        self.assertEqual(RESULT_FIELDS, set(result))
        self.assertEqual("meet-me-failed-closure-result-v1", result["protocol"])
        self.assertEqual(operation_id(), result["operationId"])
        self.assertEqual("CLOSED" if closed else "INCOMPLETE", result["outcome"])
        self.assertIs(False, result["deploymentComplete"])
        self.assertIs(closed, result["ownerReleased"])
        self.assertNotIn(SENTINEL, json.dumps(result))
        self.assertEqual(self.adapter.initial_journal, self.adapter.snapshot["journal"])

    def assert_no_release(self):
        self.assertNotIn("release_owner", self.adapter.mutations())
        self.assertIs(False, self.adapter.snapshot["owner"]["released"])

    def test_read_only_plan_is_the_exact_independent_reviewed_snapshot(self):
        plan = self.plan_now()
        self.assertEqual(PLAN_FIELDS, set(plan))
        self.assertEqual(reviewed_plan(), plan)
        self.assertEqual([], self.adapter.mutations())
        self.assertGreaterEqual(self.adapter.evidence_reads, 1)

    def test_plan_rejects_every_non_failed_command_and_nonterminal_original_workflow(self):
        for status in ["Success", "Pending", "InProgress", "Delayed", "Cancelling", "Cancelled", "TimedOut", "UNKNOWN", None]:
            with self.subTest(status=status):
                self.adapter = FakeClosureAdapter()
                self.adapter.proof["commandStatus"] = status
                with self.assertRaises(ValueError): self.plan_now()
                self.assertEqual([], self.adapter.mutations())
        for key, value in [("workflowStatus", "in_progress"), ("workflowRunId", "999"),
                           ("workflowRunAttempt", "1"), ("pendingCommands", 1),
                           ("pendingCommands", None), ("pendingCommands", False)]:
            with self.subTest(key=key, value=value):
                self.adapter = FakeClosureAdapter(); self.adapter.proof[key] = value
                with self.assertRaises(ValueError): self.plan_now()
                self.assertEqual([], self.adapter.mutations())

    def test_plan_requires_full_exact_baseline_not_health_alone(self):
        invalid = {"phase": "DEPLOYED", "guardTrusted": False, "historyMatches": False, "health": "starting",
                   "sourceSha": "0" * 40, "releaseId": "a" * 40 + "-154-2", "buildRunId": "99",
                   "buildRunAttempt": "2", "imageDigest": "sha256:" + "0" * 64,
                   "schemaFingerprint": "bad", "guardFingerprint": None, "historyDigest": "bad"}
        for key, value in invalid.items():
            with self.subTest(key=key):
                self.adapter = FakeClosureAdapter(); self.adapter.proof["hostProof"][key] = value
                with self.assertRaises(ValueError): self.plan_now()
                self.assertEqual([], self.adapter.mutations())
        for key in ["guardTrusted", "historyMatches"]:
            self.adapter = FakeClosureAdapter(); self.adapter.proof["hostProof"][key] = 1
            with self.assertRaises(ValueError): self.plan_now()

    def test_plan_rejects_changed_missing_unknown_or_extra_rules(self):
        for rules in [{}, {RULES[0]: "ENABLED"}, {**evidence()["rules"], RULES[1]: "DISABLED"},
                      {**evidence()["rules"], "foreign-rule": "ENABLED"}, {name: "UNKNOWN" for name in RULES}]:
            with self.subTest(rules=rules):
                self.adapter = FakeClosureAdapter(); self.adapter.proof["rules"] = rules
                with self.assertRaises(ValueError): self.plan_now()
                self.assertEqual([], self.adapter.mutations())

    def test_plan_only_accepts_bound_attempted_automatic_unknown_failure(self):
        for key, value in [("protocol", "meet-me-transition-operation-v1"), ("deploymentState", "NOT_ATTEMPTED"),
                           ("deploymentState", "APPLIED"), ("outcome", "APPLIED"), ("rulesState", "RESTORED"),
                           ("commandId", None), ("sequence", True), ("sequence", 0), ("operationId", "0" * 64)]:
            with self.subTest(key=key, value=value):
                self.adapter = FakeClosureAdapter(); self.adapter.snapshot["journal"][key] = value
                with self.assertRaises(ValueError): self.plan_now()
                self.assertEqual([], self.adapter.mutations())

    def test_plan_rejects_foreign_missing_released_or_malformed_owner(self):
        for changed in [None, {**owner(), "released": True}, {**owner(), "holder": "bad"},
                        {**owner(), "operationId": "0" * 64}, {**owner(), "extra": SENTINEL},
                        {**owner(), "identity": identity() | {"workflowRunAttempt": "1"}}]:
            with self.subTest(owner=changed):
                self.adapter = FakeClosureAdapter(); self.adapter.snapshot["owner"] = changed
                with self.assertRaises(ValueError): self.plan_now()
                self.assertEqual([], self.adapter.mutations())

    def test_plan_rejects_extra_untrusted_evidence_and_snapshot_fields(self):
        for location in ["snapshot", "evidence", "hostProof", "context", "journal"]:
            self.adapter = FakeClosureAdapter()
            target = {"snapshot": self.adapter.snapshot, "evidence": self.adapter.proof,
                      "hostProof": self.adapter.proof["hostProof"], "context": self.adapter.target,
                      "journal": self.adapter.snapshot["journal"]}[location]
            target["untrusted"] = SENTINEL
            with self.subTest(location=location), self.assertRaises(ValueError): self.plan_now()
            self.assertEqual([], self.adapter.mutations())

    def test_plan_checks_stable_snapshot_including_same_content_etag_change(self):
        for change in [lambda a: a.mutate_journal(),
                       lambda a: a.snapshot.update(journalEtag='"same-content-new-etag"'),
                       lambda a: a.snapshot.update(ownerEtag='"same-owner-new-etag"')]:
            self.adapter = FakeClosureAdapter()
            self.adapter.hooks[("evidence", "after")] = change
            with self.assertRaises(ValueError): self.plan_now()
            self.assertEqual([], self.adapter.mutations())

    def test_valid_closure_preserves_failed_journal_and_commits_exact_sidecars(self):
        result = self.apply()
        self.assert_result(result, True)
        intent, closure, _, released = intended_records(self.plan)
        self.assertEqual(intent, self.adapter.snapshot["intent"])
        self.assertEqual(closure, self.adapter.snapshot["closure"])
        self.assertEqual(released, self.adapter.snapshot["owner"])
        self.assertEqual(["put_intent", "fence_owner", "put_closure", "release_owner"], self.adapter.mutations())
        self.assertGreaterEqual(self.adapter.evidence_reads, 2)

    def test_exact_completed_retry_is_read_only_and_never_replays_deployment(self):
        self.assert_result(self.apply(), True)
        self.adapter.events.clear()
        self.assert_result(self.apply(), True)
        self.assertEqual([], self.adapter.mutations())

    def test_apply_rejects_unapproved_or_tampered_plan_before_mutation(self):
        for key, value in [("protocol", "unknown"), ("recoveryHolder", "0" * 32),
                           ("approvalDigest", "0" * 64), ("operationId", "0" * 64), ("extra", SENTINEL)]:
            self.adapter = FakeClosureAdapter(); changed = copy.deepcopy(self.plan); changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.apply(changed)
            self.assertEqual([], self.adapter.mutations())
        with self.assertRaises(ValueError): self.apply(approval="f" * 64)
        self.assertEqual([], self.adapter.mutations())

    def test_apply_never_substitutes_a_newly_observed_context_for_the_approved_target(self):
        for key, value in [("accountId", "999999999999"), ("region", "us-east-1"),
                           ("instanceId", "i-fffffffffffffffff"), ("bucket", "foreign-bucket"),
                           ("manifestSha256", "0" * 64)]:
            self.adapter = FakeClosureAdapter(); self.adapter.target[key] = value
            with self.subTest(key=key):
                result = self.apply(); self.assert_result(result, False)
                self.assertEqual([], self.adapter.mutations())

    def test_fresh_proof_change_before_apply_cannot_be_reapproved_implicitly(self):
        for mutate in [lambda a: a.proof.update(pendingCommands=1),
                       lambda a: a.proof.update(commandStatus="Success"),
                       lambda a: a.proof["hostProof"].update(historyDigest="2" * 64),
                       lambda a: a.proof["hostProof"].update(guardFingerprint="2" * 64)]:
            self.adapter = FakeClosureAdapter(); mutate(self.adapter)
            self.assert_result(self.apply(), False)
            self.assertEqual([], self.adapter.mutations())

    def test_evidence_changed_after_fence_prevents_closure_and_release(self):
        self.adapter.hooks[("fence_owner", "after")] = lambda a: a.proof.update(pendingCommands=1)
        self.assert_result(self.apply(), False)
        self.assertIsNone(self.adapter.snapshot["closure"])
        self.assert_no_release()

    def test_delayed_old_journal_write_after_fence_is_detected_not_erased(self):
        self.adapter.hooks[("fence_owner", "after")] = lambda a: a.mutate_journal()
        result = self.apply()
        self.assertEqual("INCOMPLETE", result["outcome"])
        self.assertIs(False, result["deploymentComplete"])
        self.assertEqual(5, self.adapter.snapshot["journal"]["sequence"])
        self.assertIsNone(self.adapter.snapshot["closure"])
        self.assert_no_release()

    def test_changed_journal_etag_after_fence_blocks_even_without_content_change(self):
        self.adapter.hooks[("fence_owner", "after")] = lambda a: a.snapshot.update(journalEtag='"new-identical"')
        self.assert_result(self.apply(), False)
        self.assert_no_release()

    def test_closure_commit_is_not_permission_to_release_after_proof_changes(self):
        self.adapter.hooks[("put_closure", "after")] = lambda a: a.proof["hostProof"].update(health="unhealthy")
        self.assert_result(self.apply(), False)
        self.assertIsNotNone(self.adapter.snapshot["closure"])
        self.assert_no_release()

    def test_owner_cas_conflict_never_overwrites_competing_holder(self):
        def competitor(a):
            a.snapshot["owner"]["holder"] = "9" * 32
            a.snapshot["ownerEtag"] = '"competing-holder"'
        self.adapter.hooks[("release_owner", "before")] = competitor
        self.assert_result(self.apply(), False)
        self.assertEqual("9" * 32, self.adapter.snapshot["owner"]["holder"])
        self.assertIs(False, self.adapter.snapshot["owner"]["released"])

    def test_foreign_or_modified_sidecars_cannot_be_replaced_or_used_as_authority(self):
        intent, closure, _, _ = intended_records(self.plan)
        for key, record in [("intent", intent | {"planDigest": "0" * 64}),
                            ("closure", closure | {"commandId": "00000000-0000-4000-8000-000000000999"})]:
            self.adapter = FakeClosureAdapter(); self.adapter.snapshot[key] = record
            self.assert_result(self.apply(), False)
            self.assertEqual(record, self.adapter.snapshot[key])
            self.assert_no_release()

    def test_exact_partial_prefixes_resume_without_overwriting_immutable_objects(self):
        intent, closure, fenced, _ = intended_records(self.plan)
        for phase in ["intent", "fenced", "committed"]:
            with self.subTest(phase=phase):
                self.adapter = FakeClosureAdapter(); self.adapter.snapshot["intent"] = copy.deepcopy(intent)
                if phase != "intent":
                    self.adapter.snapshot.update(owner=copy.deepcopy(fenced), ownerEtag='"owner-2"')
                    self.adapter.owner_version = 2
                if phase == "committed": self.adapter.snapshot["closure"] = copy.deepcopy(closure)
                self.assert_result(self.apply(), True)
                self.assertNotIn("put_intent", self.adapter.mutations())
                if phase != "intent": self.assertNotIn("fence_owner", self.adapter.mutations())
                if phase == "committed": self.assertNotIn("put_closure", self.adapter.mutations())

    def test_delivery_and_response_loss_at_each_write_is_safe_and_retryable(self):
        for seam in ["put_intent", "fence_owner", "put_closure", "release_owner"]:
            for phase in ["before", "after"]:
                with self.subTest(seam=seam, phase=phase):
                    self.adapter = FakeClosureAdapter(); self.adapter.faults[(seam, phase)] = True
                    result = self.apply()
                    self.assertIn(result["outcome"], {"CLOSED", "INCOMPLETE"})
                    self.assertIs(False, result["deploymentComplete"])
                    if result["outcome"] == "CLOSED": self.assert_result(result, True)
                    else: self.assert_result(result, False)
                    self.assert_result(self.apply(), True)

    def test_lost_release_reply_requires_readback_not_a_second_unconditional_write(self):
        self.adapter.faults[("release_owner", "after")] = True
        self.assert_result(self.apply(), True)
        self.assertEqual(1, self.adapter.mutations().count("release_owner"))

    def test_unavailable_observation_after_a_write_returns_sanitized_incomplete(self):
        self.adapter.hooks[("put_intent", "after")] = lambda a: a.faults.update({("snapshot", "before"): True})
        self.assert_result(self.apply(), False)
        self.assert_no_release()

    def test_completed_owner_without_exact_intent_and_closure_is_never_closed(self):
        _, _, _, released = intended_records(self.plan)
        self.adapter.snapshot["owner"] = released
        self.adapter.snapshot["ownerEtag"] = '"owner-3"'
        self.assert_result(self.apply(), False)
        self.assertEqual([], self.adapter.mutations())

    def test_resealed_plan_rejects_numeric_aliases_for_literal_original_types(self):
        for location, key, alias in [("originalJournal", "sequence", 4.0),
                                     ("originalOwner", "released", 0),
                                     ("evidence", "pendingCommands", False)]:
            changed = reviewed_plan()
            changed[location][key] = alias
            reseal_plan(changed)
            with self.subTest(location=location), self.assertRaises(ValueError): self.apply(changed)
            self.assertEqual([], self.adapter.mutations())

    def test_persisted_numeric_aliases_are_not_exact_approved_terminal_records(self):
        attacks = ["closure_false_as_zero", "owner_true_as_one", "journal_sequence_as_float",
                   "intent_original_owner_false_as_zero", "intent_original_journal_sequence_as_float"]
        for attack in attacks:
            with self.subTest(attack=attack):
                self.adapter = FakeClosureAdapter()
                intent, closure, _, released = intended_records(self.plan)
                self.adapter.snapshot.update(intent=copy.deepcopy(intent), closure=copy.deepcopy(closure),
                                             owner=copy.deepcopy(released), ownerEtag='"owner-3"')
                if attack == "closure_false_as_zero":
                    self.adapter.snapshot["closure"]["deploymentComplete"] = 0
                elif attack == "owner_true_as_one":
                    self.adapter.snapshot["owner"]["released"] = 1
                elif attack == "journal_sequence_as_float":
                    self.adapter.snapshot["journal"]["sequence"] = 4.0
                elif attack == "intent_original_owner_false_as_zero":
                    self.adapter.snapshot["intent"]["plan"]["originalOwner"]["released"] = 0
                else:
                    self.adapter.snapshot["intent"]["plan"]["originalJournal"]["sequence"] = 4.0
                before = canonical(self.adapter.snapshot)
                result = self.apply()
                self.assertEqual("INCOMPLETE", result["outcome"])
                self.assertIs(False, result["deploymentComplete"])
                self.assertIs(False, result["ownerReleased"])
                self.assertEqual(before, canonical(self.adapter.snapshot))
                self.assertEqual([], self.adapter.mutations())


if __name__ == "__main__":
    unittest.main()
