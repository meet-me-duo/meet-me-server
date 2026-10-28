"""Independent final CLI observability contract for absent execution artifacts."""
import contextlib
import copy
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from test_issue154_adapter import PIPELINE
from test_issue154_completion import identity, evidence
from test_issue154_prepare_audit import ENTRY, REPOSITORY


class Issue154CompletionCliTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="issue154-completion-cli-test-")
        self.addCleanup(self.directory.cleanup)
        self.bound = identity()
        self.bound.update(workflowRunId=self.bound["buildRunId"], workflowRunAttempt=self.bound["buildRunAttempt"])
        self.plan = {"identity": self.bound, "action": "automatic", "mode": "TRANSITION_REQUIRED",
                     "originalRules": copy.deepcopy(evidence()["originalRuleStates"])}
        self.emitted = []
        self.driver = SimpleNamespace(validate_workflow_event=ENTRY.validate_workflow_event)
        event_path = Path(self.directory.name) / "event.json"
        event_path.write_text(json.dumps({"repository": {"full_name": REPOSITORY}, "workflow_run": {
            "id": 153, "name": "CI", "event": "push", "conclusion": "success", "head_branch": "main",
            "head_sha": self.bound["sourceSha"], "repository": {"full_name": REPOSITORY},
            "head_repository": {"full_name": REPOSITORY},
        }}))
        self.environment = {"GITHUB_EVENT_PATH": str(event_path), "GITHUB_REPOSITORY": REPOSITORY,
                            "GITHUB_REF": "refs/heads/main", "GITHUB_EVENT_NAME": "workflow_run",
                            "SOURCE_SHA": self.bound["sourceSha"]}

    @contextlib.contextmanager
    def cwd(self):
        original = Path.cwd()
        os.chdir(self.directory.name)
        try:
            yield
        finally:
            os.chdir(original)

    def invoke(self, statuses, raw=None, expect_failure=True):
        with self.cwd(), patch.dict(os.environ, self.environment | statuses), \
             patch.object(PIPELINE, "_read_plan", return_value=self.plan), \
             patch.object(PIPELINE, "_emit", side_effect=lambda value, *a, **k: self.emitted.append(copy.deepcopy(value))):
            if raw is not None:
                Path("deployment-result.json").write_bytes(raw)
            if expect_failure:
                with self.assertRaises(Exception):
                    PIPELINE.main(self.driver, ["--pipeline-complete"])
            else:
                PIPELINE.main(self.driver, ["--pipeline-complete"])

    def assert_false_bound_unknown_observations(self, state):
        self.assertEqual(1, len(self.emitted), "final CLI must emit a bound failure outcome, not only ArtifactNotFound")
        result = self.emitted[0]
        self.assertEqual(self.bound, result["identity"])
        self.assertIs(result["deploymentComplete"], False)
        self.assertEqual(state, result["deploymentState"])
        self.assertIs(result["hostVerified"], False)
        self.assertIs(result["publicContractVerified"], False)
        self.assertIs(result["rulesRestored"], False)
        self.assertIsNone(result["pendingCommands"])

    def test_both_execution_jobs_skipped_emit_not_attempted_when_artifact_missing(self):
        self.invoke({"NORMAL_RELEASE_RESULT": "skipped", "TRANSITION_RELEASE_RESULT": "skipped"})
        self.assert_false_bound_unknown_observations("NOT_ATTEMPTED")
        self.assertEqual("TRANSITION_REQUIRED", self.emitted[0]["outcome"])

    def test_malformed_artifact_after_skipped_jobs_still_emits_explicit_false_proof(self):
        self.invoke({"NORMAL_RELEASE_RESULT": "skipped", "TRANSITION_RELEASE_RESULT": "skipped"},
                    b'{"deploymentComplete":true}')
        self.assert_false_bound_unknown_observations("NOT_ATTEMPTED")

    def test_missing_failed_or_cancelled_execution_artifact_is_unknown_not_not_attempted(self):
        for normal, transition in (("failure", "skipped"), ("skipped", "cancelled"), ("success", "skipped")):
            self.emitted.clear()
            with self.subTest(normal=normal, transition=transition):
                self.invoke({"NORMAL_RELEASE_RESULT": normal, "TRANSITION_RELEASE_RESULT": transition})
                self.assert_false_bound_unknown_observations("UNKNOWN")
                self.assertEqual("RECOVERY_REQUIRED", self.emitted[0]["outcome"])

    def test_unknown_executor_status_cannot_prove_no_deployment(self):
        self.invoke({"NORMAL_RELEASE_RESULT": "", "TRANSITION_RELEASE_RESULT": "skipped"})
        self.assert_false_bound_unknown_observations("UNKNOWN")

    def test_invalid_executor_status_cannot_inject_summary_or_synthesize_noop(self):
        self.invoke({"NORMAL_RELEASE_RESULT": "skipped\nInjected", "TRANSITION_RELEASE_RESULT": "skipped"})
        self.assert_false_bound_unknown_observations("UNKNOWN")
        self.assertNotIn("Injected", json.dumps(self.emitted))

    def test_valid_bound_complete_artifact_is_not_overwritten_by_fallback(self):
        self.plan["mode"] = "AUTO_READY"
        marker = PIPELINE._module("release_completion").evaluate_completion(self.bound, evidence())
        self.invoke({"NORMAL_RELEASE_RESULT": "success", "TRANSITION_RELEASE_RESULT": "skipped"},
                    json.dumps(marker).encode(), expect_failure=False)
        self.assertEqual([marker], self.emitted)
        self.assertIs(self.emitted[0]["deploymentComplete"], True)


if __name__ == "__main__":
    unittest.main()

