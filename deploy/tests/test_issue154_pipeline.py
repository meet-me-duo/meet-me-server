"""Independent issue154 pipeline entry boundaries; fixture event data only."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


PIPELINE = module("issue154_pipeline", ROOT / "pipeline/release_pipeline.py")
DRIVER = module("issue154_existing_driver", ROOT / "pipeline/run-approved-release.py")
GATE_FIXTURE = module("issue154_gate_fixture", ROOT / "tests/test_issue154_approval.py")
REPOSITORY = "meet-me-duo/meet-me-server"
SHA = "a" * 40


def context(event_name="workflow_run"):
    return {"repository": REPOSITORY, "ref": "refs/heads/main", "eventName": event_name, "sourceSha": SHA}


def event():
    return {"repository": {"full_name": REPOSITORY}, "workflow_run": {
        "name": "CI", "event": "push", "conclusion": "success", "head_branch": "main", "head_sha": SHA,
        "repository": {"full_name": REPOSITORY}, "head_repository": {"full_name": REPOSITORY}}}


def identity():
    return {"sourceSha": SHA, "buildRunId": "154", "buildRunAttempt": "1", "workflowRunId": "154",
            "workflowRunAttempt": "1", "releaseId": SHA + "-154-1", "imageDigest": "sha256:" + "b" * 64}


def complete_marker():
    return {"protocol": "meet-me-deployment-completion-v1", "identity": identity(), "action": "automatic",
            "mode": "AUTO_READY", "deploymentState": "APPLIED", "deploymentComplete": True,
            "outcome": "DEPLOYED_VERIFIED", "hostVerified": True, "publicContractVerified": True,
            "rulesRestored": True, "pendingCommands": 0}


def marker_line(value=None):
    return b"MEETME_DEPLOYMENT_RESULT_V1 " + json.dumps(
        complete_marker() if value is None else value, separators=(",", ":"), sort_keys=True).encode("utf-8")


class Issue154PipelineEntryTests(unittest.TestCase):
    def validate(self, action, approve, payload=None, ctx=None, **gate):
        return PIPELINE.validate_pipeline_action(DRIVER, event() if payload is None else payload,
                                                context() if ctx is None else ctx, action, approve, **gate)

    def test_successful_own_main_push_allows_normal_automatic_without_approval(self):
        self.assertEqual(SHA, self.validate("automatic", False))

    def test_normal_automatic_cannot_synthesize_transition_approval(self):
        with self.assertRaises(ValueError):
            self.validate("automatic", True)

    def test_workflow_run_cannot_upgrade_itself_to_deploy_or_resume_using_bare_boolean(self):
        for action in ("deploy", "resume"):
            with self.subTest(action=action), self.assertRaises(ValueError):
                self.validate(action, True)

    def test_missing_or_forged_gate_inputs_cannot_authorize_transition(self):
        for gate in ({}, {"identity": identity()},
                     {"identity": identity(), "gate_config": {}, "gate_evidence": {}},
                     {"identity": identity(), "gate_config": {"approved": True}, "gate_evidence": {"approved": True}}):
            with self.subTest(gate=gate), self.assertRaises(ValueError):
                self.validate("deploy", True, **gate)

    def test_existing_repository_main_and_ci_origin_checks_remain_required(self):
        mutations = []
        for field, value in (("name", "Other"), ("event", "pull_request"), ("conclusion", "failure"),
                             ("head_branch", "develop"), ("head_sha", "c" * 40)):
            payload = event()
            payload["workflow_run"][field] = value
            mutations.append(payload)
        for field in ("repository", "head_repository"):
            payload = event()
            payload["workflow_run"][field]["full_name"] = "untrusted/fork"
            mutations.append(payload)
        payload = event()
        payload["repository"]["full_name"] = "untrusted/fork"
        mutations.append(payload)
        for payload in mutations:
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                self.validate("automatic", False, payload=payload)

    def test_non_main_or_unknown_event_is_not_a_valid_entry(self):
        for mutation in ({"ref": "refs/heads/develop"}, {"repository": "untrusted/fork"},
                         {"eventName": "pull_request"}, {"sourceSha": "not-a-sha"}):
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.validate("automatic", False, ctx=context() | mutation)

    def test_manual_main_preflight_remains_explicitly_read_only(self):
        self.assertEqual(SHA, self.validate("preflight", False,
                                          payload={"repository": {"full_name": REPOSITORY}},
                                          ctx=context("workflow_dispatch")))

    def test_manual_deploy_resume_retains_explicit_literal_approval(self):
        for action in ("deploy", "resume"):
            with self.subTest(action=action):
                self.assertEqual(SHA, self.validate(action, True,
                                                  payload={"repository": {"full_name": REPOSITORY}},
                                                  ctx=context("workflow_dispatch")))

    def test_dispatch_cannot_forge_automatic_origin(self):
        with self.assertRaises(ValueError):
            self.validate("automatic", False, payload={"repository": {"full_name": REPOSITORY}},
                          ctx=context("workflow_dispatch"))

    def test_action_and_approval_are_not_truthy_or_free_form(self):
        for action, approval in (("shell", True), ("deploy", "true"), ("automatic", 0),
                                 ("automatic", None), ("automatic", "false")):
            with self.subTest(action=action, approval=approval), self.assertRaises(ValueError):
                self.validate(action, approval)

    def test_same_run_exact_gate_approval_can_continue_workflow_run_transition(self):
        bound, policy, proof = GATE_FIXTURE.fixture()
        for action in ("deploy", "resume"):
            with self.subTest(action=action):
                self.assertEqual(SHA, self.validate(action, True, identity=bound, gate_config=policy,
                                                   gate_evidence=proof))

    def test_gate_success_does_not_waive_existing_main_ci_source_restrictions(self):
        bound, policy, proof = GATE_FIXTURE.fixture()
        bad = event()
        bad["workflow_run"]["head_sha"] = "c" * 40
        with self.assertRaises(ValueError):
            self.validate("deploy", True, payload=bad, identity=bound, gate_config=policy, gate_evidence=proof)


class Issue154PipelineMarkerTests(unittest.TestCase):
    def read(self, raw, bound=None):
        return PIPELINE.read_completion_marker(raw, identity() if bound is None else bound)

    def test_safe_stdout_marker_is_independently_readable_without_artifact_zip(self):
        raw = b"unrelated safe output\n" + marker_line() + b"\noperation ended\n"
        self.assertEqual(complete_marker(), self.read(raw))

    def test_actual_github_timestamp_prefix_is_accepted_without_relaxing_marker_anchor(self):
        for timestamp in (b"2026-10-28T14:00:00Z ", b"2026-10-28T14:00:00.1234567Z "):
            with self.subTest(timestamp=timestamp):
                self.assertEqual(complete_marker(), self.read(timestamp + marker_line() + b"\n"))

    def test_missing_wrong_version_or_embedded_marker_is_rejected(self):
        for raw in (b"ordinary output", json.dumps(complete_marker()).encode(),
                    marker_line().replace(b"RESULT_V1", b"RESULT_V0"), b"untrusted prefix " + marker_line(),
                    b"not-a-timestamp " + marker_line()):
            with self.subTest(raw=raw[:60]), self.assertRaises(ValueError):
                self.read(raw)

    def test_multiple_markers_are_ambiguous_even_if_equal(self):
        with self.assertRaises(ValueError):
            self.read(marker_line() + b"\n" + marker_line())

    def test_json_duplicate_keys_are_rejected_at_outer_and_nested_levels(self):
        raw = marker_line()
        duplicate_outer = raw.replace(b'"deploymentComplete":true',
                                      b'"deploymentComplete":false,"deploymentComplete":true')
        duplicate_nested = raw.replace(b'"sourceSha":"' + SHA.encode() + b'"',
                                       b'"sourceSha":"' + SHA.encode() + b'","sourceSha":"' + SHA.encode() + b'"')
        for candidate in (duplicate_outer, duplicate_nested):
            self.assertNotEqual(raw, candidate)
            with self.subTest(candidate=candidate[:60]), self.assertRaises(ValueError):
                self.read(candidate)

    def test_invalid_utf8_or_oversized_input_and_marker_are_rejected(self):
        for raw in (b"\xff" + marker_line(), b"x" * 65537 + b"\n" + marker_line(),
                    b"MEETME_DEPLOYMENT_RESULT_V1 " + b" " * 8193 + marker_line().split(b" ", 1)[1]):
            with self.subTest(size=len(raw)), self.assertRaises(ValueError):
                self.read(raw)

    def test_stale_attempt_or_source_cannot_be_accepted_from_otherwise_valid_logs(self):
        for mutation in ({"workflowRunId": "999"}, {"workflowRunAttempt": "2"},
                         {"imageDigest": "sha256:" + "c" * 64}):
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.read(marker_line(), identity() | mutation)

    def test_noop_unknown_or_forged_completion_payload_is_not_proof(self):
        for mutation in ({"deploymentState": "UNKNOWN"}, {"outcome": "TRANSITION_REQUIRED"},
                         {"deploymentComplete": "true"}, {"hostVerified": False},
                         {"rulesRestored": False}, {"publicContractVerified": False}, {"pendingCommands": None}):
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.read(marker_line(complete_marker() | mutation))


if __name__ == "__main__":
    unittest.main()
