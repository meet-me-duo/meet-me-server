"""Independent issue154 readonly inspection and prior-operation safety fence.

Spec: the approved recovery diagnostic may observe bounded journal/owner metadata
but cannot acquire ownership, mutate rules/journal, send a command or promote an
uncertain operation. Existing reconciliation-v1 remains an independent contract.
All AWS ports below are synthetic; no network, credentials or host process runs.
"""
import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from test_issue154_adapter import PIPELINE, FakeAwsDriver, release, identity, RULES, COMMAND
from test_issue154_public_contract import contract
from test_issue154_reconcile import ReadOnlyAdapter, current_identity, original_identity, journal as legacy_journal


SECRET = "synthetic-private-owner-or-error-must-never-be-reported"
HOLDER = "9" * 32
BUCKET = "synthetic-deployment-artifacts"
INSTANCE = "i-1234567890abcdef0"
ROOT_KEY = "releases/operations/" + hashlib.sha256(INSTANCE.encode("ascii")).hexdigest() + "/"
REASONS = {"FRESH_NAMESPACE", "SAFE_TERMINAL", "OWNER_ACTIVE", "OWNER_MISSING", "OWNER_UNAVAILABLE",
           "JOURNAL_MISSING", "JOURNAL_UNAVAILABLE", "INVALID_RECORD", "IDENTITY_MISMATCH",
           "SNAPSHOT_CHANGED", "UNFINISHED_OPERATION", "RULES_UNVERIFIED", "HOST_UNVERIFIED", "NONEMPTY_NAMESPACE"}
TOP_KEYS = {"protocol", "operationId", "journal", "owner", "safeToProceed", "reason"}
JOURNAL_KEYS = {"status", "protocol", "sequence", "phase", "deploymentState", "rulesState", "outcome", "reason", "commandIdPresent"}
OWNER_KEYS = {"status", "released", "identityMatches"}
MUTATIONS = {"put-object", "delete-object", "enable-rule", "disable-rule", "send-command", "cancel-command"}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def op_id(bound):
    return hashlib.sha256(canonical(bound)).hexdigest()


def transition(bound=None, **changed):
    bound = identity() if bound is None else copy.deepcopy(bound)
    return {"protocol": "meet-me-transition-operation-v1", "operationId": op_id(bound), "identity": bound,
            "action": "deploy", "sequence": 7, "phase": "FAILED", "originalRules": {name: "ENABLED" for name in RULES},
            "changedRules": list(RULES), "deploymentState": "NOT_ATTEMPTED", "rulesState": "RESTORED",
            "outcome": "FAILED", "commandId": None, "reason": "PRECONDITION_FAILED", **changed}


def automatic(bound=None, **changed):
    value = transition(bound)
    for key in ("action", "phase", "changedRules", "reason"):
        del value[key]
    value.update(protocol="meet-me-automatic-operation-v1", outcome="NOT_DEPLOYED", rulesState="UNTOUCHED")
    value.update(changed)
    return value


def owner(bound=None, released=True, **changed):
    bound = identity() if bound is None else copy.deepcopy(bound)
    return {"protocol": "meet-me-release-owner-v1", "operationId": op_id(bound), "identity": bound,
            "holder": HOLDER, "released": released, **changed}


class InspectionDriver(FakeAwsDriver):
    """Use the real adapter's _aws seam, trapping every external mutation."""
    def __init__(self):
        super().__init__()
        self.boundary = []
        self.fault = None
        self.owner_reads = 0
        self.after_owner_read = None
        self.raw_reads = []
        self.probes = []
        self.metadata_override = None

    def _aws(self, service, operation, *args):
        self.boundary.append((service, operation, args))
        if operation in MUTATIONS:
            raise ValueError(SECRET)
        if service == "s3api":
            if operation not in ("list-objects-v2", "get-object"):
                raise ValueError(SECRET)
            if self.argument(args, "--bucket") != BUCKET:
                raise ValueError(SECRET)
            key = self.argument(args, "--prefix" if operation == "list-objects-v2" else "--key")
            if not key.startswith(ROOT_KEY):
                raise ValueError(SECRET)
            self.raw_reads.append((operation, key))
            if self.fault is not None:
                replacement = self.fault(operation, key)
                if replacement is not None:
                    return replacement
            if operation == "list-objects-v2":
                limit = int(self.argument(args, "--max-keys"))
                if not 1 <= limit <= 2:
                    raise ValueError(SECRET)
                keys = sorted(k for k in self.objects if k.startswith(key))
                return {"IsTruncated": len(keys) > limit, "Contents": [{"Key": k} for k in keys[:limit]],
                        "KeyCount": min(len(keys), limit)}
            result = super()._aws(service, operation, *args)
            if self.metadata_override is not None:
                result.update(self.metadata_override)
            if key == ROOT_KEY + "owner.json":
                self.owner_reads += 1
                if self.after_owner_read is not None:
                    self.after_owner_read(self.owner_reads)
            return result
        return super()._aws(service, operation, *args)

    def _collect_facts(self, *args, **kwargs):
        self.probes.append("fresh-host-probe")
        raise ValueError(SECRET)

    def aws_call(self, *args):
        self.probes.append("command-query")
        raise ValueError(SECRET)


