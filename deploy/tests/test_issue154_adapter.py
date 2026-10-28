"""Independent fake AWS/S3 boundary tests for issue154.

No real network, AWS, credentials, provider values or operating calls are used.
"""
import copy
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


BASE = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("issue154_pipeline_adapter", BASE / "pipeline/release_pipeline.py")
PIPELINE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PIPELINE)
RULES = ("meet-me-production-db-credential-reconcile", "meet-me-production-db-credential-rotated")
SWITCH = "/meet-me/production/config/precheck-provider-enabled"
TYPESAFE = "/meet-me/production/secret/typesafe-api-key"
COMMAND = "12345678-1234-1234-1234-123456789abc"


def identity():
    source = "a" * 40
    return {"sourceSha": source, "buildRunId": "154", "buildRunAttempt": "1",
            "workflowRunId": "155", "workflowRunAttempt": "1",
            "releaseId": source + "-154-1", "imageDigest": "sha256:" + "b" * 64}


def operation_id():
    return PIPELINE._module("transition_orchestrator").operation_id(identity())


def record(state="NOT_ATTEMPTED", sequence=1, command=None):
    return {"protocol": "meet-me-transition-operation-v1", "operationId": operation_id(),
            "identity": identity(), "action": "deploy", "sequence": sequence,
            "phase": "DEPLOY_INTENT" if state == "UNKNOWN" else "SNAPSHOT",
            "originalRules": {name: "ENABLED" for name in RULES}, "changedRules": list(RULES),
            "deploymentState": state, "rulesState": "PAUSED",
            "outcome": "RECOVERY_REQUIRED" if state == "UNKNOWN" else "FAILED",
            "commandId": command, "reason": "SUBMISSION_UNCERTAIN" if state == "UNKNOWN" else "IN_PROGRESS"}


def automatic_record(state="NOT_ATTEMPTED", sequence=1):
    return {"protocol": "meet-me-automatic-operation-v1", "identity": identity(),
            "operationId": operation_id(), "sequence": sequence, "deploymentState": state,
            "commandId": None, "originalRules": {name: "ENABLED" for name in RULES},
            "outcome": "RECOVERY_REQUIRED" if state == "UNKNOWN" else "NOT_DEPLOYED",
            "rulesState": "UNTOUCHED"}


