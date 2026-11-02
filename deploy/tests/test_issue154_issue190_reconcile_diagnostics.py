"""Issue190 independent CLI/immutable-publication/read-only failure contracts.

Actual entry, prepare, policy, origin, archive, journal and completion execute.
Only GitHub/AWS transport is synthetic; no network or subprocess is permitted.
"""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zlib

from test_issue154_public_api_archive_data import GUARD23


DEPLOY = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("issue190_actual_entry", DEPLOY / "pipeline/run-approved-release.py")
ENTRY = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = ENTRY
SPEC.loader.exec_module(ENTRY)
PRIVATE = "SYNTHETIC_PRIVATE_HEADER_BODY_CREDENTIAL_EXCEPTION"
PROTOCOL = "meet-me-reconcile-diagnostic-v1"
MARKER = "MEETME_RECONCILE_DIAGNOSTIC_V1 "
REPOSITORY = "meet-me-duo/meet-me-server"
INSTANCE = "i-1234567890abcdef0"
COMMAND = "00000000-0000-4000-8000-000000000190"
STAGES = {
    "ENTRY_VALIDATION", "MANIFEST_DOWNLOAD", "MANIFEST_VALIDATION", "ORIGIN_VALIDATION",
    "ARCHIVE_DOWNLOAD", "ARCHIVE_INTEGRITY", "PACKAGE_VALIDATION", "PUBLIC_CONTRACT",
    "IDENTITY_VALIDATION", "JOURNAL_READ", "JOURNAL_VALIDATION", "REPORT_PUBLICATION",
}
REASONS = {"INVALID_OR_UNAVAILABLE", "TIMED_OUT", "NOT_FOUND", "ACCESS_DENIED", "INTERRUPTED"}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def identity():
    return {"sourceSha": "a" * 40, "buildRunId": "501", "buildRunAttempt": "1",
            "workflowRunId": "501", "workflowRunAttempt": "1", "releaseId": "a" * 40 + "-501-1",
            "imageDigest": "sha256:" + "b" * 64}


def diagnostic(op_id, stage="JOURNAL_READ", reason="NOT_FOUND"):
    return {"protocol": PROTOCOL, "operationId": op_id, "diagnosticComplete": False,
            "stage": stage, "reason": reason}


