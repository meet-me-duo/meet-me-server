"""Independent issue154 completion contract. No AWS, DB, network or secret calls."""
import copy
import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("issue154_completion", ROOT / "pipeline/release_completion.py")
COMPLETION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPLETION)
RULES = ("meet-me-production-db-credential-reconcile", "meet-me-production-db-credential-rotated")


def identity():
    source = "a" * 40
    return {"sourceSha": source, "buildRunId": "154", "buildRunAttempt": "1",
            "workflowRunId": "155", "workflowRunAttempt": "2", "releaseId": source + "-154-1",
            "imageDigest": "sha256:" + "b" * 64}


def evidence():
    bound = identity()
    states = {name: "ENABLED" for name in RULES}
    return {"action": "automatic", "mode": "AUTO_READY", "deploymentState": "APPLIED",
            "host": {"sourceSha": bound["sourceSha"], "releaseId": bound["releaseId"], "phase": "READY",
                     "guardTrusted": True, "imageMatches": True, "historyMatches": True},
            "publicContractVerified": True, "originalRuleStates": dict(states), "ruleStates": dict(states),
            "pendingCommands": 0}


class Issue154CompletionTests(unittest.TestCase):
    def evaluate(self, observed=None):
        return COMPLETION.evaluate_completion(identity(), evidence() if observed is None else observed)

    def test_complete_requires_bound_runtime_database_api_and_original_rule_states(self):
        result = self.evaluate()
        self.assertEqual("meet-me-deployment-completion-v1", result["protocol"])
        self.assertEqual(identity(), result["identity"])
        self.assertIs(result["deploymentComplete"], True)
        self.assertEqual("DEPLOYED_VERIFIED", result["outcome"])
        self.assertIs(result["hostVerified"], True)
        self.assertIs(result["publicContractVerified"], True)
        self.assertIs(result["rulesRestored"], True)
        COMPLETION.require_complete(result, identity())

    def test_result_is_an_allowlisted_safe_contract(self):
        self.assertEqual({"protocol", "identity", "action", "mode", "deploymentState", "deploymentComplete",
                          "outcome", "hostVerified", "publicContractVerified", "rulesRestored", "pendingCommands"},
                         set(self.evaluate()))

    def test_health_or_operation_success_does_not_replace_public_contract(self):
        result = self.evaluate(evidence() | {"publicContractVerified": False})
        self.assertIs(result["deploymentComplete"], False)
        self.assertEqual("APPLIED", result["deploymentState"])
        self.assertEqual("DEPLOYED_UNVERIFIED", result["outcome"])
        with self.assertRaises(ValueError):
            COMPLETION.require_complete(result, identity())

    def test_every_host_postcondition_is_required(self):
        for field, value in (("sourceSha", "c" * 40), ("releaseId", "c" * 40 + "-154-1"),
                             ("phase", "V8_STARTED"), ("guardTrusted", False),
                             ("imageMatches", False), ("historyMatches", False)):
            observed = evidence()
            observed["host"][field] = value
            with self.subTest(field=field):
                result = self.evaluate(observed)
                self.assertIs(result["deploymentComplete"], False)
                self.assertIs(result["hostVerified"], False)
                with self.assertRaises(ValueError):
                    COMPLETION.require_complete(result, identity())

    def test_applied_without_observed_host_is_not_complete(self):
        result = self.evaluate(evidence() | {"host": None})
        self.assertIs(result["deploymentComplete"], False)

    def test_transition_required_is_explicit_not_deployment_success(self):
        result = self.evaluate(evidence() | {"mode": "TRANSITION_REQUIRED", "deploymentState": "NOT_ATTEMPTED",
                                             "host": None, "publicContractVerified": False})
        self.assertEqual("TRANSITION_REQUIRED", result["outcome"])
        self.assertIs(result["deploymentComplete"], False)

    def test_deliberate_preflight_is_read_only_even_when_current_contract_is_ready(self):
        result = self.evaluate(evidence() | {"action": "preflight", "deploymentState": "NOT_ATTEMPTED",
                                             "host": None, "publicContractVerified": False})
        self.assertEqual("PREFLIGHT_ONLY", result["outcome"])
        self.assertIs(result["deploymentComplete"], False)

    def test_lost_reply_remains_unknown_even_if_stale_host_fields_look_successful(self):
        result = self.evaluate(evidence() | {"deploymentState": "UNKNOWN"})
        self.assertEqual("UNKNOWN", result["deploymentState"])
        self.assertEqual("RECOVERY_REQUIRED", result["outcome"])
        self.assertIs(result["deploymentComplete"], False)

    def test_partial_or_unknown_rule_restoration_cannot_be_green(self):
        for name in RULES:
            observed = evidence()
            observed["ruleStates"][name] = "DISABLED"
            with self.subTest(rule=name):
                result = self.evaluate(observed)
                self.assertEqual("APPLIED", result["deploymentState"])
                self.assertIs(result["rulesRestored"], False)
                self.assertEqual("RESTORATION_REQUIRED", result["outcome"])
                self.assertIs(result["deploymentComplete"], False)

    def test_originally_disabled_rule_is_not_required_to_be_enabled(self):
        observed = evidence()
        observed["originalRuleStates"][RULES[0]] = "DISABLED"
        observed["ruleStates"][RULES[0]] = "DISABLED"
        self.assertIs(self.evaluate(observed)["deploymentComplete"], True)

    def test_missing_rule_observation_is_unknown_not_assumed_restored(self):
        for mutation in ({"originalRuleStates": None}, {"ruleStates": None},
                         {"originalRuleStates": None, "ruleStates": None}):
            with self.subTest(mutation=mutation):
                result = self.evaluate(evidence() | mutation)
                self.assertIs(result["rulesRestored"], False)
                self.assertIs(result["deploymentComplete"], False)

    def test_active_command_prevents_completion(self):
        result = self.evaluate(evidence() | {"pendingCommands": 1})
        self.assertIs(result["deploymentComplete"], False)
        with self.assertRaises(ValueError):
            COMPLETION.require_complete(result, identity())

    def test_failed_command_observation_preserves_unknown_instead_of_inventing_zero(self):
        result = self.evaluate(evidence() | {"pendingCommands": None})
        self.assertIsNone(result["pendingCommands"])
        self.assertIs(result["deploymentComplete"], False)
        self.assertEqual("RECOVERY_REQUIRED", result["outcome"])

    def test_approved_deploy_and_resume_can_complete_with_same_postconditions(self):
        for action, mode in (("deploy", "TRANSITION_APPROVED"), ("resume", "MAINTENANCE_RESUME_APPROVED")):
            with self.subTest(action=action):
                self.assertIs(self.evaluate(evidence() | {"action": action, "mode": mode})["deploymentComplete"], True)

    def test_identity_schema_and_exact_release_binding_are_strict(self):
        bad = [identity() | {"unexpected": "not allowed"}]
        for field, value in (("sourceSha", "A" * 40), ("sourceSha", "short"), ("buildRunId", "0"),
                             ("buildRunAttempt", 1), ("workflowRunAttempt", True),
                             ("workflowRunId", "-1"), ("releaseId", "a" * 40 + "-154-2"),
                             ("imageDigest", "sha256:" + "G" * 64)):
            bad.append(identity() | {field: value})
        for field in identity():
            missing = identity()
            missing.pop(field)
            bad.append(missing)
        for bound in bad:
            with self.subTest(bound=bound), self.assertRaises(ValueError):
                COMPLETION.evaluate_completion(bound, evidence())

    def test_untrusted_or_mistyped_evidence_is_rejected(self):
        bad = [evidence() | {"extra": "must never reach logs"}, evidence() | {"pendingCommands": True},
               evidence() | {"publicContractVerified": "true"}, evidence() | {"deploymentState": "SUCCESS"},
               evidence() | {"action": "shell"}, evidence() | {"mode": "always"},
               evidence() | {"ruleStates": {"unrelated-rule": "ENABLED"}},
               evidence() | {"originalRuleStates": {RULES[0]: "ENABLED"}}]
        for field in ("guardTrusted", "imageMatches", "historyMatches"):
            observed = evidence()
            observed["host"][field] = 1
            bad.append(observed)
        for observed in bad:
            with self.subTest(observed=observed), self.assertRaises(ValueError):
                self.evaluate(observed)

    def test_applied_transition_required_and_preflight_are_contradictory(self):
        for mutation in ({"mode": "TRANSITION_REQUIRED"}, {"action": "preflight"}):
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.evaluate(evidence() | mutation)

    def test_completion_marker_cannot_be_rebound_to_another_source_or_attempt(self):
        result = self.evaluate()
        for field, value in (("sourceSha", "c" * 40), ("buildRunId", "156"), ("buildRunAttempt", "2"),
                             ("workflowRunId", "156"), ("workflowRunAttempt", "3"),
                             ("releaseId", "a" * 40 + "-154-2"), ("imageDigest", "sha256:" + "c" * 64)):
            target = identity() | {field: value}
            with self.subTest(field=field), self.assertRaises(ValueError):
                COMPLETION.require_complete(result, target)

    def test_marker_parser_rejects_missing_extra_conflicting_and_string_booleans(self):
        good = self.evaluate()
        bad = [good | {"unexpected": "field"}, good | {"protocol": "old-v1"},
               good | {"deploymentComplete": "true"}, good | {"hostVerified": 1},
               good | {"publicContractVerified": False}, good | {"rulesRestored": False},
               good | {"deploymentState": "UNKNOWN"}, good | {"outcome": "TRANSITION_REQUIRED"},
               good | {"pendingCommands": True}, good | {"pendingCommands": 1}]
        for field in good:
            missing = copy.deepcopy(good)
            missing.pop(field)
            bad.append(missing)
        for marker in bad:
            with self.subTest(marker=marker), self.assertRaises(ValueError):
                COMPLETION.require_complete(marker, identity())

    def test_provider_off_does_not_require_typesafe_registration(self):
        self.assertIs(COMPLETION.provider_metadata_ready(False, {}), True)

    def test_provider_on_requires_typed_existing_typesafe_metadata(self):
        name = "/meet-me/production/secret/typesafe-api-key"
        self.assertIs(COMPLETION.provider_metadata_ready(True, {name: {"Type": "SecureString", "Version": 1}}), True)
        for metadata in ({}, {name: {"Type": "String", "Version": 1}},
                         {name: {"Type": "SecureString", "Version": 0}},
                         {name: {"Type": "SecureString", "Version": True}}):
            with self.subTest(metadata=metadata):
                self.assertIs(COMPLETION.provider_metadata_ready(True, metadata), False)

    def test_provider_flag_is_not_truthy_coerced(self):
        for enabled in ("true", "false", 1, 0, None):
            with self.subTest(enabled=enabled), self.assertRaises(ValueError):
                COMPLETION.provider_metadata_ready(enabled, {})


if __name__ == "__main__":
    unittest.main()
