"""Real adapter projections and prior-operation inspection, using synthetic IO."""
import base64
import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from test_issue154_adapter import FakeAwsDriver, release, identity, COMMAND
from test_issue154_failed_operation_closure import (
    FakeClosureAdapter, reviewed_plan, intended_records, operation_id,
)

BASE = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("closure_integration", BASE / "pipeline/release_pipeline.py")
PIPELINE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PIPELINE)
DRIVER_SPEC = importlib.util.spec_from_file_location("closure_wrapper", BASE / "pipeline/run-approved-release.py")
DRIVER = importlib.util.module_from_spec(DRIVER_SPEC)
DRIVER_SPEC.loader.exec_module(DRIVER)


class EvidenceDriver(FakeAwsDriver):
    def __init__(self):
        super().__init__()
        self.command = {"CommandId": COMMAND, "InstanceId": "i-1234567890abcdef0", "Status": "Failed", "ResponseCode": 2}
        self.run = {"id": 155, "run_attempt": 1, "status": "completed", "path": ".github/workflows/deploy.yml",
                    "head_branch": "main", "event": "workflow_dispatch",
                    "repository": {"full_name": "meet-me-duo/meet-me-server"},
                    "head_repository": {"full_name": "meet-me-duo/meet-me-server"}}
        self.account = "123456789012"
        self.fact_options = []

    def _aws(self, service, operation, *args):
        if (service, operation) == ("sts", "get-caller-identity"):
            return {"Account": self.account}
        if (service, operation) == ("ssm", "get-command-invocation"):
            return copy.deepcopy(self.command)
        return super()._aws(service, operation, *args)

    def _github_run(self, run_id, attempt):
        return copy.deepcopy(self.run)

    def _collect_facts(self, *args, **kwargs):
        self.fact_options.append(kwargs)
        return super()._collect_facts(*args, **kwargs)


class ClosureAdapterEvidenceTests(unittest.TestCase):
    def setUp(self):
        directory = self.enterContext(tempfile.TemporaryDirectory())
        self.driver = EvidenceDriver()
        self.release = release(self.driver, directory)
        self.adapter = PIPELINE.AwsReleaseAdapter(self.release, "preflight")

    def test_actual_projection_binds_complete_history_guard_image_and_exact_attempt(self):
        proof = self.adapter.closure_evidence(identity(), COMMAND)
        self.assertEqual("Failed", proof["commandStatus"])
        self.assertIs(True, proof["hostProof"]["historyMatches"])
        self.assertEqual(PIPELINE._closure_hash(self.driver.facts["history"]), proof["hostProof"]["historyDigest"])
        self.assertIs(True, self.driver.fact_options[-1]["require_healthy"])
        self.assertFalse([c for c in self.driver.calls if c[1] in {"send-command", "put-object", "enable-rule", "disable-rule"}])

    def test_partial_extra_changed_history_or_guard_cannot_be_projected_as_true(self):
        baseline = copy.deepcopy(self.driver.facts)
        changes = [lambda f: f.update(history=[]),
                   lambda f: f["history"].append(dict(f["history"][0], version="20")),
                   lambda f: f["history"][0].update(checksum=999),
                   lambda f: f.update(guardTrusted=False),
                   lambda f: f["automaticContract"].update(guardFingerprint="f" * 64),
                   lambda f: f["automaticContract"].update(schemaFingerprint="f" * 64),
                   lambda f: f.update(currentImage="foreign@sha256:" + "f" * 64)]
        for change in changes:
            with self.subTest(change=change):
                self.driver.facts = copy.deepcopy(baseline)
                change(self.driver.facts)
                with self.assertRaises(ValueError):
                    self.adapter.closure_evidence(identity(), COMMAND)

    def test_foreign_or_active_workflow_command_rule_binding_and_account_reject(self):
        for field, value in [("status", "in_progress"), ("run_attempt", 2), ("id", 156), ("head_branch", "develop")]:
            original = self.driver.run[field]
            self.driver.run[field] = value
            with self.subTest(workflow=field), self.assertRaises(ValueError):
                self.adapter.closure_evidence(identity(), COMMAND)
            self.driver.run[field] = original
        self.driver.command["Status"] = "Success"
        with self.assertRaises(ValueError):
            self.adapter.closure_evidence(identity(), COMMAND)
        self.driver.command["Status"] = "Failed"
        self.driver.bad_target = lambda t: dict(t, RunCommandParameters={"RunCommandTargets": []})
        with self.assertRaises(ValueError):
            self.adapter.closure_evidence(identity(), COMMAND)
        self.driver.account = "999999999999"
        with self.assertRaises(ValueError):
            self.adapter.closure_context()