class PublishedReadOnlyFixture:
    def __init__(self, directory):
        self.directory = directory
        self.events = []
        self.failure = None
        self.origin_failure = None
        self.objects = {}
        self.contents = {name: (DEPLOY / name).read_bytes() for name in GUARD23}
        self.contents.update({
            "public-api-contract.json": (DEPLOY / "public-api-contract.json").read_bytes(),
            "scripts/export-certificate.sh": (DEPLOY / "scripts/export-certificate.sh").read_bytes(),
            "runtime-provider-mode": b"gemini-luna-required\n",
            "preflight/compiled/ReadOnlyFlywayProbe.class": bytes.fromhex("cafebabe"),
        })
        rows = []
        catalog = []
        migrations = DEPLOY.parent / "src/main/resources/db/migration"
        paths = sorted(migrations.glob("V*__*.sql"), key=lambda p: int(p.name.split("__")[0][1:]))
        for path in paths:
            raw = path.read_bytes()
            self.contents["migrations/" + path.name] = raw
            crc = zlib.crc32("".join(re.split(r"\r\n|\r|\n", raw.decode("utf-8-sig"))).encode())
            version = path.name.split("__")[0][1:]
            rows.append({"version": version, "script": path.name, "checksum": crc if crc < 2**31 else crc - 2**32})
            catalog.append({"version": version, "script": path.name, "sha256": digest(raw)})
        self.manifest = {
            "protocol": "meet-me-approved-release-v1", "sourceSha": "a" * 40,
            "buildRunId": "501", "buildRunAttempt": "1",
            "imageRepository": "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/synthetic/server",
            "imageDigest": "sha256:" + "b" * 64, "releaseId": "a" * 40 + "-501-1",
            "archiveSha256": "c" * 64, "schemaFingerprint": digest(canonical(catalog)),
            "guardFingerprint": digest(canonical([{"path": name, "sha256": digest(self.contents[name])} for name in GUARD23])),
            "migrations": rows,
            "origin": {"repository": REPOSITORY, "ref": "refs/heads/main", "workflow": "CI", "event": "push",
                       "conclusion": "success", "ciRunId": "500", "ciSha": "a" * 40},
        }
        self.repack()
        self.op_id = digest(canonical(identity()))
        self.journal_key = "releases/operations/" + digest(INSTANCE.encode()) + "/" + self.op_id + ".json"
        self.journal = {"protocol": "meet-me-automatic-operation-v1", "identity": identity(),
                        "operationId": self.op_id, "sequence": 3, "deploymentState": "UNKNOWN", "commandId": COMMAND,
                        "originalRules": {"meet-me-production-db-credential-reconcile": "ENABLED",
                                          "meet-me-production-db-credential-rotated": "ENABLED"},
                        "outcome": "RECOVERY_REQUIRED", "rulesState": "UNTOUCHED"}
        self.objects[self.journal_key] = canonical(self.journal)
        event = {"repository": {"full_name": REPOSITORY},
                 "inputs": {"action": "reconcile", "operation_id": self.op_id}}
        self.event_path = directory / "event.json"
        self.event_path.write_bytes(canonical(event))
        self.env = {
            "GITHUB_EVENT_PATH": str(self.event_path), "GITHUB_REPOSITORY": REPOSITORY,
            "GITHUB_REF": "refs/heads/main", "GITHUB_EVENT_NAME": "workflow_dispatch", "SOURCE_SHA": "a" * 40,
            "RELEASE_ACTION": "reconcile", "OPERATION_ID": self.op_id, "APPROVE_FIRST_TRANSITION": "false",
            "RUNTIME_INSTANCE_ID": INSTANCE, "AWS_REGION": "ap-northeast-2",
            "DEPLOYMENT_ARTIFACT_BUCKET": "synthetic-artifacts", "BUILD_RUN_ID": "501", "BUILD_RUN_ATTEMPT": "1",
            "ECR_REPOSITORY_URL": self.manifest["imageRepository"], "GITHUB_RUN_ID": "600", "GITHUB_RUN_ATTEMPT": "1",
            "GITHUB_STEP_SUMMARY": str(directory / "summary.md"), "GITHUB_OUTPUT": str(directory / "outputs.txt"),
        }

    def repack(self):
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode="w:gz") as archive:
            for name, raw in self.contents.items():
                member = tarfile.TarInfo(name)
                member.size = len(raw)
                member.mode = 0o755 if name in {"scripts/render-runtime-env.sh", "scripts/export-certificate.sh"} else 0o644
                archive.addfile(member, io.BytesIO(raw))
        self.archive = output.getvalue()
        self.manifest["archiveSha256"] = digest(self.archive)

    def github(self, run_id, attempt=None):
        self.events.append(("github-read", run_id, attempt))
        if self.origin_failure is not None:
            raise self.origin_failure
        common = {"repository": {"full_name": REPOSITORY}, "head_repository": {"full_name": REPOSITORY},
                  "head_sha": "a" * 40, "head_branch": "main", "conclusion": "success"}
        if run_id == "500" and attempt is None:
            return common | {"id": 500, "path": ".github/workflows/ci.yml", "name": "CI", "event": "push"}
        return common | {"id": 501, "run_attempt": 1, "path": ".github/workflows/deploy.yml",
                         "name": "Deploy Production", "event": "workflow_run", "status": "completed"}

    def transport(self, service, operation, *args):
        self.events.append((service, operation))
        if self.failure is not None:
            failure = self.failure(service, operation, args)
            if failure is not None:
                raise failure
        if service == "s3api":
            option = "--prefix" if operation == "list-objects-v2" else "--key"
            key = args[args.index(option) + 1]
            raw = (canonical(self.manifest) if key.endswith("/manifest.json") else self.archive
                   if key.endswith("/release.tar.gz") else self.objects.get(key))
            if operation == "list-objects-v2":
                return {"status": "success", "data": {"IsTruncated": False,
                        "Contents": [] if raw is None else [{"Key": key}]}}
            if operation == "head-object":
                return {"status": "success", "data": {"ContentLength": len(raw)}}
            if operation == "get-object":
                if raw is None:
                    raise FileNotFoundError(PRIVATE)
                Path(args[-1]).write_bytes(raw)
                return {"status": "success", "data": {"ContentLength": len(raw), "ETag": '"synthetic"'}}
        if service == "ssm" and operation == "get-command-invocation":
            return {"status": "success", "data": {"Status": "Failed", "StandardOutputContent": PRIVATE, "StandardErrorContent": PRIVATE}}
        if service == "ssm" and operation == "list-commands":
            return {"status": "success", "data": {"Commands": []}}
        if service == "events":
            name = args[args.index("--name" if operation == "describe-rule" else "--rule") + 1]
            if operation == "describe-rule":
                return {"status": "success", "data": {"Name": name, "State": "ENABLED"}}
            if operation == "list-targets-by-rule":
                target = "ReconcileDatabaseCredential" if name.endswith("reconcile") else "RefreshDatabaseCredential"
                return {"status": "success", "data": {"Targets": [{"Id": target,
                    "Arn": "arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript",
                    "RoleArn": "arn:aws:iam::123456789012:role/meet-me-production-db-credential-refresh",
                    "RunCommandParameters": {"RunCommandTargets": [{"Key": "InstanceIds", "Values": [INSTANCE]}]}}]}}
        raise AssertionError("A write or undeclared service operation was attempted")