class RecoveryInspectionTests(unittest.TestCase):
    def setUp(self):
        self.directory = self.enterContext(tempfile.TemporaryDirectory(prefix="issue154-recovery-inspection-"))
        self.driver = InspectionDriver()
        self.release = release(self.driver, self.directory)
        self.release.update(action="automatic", approve=False, mode="AUTO_READY", parameterReady=True,
                            publicContract=contract())
        self.adapter = PIPELINE.AwsReleaseAdapter(self.release, "preflight")
        self.emitted = []
        self.public_calls = []

    @contextlib.contextmanager
    def cwd(self):
        previous = Path.cwd()
        os.chdir(self.directory)
        try:
            yield
        finally:
            os.chdir(previous)

    def put(self, key, value):
        raw = value if isinstance(value, bytes) else canonical(value)
        self.driver.objects[key] = (raw, self.driver.etag(raw))

    def install(self, record=None, ownership=None):
        record = transition() if record is None else copy.deepcopy(record)
        ownership = owner(record["identity"]) if ownership is None else copy.deepcopy(ownership)
        self.put(ROOT_KEY + record["operationId"] + ".json", record)
        self.put(ROOT_KEY + "owner.json", ownership)
        return record

    def api(self, name):
        function = getattr(PIPELINE, name, None)
        self.assertTrue(callable(function), "missing approved readonly recovery API: " + name)
        return function

    def assert_read_only(self):
        self.assertFalse([call for call in self.driver.boundary if call[1] in MUTATIONS])
        self.assertEqual([], self.driver.probes, "inspection cannot submit a new SSM host probe or poll a command")
        self.assertEqual([], self.public_calls, "inspection cannot enter claim/release/save/deploy ports")

    def inspect(self, operation=None):
        function = self.api("inspect_recovery_state")
        with self.public_mutation_traps(), contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
            report = function(op_id(identity()) if operation is None else operation, self.adapter)
        self.assertNotIn(SECRET, out.getvalue() + err.getvalue())
        self.assert_read_only()
        self.assertFalse([c for c in self.driver.boundary if c[0] == "ssm"])
        self.assert_report(report)
        return report

    def guard(self, safe):
        function = self.api("require_recovery_safe")
        with self.public_mutation_traps(), patch.object(PIPELINE, "_emit_inspection", create=True,
                          side_effect=lambda r: self.emitted.append(copy.deepcopy(r))), \
                contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
            if safe:
                report = function(self.adapter)
            else:
                with self.assertRaises(ValueError) as caught:
                    function(self.adapter)
                self.assertNotIn(SECRET, str(caught.exception))
                self.assertTrue(self.emitted, "unsafe guard must emit a curated inspection before stopping")
                report = self.emitted[-1]
        self.assertNotIn(SECRET, out.getvalue() + err.getvalue())
        self.assert_read_only()
        self.assertFalse([c for c in self.driver.boundary if c[0] == "ssm"])
        self.assert_report(report)
        self.assertIs(safe, report["safeToProceed"])
        return report

    def assert_report(self, report):
        self.assertEqual(TOP_KEYS, set(report))
        self.assertEqual("meet-me-recovery-inspection-v1", report["protocol"])
        self.assertRegex(report["operationId"], r"^[0-9a-f]{64}$")
        self.assertEqual(JOURNAL_KEYS, set(report["journal"]))
        self.assertEqual(OWNER_KEYS, set(report["owner"]))
        self.assertIn(report["reason"], REASONS)
        self.assertIs(type(report["safeToProceed"]), bool)
        self.assertIn(report["journal"]["status"], ("VALID", "MISSING", "UNAVAILABLE"))
        self.assertIn(report["owner"]["status"], ("PRESENT", "MISSING", "UNAVAILABLE"))
        for value in (report["owner"]["released"], report["owner"]["identityMatches"], report["journal"]["commandIdPresent"]):
            self.assertTrue(value is None or type(value) is bool)
        raw = canonical(report)
        self.assertLessEqual(len(raw), 8192)
        for value in (SECRET, HOLDER, COMMAND, '"holder"', '"identity"', '"commandId"', '"ETag"'):
            self.assertNotIn(value, raw.decode())

    def test_unknown_without_command_exposes_only_whitelisted_metadata_and_stops(self):
        record = self.install(transition(phase="DEPLOY_INTENT", deploymentState="UNKNOWN",
                                         outcome="RECOVERY_REQUIRED", reason="SUBMISSION_UNCERTAIN"), owner(released=False))
        report = self.inspect()
        self.assertEqual({"status": "VALID", "protocol": record["protocol"], "sequence": 7,
                          "phase": "DEPLOY_INTENT", "deploymentState": "UNKNOWN", "rulesState": "RESTORED",
                          "outcome": "RECOVERY_REQUIRED", "reason": "SUBMISSION_UNCERTAIN", "commandIdPresent": False}, report["journal"])
        self.assertEqual({"status": "PRESENT", "released": False, "identityMatches": True}, report["owner"])
        self.assertIs(False, report["safeToProceed"])

    def test_fresh_namespace_is_the_only_missing_owner_case_allowed(self):
        report = self.guard(True)
        self.assertEqual("FRESH_NAMESPACE", report["reason"])
        self.assertEqual(op_id(identity()), report["operationId"])
        self.assertEqual("MISSING", report["owner"]["status"])
        listings = [args for service, operation, args in self.driver.boundary
                    if service == "s3api" and operation == "list-objects-v2"
                    and self.driver.argument(args, "--prefix") == ROOT_KEY]
        self.assertTrue(listings)
        self.assertTrue(all(self.driver.argument(args, "--max-keys") == "1" for args in listings))

    def test_orphan_journal_send_claim_or_unrecognized_namespace_object_blocks(self):
        for suffix in (op_id(identity()) + ".json", op_id(identity()) + ".send.json", "unexpected.json"):
            self.driver.objects.clear()
            self.put(ROOT_KEY + suffix, transition())
            with self.subTest(suffix=suffix):
                self.guard(False)

    def test_unreleased_owner_blocks_even_terminal_not_attempted_or_applied(self):
        for record in (transition(), automatic(), transition(phase="COMPLETE", deploymentState="APPLIED",
                        commandId=COMMAND, outcome="APPLIED", reason="APPLIED")):
            self.driver.objects.clear()
            self.install(record, owner(released=False))
            with self.subTest(protocol=record["protocol"], state=record["deploymentState"]):
                report = self.guard(False)
                self.assertEqual("OWNER_ACTIVE", report["reason"])
                self.assertEqual("VALID", report["journal"]["status"])

    def test_released_terminal_failed_transition_and_untouched_automatic_are_safe(self):
        for record in (transition(), automatic()):
            self.driver.objects.clear()
            self.install(record)
            with self.subTest(protocol=record["protocol"]):
                report = self.guard(True)
                self.assertEqual("SAFE_TERMINAL", report["reason"])
                self.assertIs(False, report["journal"]["commandIdPresent"])
                if record["protocol"] == "meet-me-automatic-operation-v1":
                    self.assertIsNone(report["journal"]["phase"])
                    self.assertIsNone(report["journal"]["reason"])

    def test_unknown_intent_submitted_or_nonterminal_journal_never_allows_resume(self):
        records = [transition(phase=phase, deploymentState="UNKNOWN", outcome="RECOVERY_REQUIRED",
                              reason="SUBMISSION_UNCERTAIN", commandId=COMMAND if phase == "SUBMITTED" else None)
                   for phase in ("DEPLOY_INTENT", "SUBMITTED", "RECOVERY")]
        records += [transition(phase="SNAPSHOT"), transition(phase="PAUSE_INTENT"), automatic(deploymentState="UNKNOWN", outcome="RECOVERY_REQUIRED")]
        for record in records:
            self.driver.objects.clear()
            self.install(record)
            with self.subTest(record=record):
                self.guard(False)

    def test_missing_journal_or_read_denial_is_not_safe_or_reported_as_empty(self):
        self.put(ROOT_KEY + "owner.json", owner())
        report = self.guard(False)
        self.assertEqual("MISSING", report["journal"]["status"])
        self.install()
        for operation in ("get-object", "list-objects-v2"):
            def denied(op, key):
                if op == operation:
                    raise OSError(SECRET)
            self.driver.fault = denied
            with self.subTest(operation=operation):
                self.guard(False)
        self.driver.fault = None

    def test_namespace_denial_truncation_or_malformed_listing_is_not_fresh(self):
        for value in ({"IsTruncated": True, "Contents": []}, {"Contents": []},
                      {"IsTruncated": False, "Contents": SECRET},
                      {"IsTruncated": False, "Contents": [{"Key": ROOT_KEY + SECRET}]}):
            self.driver.fault = lambda operation, key, value=value: value if operation == "list-objects-v2" else None
            with self.subTest(value=value):
                self.guard(False)

    def test_invalid_requested_operation_is_rejected_without_reflection_or_aws_reads(self):
        function = self.api("inspect_recovery_state")
        for value in (None, False, 3, "", "f" * 63, "F" * 64, "../" + SECRET, "a" * 64 + "\n"):
            self.driver.boundary.clear()
            with self.subTest(value=value), contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
                with self.assertRaises(ValueError) as raised:
                    function(value, self.adapter)
            self.assertEqual([], self.driver.boundary)
            self.assertNotIn(SECRET, str(raised.exception) + out.getvalue() + err.getvalue())

    def test_explicit_inspection_binds_five_candidate_fields_but_not_new_workflow_run(self):
        self.install()
        self.adapter.identity = identity() | {"workflowRunId": "999", "workflowRunAttempt": "2"}
        self.assertEqual("VALID", self.inspect()["journal"]["status"])
        candidates = (identity() | {"sourceSha": "f" * 40, "releaseId": "f" * 40 + "-154-1"},
                      identity() | {"buildRunId": "999", "releaseId": "a" * 40 + "-999-1"},
                      identity() | {"buildRunAttempt": "2", "releaseId": "a" * 40 + "-154-2"},
                      identity() | {"imageDigest": "sha256:" + "f" * 64})
        for candidate in candidates:
            self.adapter.identity = candidate
            with self.subTest(candidate=candidate):
                report = self.inspect()
                self.assertIs(False, report["safeToProceed"])

    def test_namespace_guard_uses_prior_original_identity_not_new_candidate_identity(self):
        old = identity() | {"sourceSha": "c" * 40, "releaseId": "c" * 40 + "-154-1", "workflowRunId": "100"}
        self.install(transition(old), owner(old))
        report = self.guard(True)
        self.assertEqual(op_id(old), report["operationId"])
        self.assertIs(True, report["owner"]["identityMatches"])

    def test_owner_and_journal_canonical_identity_protocol_and_schema_are_required(self):
        changes = (("identity", identity() | {"workflowRunId": "999"}), ("operationId", "f" * 64),
                   ("protocol", "untrusted-" + SECRET), ("released", "true"), ("holder", SECRET), ("extra", SECRET))
        for key, value in changes:
            self.driver.objects.clear()
            self.install(ownership=owner(**{key: value}))
            with self.subTest(ownerField=key):
                self.guard(False)
        for key, value in (("identity", identity() | {"workflowRunId": "999"}), ("operationId", "f" * 64),
                           ("protocol", SECRET), ("sequence", True), ("sequence", 0), ("phase", SECRET),
                           ("reason", SECRET), ("extra", SECRET), ("commandId", SECRET)):
            self.driver.objects.clear()
            record = transition(**{key: value})
            self.put(ROOT_KEY + op_id(identity()) + ".json", record)
            self.put(ROOT_KEY + "owner.json", owner())
            with self.subTest(journalField=key):
                self.guard(False)

    def test_duplicate_malformed_oversized_or_non_utf8_json_is_never_diagnostic_data(self):
        payloads = (b'{"protocol":"' + SECRET.encode() + b'",', b'{"released":true,"released":false}',
                    b"null", b"[]", b"\xff", b'{"extra":"' + b"x" * 131073 + b'"}')
        for suffix in ("owner.json", op_id(identity()) + ".json"):
            for raw in payloads:
                self.driver.objects.clear()
                self.install()
                self.put(ROOT_KEY + suffix, raw)
                with self.subTest(suffix=suffix, payloadKind=raw[:12]):
                    self.guard(False)

    def test_torn_owner_snapshot_blocks_even_if_only_valid_holder_changes(self):
        self.install()
        def replace_after_first_read(count):
            if count == 1:
                self.put(ROOT_KEY + "owner.json", owner(holder="8" * 32))
        self.driver.after_owner_read = replace_after_first_read
        report = self.guard(False)
        self.assertEqual("SNAPSHOT_CHANGED", report["reason"])
        self.assertGreaterEqual(self.driver.owner_reads, 2)

    def test_torn_owner_release_identity_or_disappearance_cannot_pass(self):
        replacements = (owner(released=False), owner(identity() | {"workflowRunId": "998"}), None)
        for replacement in replacements:
            self.driver.objects.clear()
            self.driver.owner_reads = 0
            self.install()
            def replace(count, replacement=replacement):
                if count == 1:
                    if replacement is None:
                        del self.driver.objects[ROOT_KEY + "owner.json"]
                    else:
                        self.put(ROOT_KEY + "owner.json", replacement)
            self.driver.after_owner_read = replace
            with self.subTest(replacement=replacement):
                self.guard(False)

    def test_not_attempted_terminal_requires_null_command_and_restored_exact_rules(self):
        records = (transition(commandId=COMMAND), transition(rulesState="PAUSED"), transition(rulesState="UNKNOWN"),
                   transition(phase="FAILED", outcome="RECOVERY_REQUIRED"), automatic(commandId=COMMAND), automatic(outcome="APPLIED"))
        for record in records:
            self.driver.objects.clear()
            self.install(record)
            with self.subTest(record=record):
                self.guard(False)
        self.driver.objects.clear()
        self.install()
        self.driver.rules[RULES[0]] = "DISABLED"
        self.guard(False)

    def test_applied_terminal_requires_full_current_host_binding_without_new_probe(self):
        record = transition(phase="COMPLETE", deploymentState="APPLIED", commandId=COMMAND, outcome="APPLIED", reason="APPLIED")
        self.install(record)
        self.assertEqual("SAFE_TERMINAL", self.guard(True)["reason"])
        original = copy.deepcopy(self.adapter.before)
        faults = ({"phase": "V8_STARTED"}, {"guardTrusted": False}, {"history": []},
                  {"currentImage": self.release["manifest"]["imageRepository"] + "@sha256:" + "f" * 64},
                  {"automaticContract": None})
        for changed in faults:
            self.adapter.before = original | changed
            with self.subTest(changed=changed):
                self.guard(False)
        for key, value in (("sourceSha", "f" * 40), ("buildRunId", "999"), ("buildRunAttempt", "2"),
                           ("currentImage", "untrusted-image"), ("history", [])):
            self.adapter.before = original | {"automaticContract": original["automaticContract"] | {key: value}}
            with self.subTest(contractField=key):
                self.guard(False)

    def test_applied_projection_with_no_precollected_facts_stops_without_new_ssm(self):
        self.install(transition(phase="COMPLETE", deploymentState="APPLIED", commandId=COMMAND, outcome="APPLIED", reason="APPLIED"))
        self.release["facts"] = None
        self.adapter.before = {}
        report = self.inspect()
        self.assertEqual("VALID", report["journal"]["status"])
        self.assertIs(True, report["journal"]["commandIdPresent"])
        self.assertEqual("HOST_UNVERIFIED", report["reason"])
        self.assertIs(False, report["safeToProceed"])

    def test_applied_nonterminal_or_commandless_records_never_pass(self):
        for record in (transition(phase="APPLIED", deploymentState="APPLIED", commandId=COMMAND, outcome="APPLIED", reason="APPLIED"),
                       transition(phase="COMPLETE", deploymentState="APPLIED", commandId=None, outcome="APPLIED", reason="APPLIED"),
                       automatic(deploymentState="APPLIED", commandId=COMMAND, outcome="APPLIED", rulesState="PAUSED")):
            self.driver.objects.clear()
            self.install(record)
            with self.subTest(record=record):
                self.guard(False)

    def test_rule_target_binding_failure_is_not_reported_as_safe_restoration(self):
        self.install()
        self.driver.bad_target = lambda target: target | {"RoleArn": "untrusted-" + SECRET}
        self.guard(False)

    def test_inspection_uses_only_fixed_bucket_and_instance_namespace(self):
        self.install()
        self.inspect()
        self.assertTrue(self.driver.raw_reads)
        self.assertTrue(all(key.startswith(ROOT_KEY) for _, key in self.driver.raw_reads))
        self.assertTrue(all(self.driver.argument(args, "--bucket") == BUCKET
                            for service, _, args in self.driver.boundary if service == "s3api"))

    def test_actual_s3_journal_download_requests_a_bounded_range(self):
        self.install()
        self.assertEqual(transition(), self.adapter.read_operation_for_reconcile(op_id(identity())))
        downloads = [args for service, operation, args in self.driver.boundary if service == "s3api" and operation == "get-object"]
        self.assertTrue(downloads)
        for args in downloads:
            self.assertIn("--range", args, "bound bytes before downloading untrusted S3 journal content")
            self.assertEqual("bytes=0-131072", self.driver.argument(args, "--range"))

    def test_s3_content_length_and_range_metadata_cannot_hide_oversized_or_torn_body(self):
        self.install()
        size = len(canonical(transition()))
        invalid = ({"ContentLength": True}, {"ContentLength": 0}, {"ContentLength": size + 1},
                   {"ContentLength": 131073}, {"ContentRange": SECRET},
                   {"ContentRange": f"bytes 0-{size - 1}/131073"}, {"ContentRange": f"bytes 1-{size}/{size + 1}"},
                   {"ContentRange": f"bytes 0-{size - 2}/{size}"}, {"ContentRange": f"bytes 0-{size - 1}/*"})
        for metadata in invalid:
            self.driver.metadata_override = metadata
            with self.subTest(metadata=metadata), self.assertRaises(ValueError):
                self.adapter.read_operation_for_reconcile(op_id(identity()))
        self.driver.metadata_override = {"ContentLength": size, "ContentRange": f"bytes 0-{size - 1}/{size}"}
        self.assertEqual(transition(), self.adapter.read_operation_for_reconcile(op_id(identity())))

    def test_separate_inspection_emission_is_canonical_bounded_and_preserves_legacy_artifact(self):
        emit = self.api("_emit_inspection")
        report = {"protocol": "meet-me-recovery-inspection-v1", "operationId": op_id(identity()),
                  "journal": {"status": "VALID", "protocol": "meet-me-transition-operation-v1", "sequence": 7,
                              "phase": "DEPLOY_INTENT", "deploymentState": "UNKNOWN", "rulesState": "RESTORED",
                              "outcome": "RECOVERY_REQUIRED", "reason": "SUBMISSION_UNCERTAIN", "commandIdPresent": False},
                  "owner": {"status": "PRESENT", "released": False, "identityMatches": True},
                  "safeToProceed": False, "reason": "OWNER_ACTIVE"}
        with self.cwd(), contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
            Path("recovery-report.json").write_bytes(b"original-v1-artifact")
            emit(report)
            self.assertEqual(canonical(report) + b"\n", Path("recovery-inspection.json").read_bytes())
            self.assertEqual(b"original-v1-artifact", Path("recovery-report.json").read_bytes())
        self.assertEqual("MEETME_RECOVERY_INSPECTION_V1 " + canonical(report).decode() + "\n", out.getvalue())
        self.assertEqual("", err.getvalue())

    def test_reconcile_pipeline_adds_separate_projection_without_changing_v1_fields(self):
        record = transition(phase="DEPLOY_INTENT", deploymentState="UNKNOWN", outcome="RECOVERY_REQUIRED",
                            reason="SUBMISSION_UNCERTAIN")
        self.install(record, owner(released=False))
        event_path = Path(self.directory) / "event.json"
        event_path.write_bytes(canonical({"inputs": {"action": "reconcile", "operation_id": op_id(identity())}}))
        env = {"OPERATION_ID": op_id(identity()), "GITHUB_EVENT_PATH": str(event_path), "GITHUB_EVENT_NAME": "workflow_dispatch"}
        with self.cwd(), self.public_mutation_traps(), \
                patch.object(PIPELINE, "prepare_release", return_value=contextlib.nullcontext(self.release)), \
                patch.object(PIPELINE, "_outputs"), contextlib.redirect_stdout(io.StringIO()) as out:
            PIPELINE.reconcile_pipeline(self.driver, env)
            legacy = json.loads(Path("recovery-report.json").read_bytes())
            self.assertEqual({"protocol", "identity", "operationId", "commandStatus", "hostVerified", "rulesObserved",
                              "pendingCommands", "deploymentComplete", "outcome"}, set(legacy))
            self.assertEqual("meet-me-release-reconciliation-v1", legacy["protocol"])
            self.assertEqual("RECOVERY_REQUIRED", legacy["outcome"])
            self.assertTrue(Path("recovery-inspection.json").is_file(), "CLI must emit the new diagnostic separately")
            projection = json.loads(Path("recovery-inspection.json").read_bytes())
            self.assert_report(projection)
            self.assertIs(False, projection["safeToProceed"])
        self.assertEqual(1, out.getvalue().count("MEETME_RECOVERY_INSPECTION_V1 "))
        self.assert_read_only()

    def test_reconciliation_v1_return_fields_and_conditional_known_command_get_are_unchanged(self):
        fixture = ReadOnlyAdapter(legacy_journal())
        report = PIPELINE.reconcile_known_operation(current_identity(), op_id(original_identity()), fixture)
        self.assertEqual({"protocol", "identity", "operationId", "commandStatus", "hostVerified", "rulesObserved",
                          "pendingCommands", "deploymentComplete", "outcome"}, set(report))
        self.assertEqual("meet-me-release-reconciliation-v1", report["protocol"])
        self.assertEqual("RECOVERY_REQUIRED", report["outcome"])
        self.assertIs(False, report["deploymentComplete"])
        self.assertEqual(1, sum(event[0] == "query_command" for event in fixture.events))
        self.assertFalse([event for event in fixture.events if event[0] == "FORBIDDEN"])

    def test_reconciliation_v1_without_command_never_queries_or_adds_owner_fields(self):
        fixture = ReadOnlyAdapter(legacy_journal(command_id=None))
        report = PIPELINE.reconcile_known_operation(current_identity(), op_id(original_identity()), fixture)
        self.assertEqual("UNKNOWN", report["commandStatus"])
        self.assertNotIn("owner", report)
        self.assertNotIn("journal", report)
        self.assertFalse([event for event in fixture.events if event[0] in ("query_command", "FORBIDDEN")])

    def public_mutation_traps(self):
        stack = contextlib.ExitStack()
        for name in ("claim_operation", "release_operation", "save_operation", "write_rule", "submit_deploy"):
            def denied(*args, name=name, **kwargs):
                self.public_calls.append(name)
                raise ValueError(SECRET)
            stack.enter_context(patch.object(PIPELINE.AwsReleaseAdapter, name, side_effect=denied))
        return stack

    def test_plan_fences_old_unfinished_owner_before_provider_gate_or_outputs(self):
        old = identity() | {"workflowRunId": "100"}
        self.install(transition(old, phase="DEPLOY_INTENT", deploymentState="UNKNOWN", outcome="RECOVERY_REQUIRED",
                                reason="SUBMISSION_UNCERTAIN"), owner(old, released=False))
        with self.cwd(), self.public_mutation_traps(), \
                patch.object(PIPELINE, "prepare_release", return_value=contextlib.nullcontext(self.release)), \
                patch.object(PIPELINE, "_gate_config", return_value={"state": "UNCONFIGURED"}) as gate, \
                patch.object(PIPELINE, "_outputs") as outputs, patch.object(PIPELINE, "_emit"), \
                patch.object(PIPELINE, "_emit_inspection", create=True, side_effect=lambda r: self.emitted.append(r)):
            with self.assertRaises(ValueError):
                PIPELINE.plan_pipeline(self.driver, {})
            gate.assert_not_called()
            outputs.assert_not_called()
        self.assertFalse([c for c in self.driver.boundary if c[0] == "ssm"])
        self.assertTrue(self.emitted)
        self.assert_read_only()

    def test_plan_allows_genuinely_empty_namespace_without_special_approval(self):
        rows = []
        with self.cwd(), self.public_mutation_traps(), \
                patch.object(PIPELINE, "prepare_release", return_value=contextlib.nullcontext(self.release)), \
                patch.object(PIPELINE, "_gate_config", return_value={"state": "UNCONFIGURED"}), \
                patch.object(PIPELINE, "_outputs", side_effect=rows.append), patch.object(PIPELINE, "_emit"):
            PIPELINE.plan_pipeline(self.driver, {})
        self.assertEqual("AUTO_READY", rows[-1]["mode"])
        self.assertFalse(rows[-1]["gateReady"])
        self.assert_read_only()

    def execute(self, transition_mode=False):
        results = []
        self.release.update(action="deploy" if transition_mode else "automatic", approve=transition_mode,
                            mode="TRANSITION_REQUIRED" if transition_mode else "AUTO_READY")
        plan = {"identity": self.release["identity"], "manifest": self.release["manifest"]}
        with self.cwd(), self.public_mutation_traps(), \
                patch.object(PIPELINE, "_read_plan", return_value=plan), \
                patch.object(PIPELINE, "prepare_release", return_value=contextlib.nullcontext(self.release)), \
                patch.object(PIPELINE, "validate_transition_entry"), patch.object(PIPELINE, "verify_public_contract") as public, \
                patch.object(PIPELINE, "_emit", side_effect=lambda r, *a, **k: results.append(copy.deepcopy(r))), \
                patch.object(PIPELINE, "_emit_inspection", create=True, side_effect=lambda r: self.emitted.append(copy.deepcopy(r))), \
                contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
            with self.assertRaises(ValueError):
                PIPELINE.execute_pipeline(self.driver, {}, transition_mode)
            public.assert_not_called()
        self.assert_read_only()
        self.assertTrue(results, "executor must emit explicit failure evidence before stopping")
        self.assertIs(False, results[-1]["deploymentComplete"])
        self.assertNotIn(SECRET, out.getvalue() + err.getvalue())
        return results[-1]

    def test_executor_rechecks_after_plan_and_blocks_new_candidate_before_any_claim(self):
        old = identity() | {"workflowRunId": "100"}
        self.install(transition(old, phase="DEPLOY_INTENT", deploymentState="UNKNOWN", outcome="RECOVERY_REQUIRED",
                                reason="SUBMISSION_UNCERTAIN"), owner(old, released=False))
        for transition_mode in (False, True):
            with self.subTest(transition=transition_mode):
                result = self.execute(transition_mode)
                self.assertEqual("NOT_ATTEMPTED", result["deploymentState"])
                self.assertTrue(self.emitted)

    def test_executor_preserves_same_operation_unknown_without_reentering_claim_or_send(self):
        self.install(transition(phase="DEPLOY_INTENT", deploymentState="UNKNOWN", outcome="RECOVERY_REQUIRED",
                                reason="SUBMISSION_UNCERTAIN"), owner(released=False))
        result = self.execute()
        self.assertEqual("UNKNOWN", result["deploymentState"])

    def test_executor_does_not_trust_old_safe_plan_when_owner_appears_before_execute(self):
        with self.cwd(), patch.object(PIPELINE, "prepare_release", return_value=contextlib.nullcontext(self.release)), \
                patch.object(PIPELINE, "_gate_config", return_value={"state": "UNCONFIGURED"}), \
                patch.object(PIPELINE, "_outputs"), patch.object(PIPELINE, "_emit"):
            PIPELINE.plan_pipeline(self.driver, {})
        self.install(transition(phase="DEPLOY_INTENT", deploymentState="UNKNOWN", outcome="RECOVERY_REQUIRED",
                                reason="SUBMISSION_UNCERTAIN"), owner(released=False))
        self.driver.boundary.clear()
        self.execute()


if __name__ == "__main__":
    unittest.main()