class PriorClosureInspectionTests(unittest.TestCase):
    def setUp(self):
        self.adapter = FakeClosureAdapter()
        self.plan = reviewed_plan()
        self.intent, self.closure, self.fenced, self.released = intended_records(self.plan)
        self.adapter.snapshot.update(intent=self.intent, closure=self.closure, owner=self.released, ownerEtag='"owner-3"')
        self.adapter.identity = copy.deepcopy(self.plan["identity"])
        self.adapter.driver = object()
        target = self.plan["target"]
        # The new candidate manifest deliberately differs from the closed one.
        self.adapter.release = {"instance": target["instanceId"], "region": target["region"], "bucket": target["bucket"],
                                "manifest": {"imageRepository": target["accountId"] + ".dkr.ecr.ap-northeast-2.amazonaws.com/meet-me/server",
                                             "sourceSha": "f" * 40}}
        self.adapter.read_owner_for_inspection = lambda: (copy.deepcopy(self.adapter.snapshot["owner"]), self.adapter.snapshot["ownerEtag"])
        self.adapter.read_operation_for_reconcile = lambda op: copy.deepcopy(self.adapter.snapshot["journal"])

    def inspect(self):
        with patch.object(PIPELINE, "_prepare_closure_release", return_value=contextlib.nullcontext({})) as prepare, \
                patch.object(PIPELINE, "AwsReleaseAdapter", return_value=self.adapter):
            report = PIPELINE.inspect_recovery_state(operation_id(), self.adapter)
        return report, prepare

    def test_supported_closed_failure_is_safe_for_next_candidate_but_never_applied(self):
        report, prepare = self.inspect()
        self.assertIs(True, report["safeToProceed"])
        self.assertEqual("UNKNOWN", report["journal"]["deploymentState"])
        self.assertEqual("RECOVERY_REQUIRED", report["journal"]["outcome"])
        self.assertEqual(self.plan["target"], prepare.call_args.args[1])
        self.assertEqual(self.plan["identity"], prepare.call_args.args[-1])
        self.assertEqual([], self.adapter.mutations())

    def test_partial_foreign_or_stale_closure_is_unsafe(self):
        baseline = copy.deepcopy(self.adapter.snapshot)
        for change in [lambda s: s.update(owner=self.fenced), lambda s: s.update(closure=None),
                       lambda s: s.update(intent=None), lambda s: s["closure"].update(deploymentComplete=0),
                       lambda s: s["journal"].update(sequence=5)]:
            self.adapter.snapshot = copy.deepcopy(baseline)
            change(self.adapter.snapshot)
            report, _ = self.inspect()
            self.assertIs(False, report["safeToProceed"])
        self.adapter.snapshot = baseline
        self.adapter.proof["hostProof"]["historyDigest"] = "0" * 64
        self.assertIs(False, self.inspect()[0]["safeToProceed"])