class Issue190ReconcileDiagnosticTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="issue190-readonly-")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.fixture = PublishedReadOnlyFixture(self.directory)

    def invoke(self, phase="--pipeline-reconcile", publication_failure=False, diagnostic_publication_failure=False):
        output = io.StringIO()
        previous = Path.cwd()
        original_load, original_write, original_replace = ENTRY._load, Path.write_bytes, os.replace
        observed_files = ("summary.md", "reconcile-diagnostic.json", "recovery-report.json", "deployment-result.json")
        before = {name: (self.directory / name).read_bytes() for name in observed_files if (self.directory / name).is_file()}

        def load(name, path):
            module = original_load(name, path)
            if name == "pipeline_active_commands":
                module.aws_call = self.fixture.transport
            if name == "release_pipeline_controller":
                for method in ("claim_operation", "owns_operation", "load_operation", "save_operation", "release_operation", "write_rule", "submit_deploy"):
                    def forbidden(*_args, _method=method, **_kwargs):
                        self.fixture.events.append(("FORBIDDEN", _method))
                        raise AssertionError("Read-only entry attempted mutation authority")
                    setattr(module.AwsReleaseAdapter, method, forbidden)
            return module

        def write(path, raw):
            if (publication_failure and path.name == "recovery-report.json") or (diagnostic_publication_failure and path.name == "reconcile-diagnostic.json"):
                raise PermissionError(PRIVATE)
            return original_write(path, raw)

        def replace(source, destination, *args, **kwargs):
            # Fail the actual final publication of an atomic writer as well as
            # direct writes; temporary creation/fsync remains legitimate.
            if diagnostic_publication_failure and Path(destination).name == "reconcile-diagnostic.json":
                raise PermissionError(PRIVATE)
            return original_replace(source, destination, *args, **kwargs)

        try:
            os.chdir(self.directory)
            with patch.dict(os.environ, self.fixture.env, clear=True), patch.object(sys, "argv", [str(ENTRY.__file__), phase]), \
                 patch.object(ENTRY, "aws_call", side_effect=self.fixture.transport), \
                 patch.object(ENTRY, "_github_run", side_effect=self.fixture.github), \
                 patch.object(ENTRY, "_load", side_effect=load), patch.object(Path, "write_bytes", write), \
                 patch("os.replace", side_effect=replace), \
                 patch.object(ENTRY, "_collect_facts", side_effect=AssertionError("Read-only reconcile must not send a host probe")) as probe, \
                 patch("subprocess.run", side_effect=AssertionError("No subprocess is permitted")) as process, \
                 patch("urllib.request.build_opener", side_effect=AssertionError("No network is permitted")) as network, \
                 contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                try:
                    ENTRY.main()
                    failure = None
                except BaseException as error:
                    failure = error
                self.assertEqual(0, probe.call_count)
                self.assertEqual(0, process.call_count)
                self.assertEqual(0, network.call_count)
        finally:
            os.chdir(previous)
        self.assertFalse([event for event in self.fixture.events if event[0] == "FORBIDDEN"])
        self.assertFalse([event for event in self.fixture.events if event[:2] in {
            ("ssm", "send-command"), ("s3api", "put-object"), ("s3api", "delete-object"),
            ("events", "enable-rule"), ("events", "disable-rule")}])
        visible = output.getvalue()
        for name in observed_files:
            path = self.directory / name
            if path.is_file() and path.read_bytes() != before.get(name):
                visible += path.read_text()
        if failure is not None:
            visible += " ".join(str(value) for value in self.cause_chain(failure))
        self.assertNotIn(PRIVATE, visible, "Raw service/callback data must not escape diagnostics")
        self.assertNotIn("Traceback (most recent call last)", visible)
        return failure, output.getvalue()

    @staticmethod
    def cause_chain(error):
        seen = set()
        while error is not None and id(error) not in seen:
            seen.add(id(error))
            yield error
            error = error.__cause__ if error.__cause__ is not None else (None if error.__suppress_context__ else error.__context__)

    def assert_diagnostic(self, stage, reason="INVALID_OR_UNAVAILABLE", op_id="BOUND"):
        path = self.directory / "reconcile-diagnostic.json"
        self.assertTrue(path.is_file(), "Failure must leave the dedicated diagnostic artifact")
        document = json.loads(path.read_bytes())
        expected = diagnostic(self.fixture.op_id if op_id == "BOUND" else op_id, stage, reason)
        self.assertEqual(expected, document)
        self.assertEqual(STAGES.intersection({document["stage"]}), {document["stage"]})
        self.assertIn(document["reason"], REASONS)
        return document

    def test_real_full_entry_prepare_and_failed_ssm_observation_preserve_v1_report(self):
        failure, output = self.invoke()
        self.assertIsNone(failure, "Valid immutable fixture must reach actual read-only reconciliation")
        report = json.loads((self.directory / "recovery-report.json").read_bytes())
        self.assertEqual({"protocol", "identity", "operationId", "commandStatus", "hostVerified", "rulesObserved",
                          "pendingCommands", "deploymentComplete", "outcome"}, set(report))
        self.assertEqual("Failed", report["commandStatus"])
        self.assertIs(False, report["deploymentComplete"])
        self.assertEqual("RECOVERY_REQUIRED", report["outcome"])
        self.assertFalse((self.directory / "reconcile-diagnostic.json").exists())
        self.assertNotIn(MARKER, output)
        self.assertIn(("ssm", "get-command-invocation"), self.fixture.events)
        failure, _ = self.invoke("--pipeline-complete")
        self.assertIsNone(failure)

    def test_invalid_operation_input_has_entry_diagnostic_with_null_identity_and_no_service_read(self):
        self.fixture.env["OPERATION_ID"] = PRIVATE
        failure, output = self.invoke()
        self.assertIsNotNone(failure)
        self.assert_diagnostic("ENTRY_VALIDATION", op_id=None)
        self.assertIn(MARKER, output)
        self.assertFalse(self.fixture.events)

    def test_invalid_entry_context_fails_before_any_download(self):
        self.fixture.env["GITHUB_REF"] = "refs/heads/develop"
        failure, _ = self.invoke()
        self.assertIsNotNone(failure)
        self.assert_diagnostic("ENTRY_VALIDATION")
        self.assertFalse(self.fixture.events)

    def test_real_prepare_failures_have_distinct_stage_and_preserve_read_only_boundary(self):
        cases = ("MANIFEST_DOWNLOAD", "MANIFEST_VALIDATION", "ORIGIN_VALIDATION", "ARCHIVE_DOWNLOAD",
                 "ARCHIVE_INTEGRITY", "PACKAGE_VALIDATION", "PUBLIC_CONTRACT", "IDENTITY_VALIDATION",
                 "JOURNAL_READ", "JOURNAL_VALIDATION", "REPORT_PUBLICATION")
        for stage in cases:
            with self.subTest(stage=stage):
                self.fixture = PublishedReadOnlyFixture(self.directory)
                (self.directory / "reconcile-diagnostic.json").unlink(missing_ok=True)
                def fail(service, operation, args):
                    key = args[args.index("--key") + 1] if "--key" in args else ""
                    if stage == "MANIFEST_DOWNLOAD" and key.endswith("manifest.json"):
                        return TimeoutError(PRIVATE)
                    if stage == "ARCHIVE_DOWNLOAD" and key.endswith("release.tar.gz"):
                        return TimeoutError(PRIVATE)
                    if stage == "JOURNAL_READ" and key == self.fixture.journal_key:
                        return FileNotFoundError(PRIVATE)
                self.fixture.failure = fail
                if stage == "MANIFEST_VALIDATION":
                    self.fixture.env["BUILD_RUN_ATTEMPT"] = "2"
                elif stage == "ORIGIN_VALIDATION":
                    self.fixture.origin_failure = PermissionError(PRIVATE)
                elif stage == "ARCHIVE_INTEGRITY":
                    self.fixture.archive += b"altered-after-immutable-hash"
                elif stage == "PACKAGE_VALIDATION":
                    self.fixture.contents.pop("scripts/export-certificate.sh")
                    self.fixture.repack()
                elif stage == "PUBLIC_CONTRACT":
                    self.fixture.contents["public-api-contract.json"] = b'{"protocol":"untrusted"}'
                    self.fixture.repack()
                elif stage == "IDENTITY_VALIDATION":
                    self.fixture.env["GITHUB_RUN_ID"] = "not-a-run-id"
                elif stage == "JOURNAL_VALIDATION":
                    self.fixture.objects[self.fixture.journal_key] = canonical(self.fixture.journal | {"extra": PRIVATE})
                failure, output = self.invoke(publication_failure=stage == "REPORT_PUBLICATION")
                self.assertIsNotNone(failure)
                reason = ("TIMED_OUT" if stage in {"MANIFEST_DOWNLOAD", "ARCHIVE_DOWNLOAD"} else
                          "NOT_FOUND" if stage == "JOURNAL_READ" else
                          "ACCESS_DENIED" if stage in {"ORIGIN_VALIDATION", "REPORT_PUBLICATION"} else "INVALID_OR_UNAVAILABLE")
                self.assert_diagnostic(stage, reason)
                self.assertIn(MARKER, output)
                if stage not in {"REPORT_PUBLICATION"}:
                    self.assertNotIn(("ssm", "get-command-invocation"), self.fixture.events)

    def test_reason_is_based_on_typed_failure_and_never_exception_text_or_class(self):
        class ArbitrarySensitiveFailure(Exception):
            pass
        errors = ((FileNotFoundError(PRIVATE), "NOT_FOUND"), (PermissionError(PRIVATE), "ACCESS_DENIED"),
                  (TimeoutError(PRIVATE), "TIMED_OUT"), (subprocess.TimeoutExpired(PRIVATE, 1, output=PRIVATE), "TIMED_OUT"),
                  (KeyboardInterrupt(PRIVATE), "INTERRUPTED"), (ArbitrarySensitiveFailure("AccessDenied " + PRIVATE), "INVALID_OR_UNAVAILABLE"))
        for error, reason in errors:
            with self.subTest(reason=reason, kind=type(error).__name__):
                self.fixture = PublishedReadOnlyFixture(self.directory)
                self.fixture.failure = lambda *_: error
                failure, output = self.invoke()
                self.assertIsNotNone(failure)
                self.assert_diagnostic("MANIFEST_DOWNLOAD", reason)
                self.assertNotIn("ArbitrarySensitiveFailure", output)

    def test_completion_consumes_valid_diagnostic_before_missing_or_poisoned_plan(self):
        for poisoned in (False, True):
            with self.subTest(poisoned=poisoned):
                document = diagnostic(self.fixture.op_id, "ARCHIVE_INTEGRITY", "INVALID_OR_UNAVAILABLE")
                (self.directory / "reconcile-diagnostic.json").write_bytes(canonical(document))
                plan = self.directory / "release-plan.json"
                if poisoned:
                    plan.write_text(PRIVATE)
                else:
                    plan.unlink(missing_ok=True)
                failure, output = self.invoke("--pipeline-complete")
                self.assertIsNotNone(failure)
                entries = [json.loads(line[len(MARKER):]) for line in output.splitlines() if line.startswith(MARKER)]
                self.assertEqual([document], entries)
                self.assertFalse((self.directory / "deployment-result.json").exists())
                self.assertFalse(self.fixture.events)

    def test_forged_completion_diagnostic_is_rejected_without_echo_or_success(self):
        good = diagnostic(self.fixture.op_id)
        bad = (good | {"stage": PRIVATE}, good | {"reason": PRIVATE}, good | {"operationId": "f" * 64},
               good | {"extra": PRIVATE}, good | {"diagnosticComplete": True}, good | {"diagnosticComplete": 0}, good | {"protocol": "untrusted"},
               good | {"operationId": None})
        for document in bad:
            with self.subTest(fields=sorted(document)):
                (self.directory / "reconcile-diagnostic.json").write_bytes(canonical(document))
                failure, output = self.invoke("--pipeline-complete")
                self.assertIsNotNone(failure)
                self.assertNotIn(MARKER, output)
                self.assertNotIn('"deploymentComplete":true', output)
                self.assertFalse(self.fixture.events)

    def test_completion_binds_diagnostic_to_both_event_and_environment(self):
        for changed in ("event", "environment"):
            with self.subTest(changed=changed):
                self.fixture = PublishedReadOnlyFixture(self.directory)
                (self.directory / "reconcile-diagnostic.json").write_bytes(canonical(diagnostic(self.fixture.op_id)))
                if changed == "event":
                    event = json.loads(self.fixture.event_path.read_bytes())
                    event["inputs"]["operation_id"] = "f" * 64
                    self.fixture.event_path.write_bytes(canonical(event))
                else:
                    self.fixture.env["OPERATION_ID"] = "f" * 64
                failure, output = self.invoke("--pipeline-complete")
                self.assertIsNotNone(failure)
                self.assertNotIn(MARKER, output)
                self.assertFalse(self.fixture.events)

    def test_unwritable_diagnostic_emits_only_sanitized_publication_failure_and_remains_red(self):
        self.fixture.failure = lambda *_: FileNotFoundError(PRIVATE)
        failure, output = self.invoke(diagnostic_publication_failure=True)
        self.assertIsNotNone(failure)
        entries = [json.loads(line[len(MARKER):]) for line in output.splitlines() if line.startswith(MARKER)]
        self.assertEqual([diagnostic(self.fixture.op_id, "REPORT_PUBLICATION", "ACCESS_DENIED")], entries)
        self.assertFalse((self.directory / "reconcile-diagnostic.json").exists())

    def test_workflow_preserves_diagnostic_and_provides_completion_binding(self):
        workflow = (DEPLOY.parent / ".github/workflows/deploy.yml").read_text()
        approved = workflow.split("  approved-release:", 1)[1].split("  automatic-release:", 1)[0]
        completion = workflow.split("  deployment-completion:", 1)[1]
        self.assertIn("reconcile-diagnostic.json", approved)
        self.assertRegex(completion, r"(?m)^      RELEASE_ACTION:")
        self.assertRegex(completion, r"(?m)^      OPERATION_ID:")
        self.assertNotIn("id-token: write", completion)


if __name__ == "__main__":
    unittest.main()