class FakeAwsDriver:
    BASE = BASE
    REPOSITORY = "meet-me-duo/meet-me-server"

    def __init__(self):
        self.objects = {}
        self.calls = []
        self.rules = {name: "ENABLED" for name in RULES}
        self.bad_target = None
        self.list_error = False
        self.before_put = None
        self.active_results = []
        self.active_reads = 0
        self.parameters = {}
        self.ready = True
        self.payload = {"protocol": "meet-me-approved-release-result-v1", "mode": "AUTO_READY",
                        "sourceSha": identity()["sourceSha"], "phase": "READY"}
        self.facts = None

    @staticmethod
    def argument(args, name):
        return args[args.index(name) + 1]

    @staticmethod
    def etag(raw):
        return '"' + hashlib.sha256(raw).hexdigest()[:32] + '"'

    def _aws(self, service, operation, *args):
        self.calls.append((service, operation, args))
        if service == "s3api":
            key = self.argument(args, "--prefix" if operation == "list-objects-v2" else "--key")
            if operation == "list-objects-v2":
                if self.list_error:
                    raise ValueError("synthetic unavailable listing")
                keys = sorted(k for k in self.objects if k.startswith(key))
                return {"IsTruncated": False, "Contents": [{"Key": k} for k in keys], "KeyCount": len(keys)}
            if operation == "get-object":
                raw, etag = self.objects[key]
                Path(args[-1]).write_bytes(raw)
                return {"ETag": etag, "ContentLength": len(raw)}
            if operation == "put-object":
                if self.before_put is not None:
                    callback, self.before_put = self.before_put, None
                    callback(key)
                prior = self.objects.get(key)
                if "--if-none-match" in args:
                    if self.argument(args, "--if-none-match") != "*" or prior is not None:
                        raise ValueError("conditional object exists")
                elif "--if-match" in args:
                    if prior is None or self.argument(args, "--if-match") != prior[1]:
                        raise ValueError("conditional ETag mismatch")
                else:
                    raise AssertionError("unconditional journal write")
                raw = Path(self.argument(args, "--body")).read_bytes()
                self.objects[key] = (raw, self.etag(raw))
                return {"ETag": self.objects[key][1]}
            raise AssertionError("unsupported S3 mutation")
        if service == "events":
            name = self.argument(args, "--rule" if operation == "list-targets-by-rule" else "--name")
            if name not in RULES:
                raise AssertionError("unrelated EventBridge target")
            if operation == "describe-rule":
                return {"Name": name, "State": self.rules[name]}
            if operation == "list-targets-by-rule":
                target = {
                    "Id": "RefreshDatabaseCredential" if name.endswith("rotated") else "ReconcileDatabaseCredential",
                    "Arn": "arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript",
                    "RoleArn": "arn:aws:iam::123456789012:role/meet-me-production-db-credential-refresh",
                    "RunCommandParameters": {"RunCommandTargets": [{"Key": "InstanceIds", "Values": ["i-1234567890abcdef0"]}]},
                }
                if self.bad_target is not None:
                    target = self.bad_target(target)
                return {"Targets": [target]}
            if operation in ("disable-rule", "enable-rule"):
                self.rules[name] = "DISABLED" if operation == "disable-rule" else "ENABLED"
                return {}
            raise AssertionError("unexpected EventBridge operation")
        if service == "ssm" and operation == "send-command":
            return {"Command": {"CommandId": COMMAND}}
        if service == "ssm" and operation == "describe-parameters":
            filter_value = self.argument(args, "--parameter-filters")
            name = filter_value.split("Values=", 1)[1]
            item = self.parameters.get(name)
            return {"Parameters": [] if item is None else [
                {k: v for k, v in item.items() if k != "Value"}
            ]}
        if service == "ssm" and operation == "get-parameter":
            name = self.argument(args, "--name")
            if name != SWITCH or "--with-decryption" in args:
                raise AssertionError("secret value or decryption requested")
            return {"Parameter": copy.deepcopy(self.parameters[name])}
        raise AssertionError("unexpected AWS boundary " + service + "/" + operation)

    def _load(self, name, path):
        if name != "pipeline_active_commands":
            raise AssertionError("unexpected loader")
        owner = self

        class Probe:
            @staticmethod
            def active_commands(instance):
                owner.active_reads += 1
                if owner.active_results:
                    return copy.deepcopy(owner.active_results.pop(0))
                return {"status": "success", "data": []}

        return Probe

    def _collect_facts(self, *args, **kwargs):
        return copy.deepcopy(self.facts), self.ready

    def _verify_origin(self, *args, **kwargs):
        return None

    def _deploy_command(self, *args, **kwargs):
        return "synthetic immutable reviewed release command"

    def aws_call(self, service, operation, *args):
        self.calls.append((service, operation, args))
        return {"status": "success", "data": {"Status": "Success",
                                              "StandardOutputContent": json.dumps(self.payload),
                                              "StandardErrorContent": ""}}

    def _deploy_result(self, status, stdout, stderr):
        return json.loads(stdout)


class FakePolicy:
    @staticmethod
    def validate_deploy_facts(validated, facts, action, approved):
        if action != "automatic" and approved is not True:
            raise ValueError("approval required")
        return {"mode": {"automatic": "AUTO_READY", "deploy": "TRANSITION_APPROVED",
                         "resume": "MAINTENANCE_RESUME_APPROVED"}[action]}