class WrapperDiagnosticTests(unittest.TestCase):
    def invoke(self, root, encoded_manifest=None):
        manifest = {"releaseId": "synthetic-release"}
        command = DRIVER._deploy_command("synthetic", "synthetic", "ap-northeast-2", manifest, {})
        remote = command.split("<<'MEETME_APPROVED_DEPLOY'\n", 1)[1].rsplit("\nMEETME_APPROVED_DEPLOY", 1)[0]
        remote = remote.replace("pathlib.Path('/opt/meet-me/releases')", "pathlib.Path(" + repr(str(root)) + ")")
        argv = ["-", encoded_manifest or DRIVER._encoded(manifest), "synthetic", "synthetic", "ap-northeast-2", "e30=", "e30="]
        out, err = io.StringIO(), io.StringIO()
        with patch.object(sys, "argv", argv), patch("os.umask"), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            with self.assertRaises(SystemExit) as stopped:
                exec(compile(remote, "<synthetic-deploy-wrapper>", "exec"), {})
        self.assertEqual(2, stopped.exception.code)
        self.assertEqual("", out.getvalue())
        marker = "MEETME_DEPLOY_WRAPPER_FAILURE_V1 "
        self.assertTrue(err.getvalue().startswith(marker), "Every wrapper rejection must expose a safe stage marker")
        return json.loads(err.getvalue()[len(marker):])

    def test_existing_release_is_preserved_with_exact_safe_rejection(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            release_path = root / "synthetic-release"
            release_path.mkdir()
            preserved = release_path / "existing"
            preserved.write_text("preserve-me")
            result = self.invoke(root)
            self.assertEqual({"protocol": "meet-me-deploy-wrapper-failure-v1", "stage": "RELEASE_PATH", "reason": "ALREADY_EXISTS"}, result)
            self.assertEqual("preserve-me", preserved.read_text())

    def test_malformed_manifest_is_sanitized_without_exception_or_input(self):
        secret = "synthetic-sensitive-payload"
        with tempfile.TemporaryDirectory() as temp:
            result = self.invoke(Path(temp), base64.b64encode(secret.encode()).decode())
        self.assertEqual("MANIFEST", result["stage"])
        self.assertNotIn(secret, json.dumps(result))


class OperatorClosureCliTests(unittest.TestCase):
    def test_plan_and_explicit_apply_only_emit_allowlisted_summaries(self):
        adapter = FakeClosureAdapter()
        target = adapter.target
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ), \
                patch.object(PIPELINE, "_prepare_closure_release", return_value=contextlib.nullcontext({"identity": adapter.snapshot["journal"]["identity"]})), \
                patch.object(PIPELINE, "AwsReleaseAdapter", return_value=adapter):
            path = Path(temp) / "plan.json"
            args = ["--failed-closure-plan", "--plan-file", str(path), "--account-id", target["accountId"],
                    "--region", target["region"], "--instance-id", target["instanceId"], "--bucket", target["bucket"],
                    "--build-run-id", "154", "--build-run-attempt", "1", "--operation-id", operation_id()]
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(0, PIPELINE.operator_failed_closure(object(), args))
            plan = json.loads(path.read_text())
            self.assertEqual(reviewed_plan(), plan)
            self.assertEqual([], adapter.mutations())
            self.assertNotIn(plan["originalOwner"]["holder"], out.getvalue())
            self.assertNotIn(plan["originalOwnerEtag"], out.getvalue())
            summary = json.loads(out.getvalue().split(" ", 1)[1])
            self.assertEqual({"operationId", "approvalDigest", "deploymentComplete"}, set(summary))
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(0, PIPELINE.operator_failed_closure(object(), ["--failed-closure-apply", "--plan-file", str(path),
                                 "--approve-digest", plan["approvalDigest"]]))
            self.assertEqual("CLOSED", json.loads(out.getvalue().split(" ", 1)[1])["outcome"])
            self.assertNotIn("originalJournal", out.getvalue())

    def test_wrong_approval_is_rejected_before_any_external_preparation(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(PIPELINE, "_prepare_closure_release") as prepare:
            path = Path(temp) / "plan.json"
            path.write_text(json.dumps(reviewed_plan()))
            with self.assertRaises(ValueError):
                PIPELINE.operator_failed_closure(object(), ["--failed-closure-apply", "--plan-file", str(path), "--approve-digest", "0" * 64])
            prepare.assert_not_called()


if __name__ == "__main__":
    unittest.main()
