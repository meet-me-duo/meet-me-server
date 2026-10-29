"""Independent production-controller wrappers using real cores and fake AWS.

Entry evidence and release preparation are explicit fixtures; this does not claim
to execute real environment approval, host scripts or a production deployment.
"""
import contextlib
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from test_issue154_adapter import PIPELINE, FakeAwsDriver, release, RULES, SWITCH
from test_issue154_public_contract import contract


class Issue154ControllerAuditTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="issue154-controller-test-")
        self.addCleanup(self.directory.cleanup)
        self.driver = FakeAwsDriver()
        self.release = release(self.driver, self.directory.name)
        self.release.update(action="automatic", approve=False, mode="AUTO_READY",
                            parameterReady=True, publicContract=contract())
        self.plan = {"identity": self.release["identity"], "manifest": self.release["manifest"]}
        self.outputs = []

    def execute(self, public=True, transition=False, gate_error=None):
        with patch.object(PIPELINE, "_read_plan", return_value=self.plan), \
             patch.object(PIPELINE, "prepare_release", return_value=contextlib.nullcontext(self.release)), \
             patch.object(PIPELINE, "validate_transition_entry", side_effect=gate_error) as gate, \
             patch.object(PIPELINE, "verify_public_contract", return_value=public) as public_check, \
             patch.object(PIPELINE, "_emit", side_effect=lambda value, *a, **k: self.outputs.append(copy.deepcopy(value))):
            result = PIPELINE.execute_pipeline(self.driver, {}, transition)
        return result, gate, public_check

    def sends(self):
        return [c for c in self.driver.calls if c[0] == "ssm" and c[1] == "send-command"]

    def test_automatic_wrapper_uses_real_core_and_requires_every_completion_axis(self):
        _, gate, public_check = self.execute()
        gate.assert_not_called()
        public_check.assert_called_once_with(contract())
        self.assertEqual(1, len(self.sends()))
        self.assertEqual(1, len(self.outputs))
        marker = self.outputs[0]
        self.assertIs(marker["deploymentComplete"], True)
        self.assertEqual("APPLIED", marker["deploymentState"])
        self.assertEqual("DEPLOYED_VERIFIED", marker["outcome"])
        self.assertIs(marker["hostVerified"], True)
        self.assertIs(marker["rulesRestored"], True)

    def test_applied_host_with_failed_public_proof_emits_unverified_and_fails(self):
        with self.assertRaises(ValueError):
            self.execute(public=False)
        self.assertEqual(1, len(self.sends()))
        marker = self.outputs[-1]
        self.assertEqual("APPLIED", marker["deploymentState"])
        self.assertEqual("DEPLOYED_UNVERIFIED", marker["outcome"])
        self.assertIs(marker["deploymentComplete"], False)

    def test_missing_provider_metadata_never_submits_a_deploy(self):
        self.driver.parameters[SWITCH] = {"Name": SWITCH, "Type": "String", "Version": 1, "Value": "true"}
        with self.assertRaises(ValueError):
            self.execute()
        self.assertFalse(self.sends())
        self.assertIs(self.outputs[-1]["deploymentComplete"], False)
        self.assertEqual("NOT_ATTEMPTED", self.outputs[-1]["deploymentState"])

    def test_lost_send_reply_is_unknown_and_second_runner_cannot_resubmit(self):
        original = self.driver._aws

        def lost_reply(service, operation, *args):
            result = original(service, operation, *args)
            if service == "ssm" and operation == "send-command":
                raise ValueError("synthetic lost reply after accepted send")
            return result

        self.driver._aws = lost_reply
        for _ in range(2):
            with self.assertRaises(ValueError):
                self.execute()
            self.assertEqual("UNKNOWN", self.outputs[-1]["deploymentState"])
            self.assertIs(self.outputs[-1]["deploymentComplete"], False)
        self.assertEqual(1, len(self.sends()))

    def test_old_successful_command_cannot_hide_fresh_runtime_drift(self):
        original = self.driver._collect_facts
        reads = []

        def changed_after_validation(*args, **kwargs):
            facts, ready = original(*args, **kwargs)
            reads.append(True)
            if len(reads) >= 2:
                facts["currentImage"] = self.release["manifest"]["imageRepository"] + "@sha256:" + "f" * 64
            return facts, ready

        self.driver._collect_facts = changed_after_validation
        with self.assertRaises(ValueError):
            self.execute()
        self.assertEqual(1, len(self.sends()))
        self.assertEqual("UNKNOWN", self.outputs[-1]["deploymentState"])
        self.assertIs(self.outputs[-1]["hostVerified"], False)
        self.assertIs(self.outputs[-1]["deploymentComplete"], False)

    def test_transition_gate_failure_precedes_any_aws_effect_or_prepare(self):
        with patch.object(PIPELINE, "_read_plan", return_value=self.plan), \
             patch.object(PIPELINE, "prepare_release") as prepare, \
             patch.object(PIPELINE, "validate_transition_entry", side_effect=ValueError("untrusted gate")):
            with self.assertRaises(ValueError):
                PIPELINE.execute_pipeline(self.driver, {}, True)
            prepare.assert_not_called()
        self.assertFalse(self.driver.calls)

    @contextlib.contextmanager
    def private_cwd(self):
        original = Path.cwd()
        os.chdir(self.directory.name)
        try:
            yield
        finally:
            os.chdir(original)

    def test_unconfigured_transition_plan_never_outputs_an_environment_name(self):
        self.release["mode"] = "TRANSITION_REQUIRED"
        rows = []
        with self.private_cwd(), \
             patch.object(PIPELINE, "prepare_release", return_value=contextlib.nullcontext(self.release)), \
             patch.object(PIPELINE, "_gate_config", return_value={"protocol": "meet-me-transition-gate-policy-v1", "state": "UNCONFIGURED"}), \
             patch.object(PIPELINE, "_gate_environment") as gate_read, \
             patch.object(PIPELINE, "_outputs", side_effect=lambda p: rows.append(copy.deepcopy(p))), \
             patch.object(PIPELINE, "_emit"):
            with self.assertRaises(ValueError):
                PIPELINE.plan_pipeline(self.driver, {})
            gate_read.assert_not_called()
        self.assertEqual(1, len(rows))
        self.assertIs(rows[0]["gateReady"], False)
        self.assertIsNone(rows[0]["gateEnvironment"])
        self.assertFalse(self.sends())

    def test_normal_auto_ready_plan_needs_no_special_approval_environment(self):
        rows = []
        with self.private_cwd(), \
             patch.object(PIPELINE, "prepare_release", return_value=contextlib.nullcontext(self.release)), \
             patch.object(PIPELINE, "_gate_config", return_value={"protocol": "meet-me-transition-gate-policy-v1", "state": "UNCONFIGURED"}), \
             patch.object(PIPELINE, "_gate_environment") as gate_read, \
             patch.object(PIPELINE, "_outputs", side_effect=lambda p: rows.append(copy.deepcopy(p))), \
             patch.object(PIPELINE, "_emit"):
            PIPELINE.plan_pipeline(self.driver, {})
            gate_read.assert_not_called()
        self.assertFalse(rows[0]["gateReady"])
        self.assertIsNone(rows[0]["gateEnvironment"])
        self.assertFalse(self.sends())


if __name__ == "__main__":
    unittest.main()