def release(driver, directory):
    bound = identity()
    manifest = {key: bound[key] for key in ("sourceSha", "buildRunId", "buildRunAttempt", "releaseId", "imageDigest")}
    manifest.update(imageRepository="123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/synthetic/server",
                    guardFingerprint="c" * 64, schemaFingerprint="d" * 64,
                    migrations=[{"version": "19", "script": "V19__synthetic.sql", "checksum": 123}])
    image = manifest["imageRepository"] + "@" + manifest["imageDigest"]
    history = [dict(row, success=True) for row in manifest["migrations"]]
    contract = {key: manifest[key] for key in ("sourceSha", "buildRunId", "buildRunAttempt",
                                              "guardFingerprint", "schemaFingerprint")}
    contract.update(currentImage=image, history=copy.deepcopy(history), protocol="meet-me-automatic-release-v1")
    driver.facts = {"history": history, "phase": "READY", "currentImage": image, "guardTrusted": True,
                    "automaticContract": contract, "refreshRuleStates": ["ENABLED", "ENABLED"], "pendingCommands": 0}
    driver.payload["currentImage"] = image
    return {"driver": driver, "identity": bound, "instance": "i-1234567890abcdef0", "region": "ap-northeast-2",
            "bucket": "synthetic-deployment-artifacts", "directory": Path(directory),
            "manifest": manifest, "validated": {"imageRef": image}, "facts": copy.deepcopy(driver.facts),
            "policy": FakePolicy(), "auth": True, "context": {"eventName": "workflow_run"},
            "prefix": "releases/builds/154/1/"}


class Issue154AdapterTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="issue154-adapter-test-")
        self.addCleanup(self.directory.cleanup)
        self.driver = FakeAwsDriver()
        self.release = release(self.driver, self.directory.name)
        self.adapter = PIPELINE.AwsReleaseAdapter(self.release, "deploy", approved=True)

    def writes(self, service=None, operation=None):
        write_operations = ("put-object", "delete-object", "send-command", "enable-rule", "disable-rule")
        return [c for c in self.driver.calls if c[1] in write_operations
                and (service is None or c[0] == service) and (operation is None or c[1] == operation)]

    def claim(self, adapter=None):
        adapter = self.adapter if adapter is None else adapter
        self.assertIs(adapter.claim_operation(operation_id(), identity()), True)

    def test_empty_s3_listing_can_claim_but_unavailable_listing_cannot_write(self):
        self.claim()
        self.assertEqual(1, len(self.writes("s3api", "put-object")))
        other_driver = FakeAwsDriver()
        other = PIPELINE.AwsReleaseAdapter(release(other_driver, self.directory.name), "deploy", True)
        other_driver.list_error = True
        with self.assertRaises(ValueError):
            other.claim_operation(operation_id(), identity())
        self.assertFalse([c for c in other_driver.calls if c[1] == "put-object"])

    def test_distinct_execution_nonce_prevents_two_instances_owning_same_operation(self):
        self.claim()
        other = PIPELINE.AwsReleaseAdapter(self.release, "deploy", approved=True)
        self.assertNotEqual(self.adapter.holder, other.holder)
        before = len(self.writes())
        self.assertIs(other.claim_operation(operation_id(), identity()), False)
        self.assertFalse(other.owns_operation(operation_id()))
        with self.assertRaises(ValueError):
            other.write_rule(RULES[0], "DISABLED")
        self.assertEqual(before, len(self.writes()))

    def test_owner_release_uses_conditional_tombstone_without_delete(self):
        self.claim()
        self.adapter.release_operation(operation_id())
        self.assertFalse(self.adapter.owns_operation(operation_id()))
        tombstone = json.loads(self.driver.objects[self.adapter.owner_key][0])
        self.assertIs(tombstone["released"], True)
        self.assertFalse(self.writes("s3api", "delete-object"))
        for _, _, args in self.writes("s3api", "put-object"):
            self.assertTrue("--if-match" in args or "--if-none-match" in args)

    def test_etag_conflict_cannot_overwrite_a_new_owner(self):
        self.claim()
        prior = self.driver.objects[self.adapter.owner_key]
        owner = json.loads(prior[0])
        owner["holder"] = "f" * 32
        replacement = json.dumps(owner).encode()
        self.driver.before_put = lambda key: self.driver.objects.__setitem__(key, (replacement, self.driver.etag(replacement)))
        with self.assertRaises(ValueError):
            self.adapter.release_operation(operation_id())
        self.assertEqual("f" * 32, json.loads(self.driver.objects[self.adapter.owner_key][0])["holder"])

    def test_journal_rejects_other_identity_or_stale_sequence_before_overwrite(self):
        self.claim()
        original = record()
        self.adapter.save_operation(operation_id(), original)
        for invalid in (copy.deepcopy(original), original | {"sequence": 0},
                        original | {"sequence": 2, "identity": identity() | {"workflowRunId": "999"}}):
            before = len(self.writes())
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.adapter.save_operation(operation_id(), invalid)
            self.assertEqual(before, len(self.writes()))

    def test_durable_unknown_intent_cannot_be_reset_to_not_attempted(self):
        self.claim()
        self.adapter.save_operation(operation_id(), record("UNKNOWN"))
        before = len(self.writes())
        with self.assertRaises(ValueError):
            self.adapter.save_operation(operation_id(), record("NOT_ATTEMPTED", sequence=2))
        self.assertEqual(before, len(self.writes()))
        self.assertEqual("UNKNOWN", self.adapter.load_operation(operation_id())["deploymentState"])

    def test_original_rule_snapshot_cannot_change_during_the_same_operation(self):
        self.claim()
        self.adapter.save_operation(operation_id(), record())
        changed = record(sequence=2)
        changed["originalRules"][RULES[0]] = "DISABLED"
        before = len(self.writes())
        with self.assertRaises(ValueError):
            self.adapter.save_operation(operation_id(), changed)
        self.assertEqual(before, len(self.writes()))

    def test_loaded_journal_must_be_bound_to_its_operation_identity(self):
        self.claim()
        corrupted = record("UNKNOWN")
        corrupted["identity"]["workflowRunId"] = "999"
        raw = json.dumps(corrupted).encode()
        self.driver.objects[self.adapter.root_key + operation_id() + ".json"] = (raw, self.driver.etag(raw))
        with self.assertRaises(ValueError):
            self.adapter.load_operation(operation_id())

    def test_corrupt_journal_cannot_authorize_a_deployment_send(self):
        automatic = PIPELINE.AwsReleaseAdapter(self.release, "automatic", approved=False)
        self.claim(automatic)
        corrupted = automatic_record("UNKNOWN")
        corrupted["identity"]["sourceSha"] = "f" * 40
        raw = json.dumps(corrupted).encode()
        self.driver.objects[automatic.root_key + operation_id() + ".json"] = (raw, self.driver.etag(raw))
        automatic.validate_facts()
        with self.assertRaises(ValueError):
            automatic.submit_deploy()
        self.assertFalse(self.writes("ssm", "send-command"))

    def test_rule_mutation_requires_claim_exact_rule_and_approval(self):
        with self.assertRaises(ValueError):
            self.adapter.write_rule(RULES[0], "DISABLED")
        self.claim()
        with self.assertRaises(ValueError):
            self.adapter.write_rule("unrelated-rule", "DISABLED")
        self.adapter.approved = False
        with self.assertRaises(ValueError):
            self.adapter.write_rule(RULES[0], "DISABLED")
        self.assertFalse(self.writes("events"))

    def test_live_target_role_instance_or_document_mismatch_prevents_rule_write(self):
        mutations = [
            lambda t: t | {"Id": "OtherTarget"},
            lambda t: t | {"Arn": "arn:aws:ssm:ap-northeast-2::document/OtherDocument"},
            lambda t: t | {"RoleArn": "arn:aws:iam::123456789012:role/other-role"},
            lambda t: t | {"RunCommandParameters": {"RunCommandTargets": [{"Key": "InstanceIds", "Values": ["i-fffffffffffffffff"]}]}},
        ]
        self.claim()
        for mutation in mutations:
            self.driver.bad_target = mutation
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.adapter.write_rule(RULES[0], "DISABLED")
        self.assertFalse(self.writes("events"))

    def test_approved_exact_target_write_never_uses_broader_rule_operations(self):
        self.claim()
        self.adapter.write_rule(RULES[0], "DISABLED")
        self.assertEqual([("events", "disable-rule", ("--name", RULES[0]))], self.writes("events"))
        self.assertEqual("DISABLED", self.adapter.read_rules()[RULES[0]])

    def test_enable_is_denied_for_interrupted_maintenance_even_with_owned_approval(self):
        self.release["facts"]["phase"] = "V8_STARTED"
        self.driver.facts["phase"] = "V8_STARTED"
        self.driver.rules[RULES[0]] = "DISABLED"
        interrupted = PIPELINE.AwsReleaseAdapter(self.release, "resume", approved=True)
        self.claim(interrupted)
        with self.assertRaises(ValueError):
            interrupted.write_rule(RULES[0], "ENABLED")
        self.assertFalse(self.writes("events"))

    def test_enable_rechecks_fresh_runtime_after_a_prior_successful_host_proof(self):
        self.claim()
        self.assertIs(self.adapter.verify_host({"sourceSha": identity()["sourceSha"], "phase": "READY"}), True)
        self.driver.facts["phase"] = "V8_STARTED"
        self.driver.rules[RULES[0]] = "DISABLED"
        with self.assertRaises(ValueError):
            self.adapter.write_rule(RULES[0], "ENABLED")
        self.assertIsNone(self.adapter.host_proof)
        self.assertFalse(self.writes("events"))

    def test_active_command_drain_is_bounded_and_does_not_invent_zero_on_error(self):
        self.driver.active_results = [{"status": "success", "data": ["synthetic-active"]} for _ in range(4)]
        with patch.object(PIPELINE.time, "sleep"):
            self.assertEqual(1, self.adapter.active_commands())
        self.assertEqual(4, self.driver.active_reads)
        self.driver.active_results = [{"status": "unavailable", "data": []}]
        with self.assertRaises(ValueError):
            self.adapter.active_commands()

    def test_deploy_submission_requires_ownership_and_durable_unknown_intent(self):
        automatic = PIPELINE.AwsReleaseAdapter(self.release, "automatic", approved=False)
        automatic.validate_facts()
        with self.assertRaises(ValueError):
            automatic.submit_deploy()
        self.assertFalse(self.writes("ssm", "send-command"))
        self.claim(automatic)
        automatic.save_operation(operation_id(), automatic_record())
        with self.assertRaises(ValueError):
            automatic.submit_deploy()
        self.assertFalse(self.writes("ssm", "send-command"))

    def test_durable_intent_allows_one_submission_but_never_a_duplicate_send(self):
        automatic = PIPELINE.AwsReleaseAdapter(self.release, "automatic", approved=False)
        self.claim(automatic)
        automatic.save_operation(operation_id(), automatic_record("UNKNOWN"))
        automatic.validate_facts()
        self.assertEqual(COMMAND, automatic.submit_deploy())
        with self.assertRaises(ValueError):
            automatic.submit_deploy()
        self.assertEqual(1, len(self.writes("ssm", "send-command")))

    def test_fresh_host_evidence_rejects_prior_reply_after_runtime_or_history_change(self):
        payload = {"sourceSha": identity()["sourceSha"], "phase": "READY"}
        self.assertIs(self.adapter.verify_host(payload), True)
        self.assertIsNotNone(self.adapter.host_proof)
        self.driver.facts["currentImage"] = self.release["manifest"]["imageRepository"] + "@sha256:" + "f" * 64
        self.assertIs(self.adapter.verify_host(payload), False)
        self.assertIsNone(self.adapter.host_proof)

    def test_fresh_history_guard_or_contract_mismatch_is_not_host_proof(self):
        for mutation in (
            {"history": []}, {"phase": "V8_STARTED"}, {"guardTrusted": False},
            {"automaticContract": self.driver.facts["automaticContract"] | {"sourceSha": "f" * 40}},
        ):
            self.driver.facts = copy.deepcopy(self.release["facts"]) | mutation
            with self.subTest(mutation=mutation):
                self.assertIs(self.adapter.verify_host({"sourceSha": identity()["sourceSha"], "phase": "READY"}), False)

    def test_unchanged_interrupted_maintenance_does_not_authorize_rule_restore(self):
        self.release["facts"]["phase"] = "V8_STARTED"
        self.driver.facts["phase"] = "V8_STARTED"
        interrupted = PIPELINE.AwsReleaseAdapter(self.release, "resume", approved=True)
        self.assertIs(interrupted.safe_to_restore(), False)

    def test_off_or_missing_provider_switch_never_fetches_a_key_value(self):
        self.assertIs(self.adapter.provider_ready(), True)
        self.driver.parameters[SWITCH] = {"Name": SWITCH, "Type": "String", "Version": 1, "Value": "false"}
        self.assertIs(self.adapter.provider_ready(), True)
        self.assertFalse([c for c in self.driver.calls if c[0] == "ssm" and c[1] == "get-parameter"
                          and TYPESAFE in c[2]])
        self.assertFalse([c for c in self.driver.calls if c[0] == "ssm" and c[1] == "describe-parameters"
                          and any(TYPESAFE in str(v) for v in c[2])])

    def test_provider_on_missing_or_wrong_type_key_blocks_before_any_send(self):
        self.driver.parameters[SWITCH] = {"Name": SWITCH, "Type": "String", "Version": 1, "Value": "true"}
        for key in (None, {"Name": TYPESAFE, "Type": "String", "Version": 1}):
            if key is None:
                self.driver.parameters.pop(TYPESAFE, None)
            else:
                self.driver.parameters[TYPESAFE] = key
            with self.subTest(key=key):
                self.assertIs(self.adapter.provider_ready(), False)
                with self.assertRaises(ValueError):
                    self.adapter.validate_facts()
        self.assertFalse(self.writes("ssm", "send-command"))

    def test_provider_on_accepts_only_key_metadata_and_reads_only_nonsecret_switch(self):
        self.driver.parameters[SWITCH] = {"Name": SWITCH, "Type": "String", "Version": 1, "Value": "true"}
        self.driver.parameters[TYPESAFE] = {"Name": TYPESAFE, "Type": "SecureString", "Version": 1}
        self.assertIs(self.adapter.provider_ready(), True)
        reads = [c for c in self.driver.calls if c[0] == "ssm" and c[1] == "get-parameter"]
        self.assertEqual(1, len(reads))
        self.assertEqual(SWITCH, self.driver.argument(reads[0][2], "--name"))
        self.assertNotIn("--with-decryption", reads[0][2])

    def test_invalid_explicit_provider_switch_fails_closed(self):
        for value in ("TRUE", "", "1", " false ", True):
            self.driver.parameters[SWITCH] = {"Name": SWITCH, "Type": "String", "Version": 1, "Value": value}
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.adapter.provider_ready()

    def test_adapter_does_not_print_raw_external_failure_payload(self):
        self.driver.list_error = True
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            with self.assertRaises(ValueError):
                self.adapter.claim_operation(operation_id(), identity())
        self.assertEqual("", stdout.getvalue())
        self.assertEqual("", stderr.getvalue())

    def test_unconfigured_pre_yaml_gate_makes_no_github_call(self):
        with patch.object(PIPELINE, "_github") as github:
            with self.assertRaises(ValueError):
                PIPELINE._gate_environment(self.driver, {"protocol": "meet-me-transition-gate-policy-v1", "state": "UNCONFIGURED"})
            github.assert_not_called()


if __name__ == "__main__":
    unittest.main()

