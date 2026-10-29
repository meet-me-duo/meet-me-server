"""Issue154 AWS CLI transport contracts, entirely offline and without credentials.

The original adapter fixtures intentionally return decoded objects. These tests
add the missing subprocess -> optional controller -> release driver -> adapter
boundary while preserving the legacy wrapper and readonly preflight contract.
EventBridge EnableRule/DisableRule document no output; a successful write reply
does not replace strict rule/target readback or resolve an uncertain SSM send.
"""
import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest
from unittest.mock import patch


BASE = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


PREFLIGHT = load("issue154_control_preflight", BASE / "preflight/run-preflight.py")
DRIVER = load("issue154_control_driver", BASE / "pipeline/run-approved-release.py")
FIXTURE = load("issue154_control_fixture", BASE / "tests/test_issue154_adapter.py")
PIPELINE, RULES = FIXTURE.PIPELINE, FIXTURE.RULES
SECRET = "synthetic-sensitive-payload-never-emit"
WRITES = {("events", "enable-rule"), ("events", "disable-rule")}
REASONS = {"AWS_DENIED_OR_FAILED", "AWS_TIMEOUT", "AWS_INVALID_JSON", "AWS_EXECUTION_FAILED",
           "AWS_UNAVAILABLE", "AWS_UNEXPECTED_STDERR", "AWS_UNEXPECTED_OUTPUT"}
MARKER = "MEETME_AWS_CALL_FAILURE_V1 "


def diagnostic(operation, reason):
    return MARKER + json.dumps({"service": "events", "operation": operation, "reason": reason},
                               sort_keys=True, separators=(",", ":")) + "\n"


class CliDriver(FIXTURE.FakeAwsDriver):
    """Remote state is fake, while every _aws call uses the real CLI decoder."""

    def __init__(self):
        super().__init__()
        self.responses = {}
        self.cli_calls = []
        self.applied_before_error = set()
        self.write_stdout = ""

    def _aws(self, service, operation, *args):
        return DRIVER._aws(service, operation, *args)

    def subprocess(self, command, **kwargs):
        service, operation, *args = command[5:]
        self.cli_calls.append((service, operation, tuple(args)))
        key = (service, operation)
        if key in self.responses:
            if key in self.applied_before_error:
                FIXTURE.FakeAwsDriver._aws(self, service, operation, *args)
            value = self.responses[key]
            if isinstance(value, BaseException):
                raise value
            return value
        data = FIXTURE.FakeAwsDriver._aws(self, service, operation, *args)
        return subprocess.CompletedProcess(command, 0, self.write_stdout if key in WRITES else json.dumps(data), "")


class AwsControlTransportTests(unittest.TestCase):
    def setUp(self):
        self.enterContext(patch.dict(os.environ, AWS_REGION="ap-northeast-2"))

    def call(self, service, operation, response, driver=False):
        options = {"side_effect": response} if isinstance(response, BaseException) else {"return_value": response}
        with patch.object(subprocess, "run", **options) as run, \
                contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
            if driver:
                with self.assertRaises(ValueError) as raised:
                    DRIVER._aws(service, operation, "--name", SECRET)
                result = str(raised.exception)
            else:
                result = DRIVER.aws_call(service, operation, "--name", SECRET)
        self.assertEqual(run.call_count, 1, "transport failures must not blindly retry a remote write")
        emitted = out.getvalue() + err.getvalue()
        if (service, operation) in WRITES and (driver or result.get("status") != "success"):
            self.assertTrue(out.getvalue().startswith(MARKER))
            self.assertEqual("", err.getvalue())
            payload = json.loads(out.getvalue()[len(MARKER):])
            self.assertEqual({"service", "operation", "reason"}, set(payload))
            self.assertEqual((service, operation), (payload["service"], payload["operation"]))
            self.assertIn(payload["reason"], REASONS)
            self.assertEqual(diagnostic(operation, payload["reason"]), emitted)
        else:
            self.assertEqual(emitted, "")
        self.assertNotIn(SECRET, emitted)
        self.assertNotIn(SECRET, str(result))
        return result

    def test_only_documented_no_output_event_writes_accept_empty_or_whitespace(self):
        for service, operation in sorted(WRITES):
            for body in ("", " \t\r\n"):
                with self.subTest(operation=operation, body=repr(body)):
                    result = self.call(service, operation, subprocess.CompletedProcess([], 0, body, ""))
                    self.assertEqual({"status": "success", "data": {}}, result)

    def test_empty_output_does_not_turn_reads_or_other_writes_into_success(self):
        operations = (("events", "describe-rule"), ("events", "list-targets-by-rule"),
                      ("events", "put-rule"), ("ssm", "send-command"), ("ssm", "list-commands"),
                      ("s3api", "list-objects-v2"), ("s3api", "get-object"), ("s3api", "put-object"),
                      ("ssm", "enable-rule"), ("Events", "enable-rule"), ("events", "EnableRule"))
        for service, operation in operations:
            for body in ("", " \t\r\n"):
                with self.subTest(service=service, operation=operation, body=repr(body)):
                    self.assertEqual({"status": "unavailable", "reason": "AWS_INVALID_JSON"},
                                     self.call(service, operation, subprocess.CompletedProcess([], 0, body, SECRET)))

    def test_malformed_and_nonobject_write_bodies_still_fail_driver(self):
        for _, operation in sorted(WRITES):
            for body in (SECRET, "{", "None", "\x00", "[]", "null", "true", "0", '""'):
                with self.subTest(operation=operation, body=repr(body)):
                    self.call("events", operation, subprocess.CompletedProcess([], 0, body, ""), driver=True)

    def test_no_output_writes_reject_stderr_and_nonempty_json_objects(self):
        for _, operation in sorted(WRITES):
            for body in ("", " \n", "{}"):
                for stderr in (" \n", SECRET):
                    with self.subTest(operation=operation, body=body, stderr=repr(stderr)):
                        self.assertEqual({"status": "unavailable", "reason": "AWS_UNEXPECTED_STDERR"},
                                         self.call("events", operation, subprocess.CompletedProcess([], 0, body, stderr)))
            for body in ('{"unexpected":"' + SECRET + '"}', "[]", "null", "true", "0", '""'):
                with self.subTest(operation=operation, body=body):
                    self.assertEqual({"status": "unavailable", "reason": "AWS_UNEXPECTED_OUTPUT"},
                                     self.call("events", operation, subprocess.CompletedProcess([], 0, body, "")))

    def test_valid_json_metadata_and_existing_object_write_reply_remain_supported(self):
        for service, operation, data in (("events", "describe-rule", {"Name": RULES[0], "State": "ENABLED"}),
                                         ("events", "enable-rule", {}), ("s3api", "put-object", {"ETag": '"abc"'})):
            with self.subTest(service=service, operation=operation):
                self.assertEqual({"status": "success", "data": data},
                                 self.call(service, operation, subprocess.CompletedProcess([], 0, json.dumps(data), "")))

    def test_control_failure_marker_preserves_only_safe_subprocess_reason(self):
        cases = ((subprocess.CompletedProcess([], 254, SECRET, SECRET), "AWS_DENIED_OR_FAILED"),
                 (subprocess.TimeoutExpired([SECRET], 25, output=SECRET, stderr=SECRET), "AWS_TIMEOUT"),
                 (OSError(SECRET), "AWS_EXECUTION_FAILED"), (UnicodeError(SECRET), "AWS_EXECUTION_FAILED"),
                 (RuntimeError(SECRET), "AWS_UNAVAILABLE"),
                 (subprocess.CompletedProcess([], 0, SECRET, ""), "AWS_INVALID_JSON"),
                 (subprocess.CompletedProcess([], 0, "", SECRET), "AWS_UNEXPECTED_STDERR"),
                 (subprocess.CompletedProcess([], 0, '{"unexpected":"' + SECRET + '"}', ""), "AWS_UNEXPECTED_OUTPUT"))
        for _, operation in sorted(WRITES):
            for response, reason in cases:
                options = {"side_effect": response} if isinstance(response, BaseException) else {"return_value": response}
                with self.subTest(operation=operation, reason=reason), \
                        patch.object(subprocess, "run", **options) as run, \
                        contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
                    with self.assertRaisesRegex(ValueError, "^UNTRUSTED_RELEASE_STATE$"):
                        DRIVER._aws("events", operation, "--name", SECRET)
                self.assertEqual(diagnostic(operation, reason), out.getvalue())
                self.assertEqual("", err.getvalue())
                self.assertEqual(1, run.call_count)

    def test_readonly_preflight_retains_original_empty_control_rejection(self):
        for _, operation in sorted(WRITES):
            for body in ("", " \t\r\n"):
                with self.subTest(operation=operation, body=repr(body)), \
                        patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 0, body, "")), \
                        contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
                    result = PREFLIGHT.aws_call("events", operation, "--name", SECRET)
                self.assertEqual({"status": "unavailable", "reason": "AWS_INVALID_JSON"}, result)
                self.assertEqual("", out.getvalue() + err.getvalue())

    def test_legacy_archive_without_optional_controller_keeps_read_transport(self):
        with tempfile.TemporaryDirectory(prefix="issue154-legacy-transport-") as directory:
            legacy = Path(directory)
            (legacy / "preflight").mkdir()
            shutil.copyfile(BASE / "preflight/run-preflight.py", legacy / "preflight/run-preflight.py")
            with patch.object(DRIVER, "BASE", legacy):
                self.assertEqual({"status": "success", "data": {"synthetic": "metadata"}},
                                 self.call("events", "describe-rule", subprocess.CompletedProcess([], 0, '{"synthetic":"metadata"}', "")))
                for _, operation in sorted(WRITES):
                    with patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "", "")) as run, \
                            contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
                        with self.assertRaisesRegex(ValueError, "^UNTRUSTED_RELEASE_STATE$"):
                            DRIVER._aws("events", operation, "--name", SECRET)
                    self.assertEqual("", out.getvalue() + err.getvalue())
                    self.assertEqual(1, run.call_count)

    def test_controller_reads_delegate_unchanged_without_control_diagnostics(self):
        controller = load("issue154_controller_transport_contract", BASE / "pipeline/release_pipeline.py")
        self.assertTrue(callable(getattr(controller, "aws_call", None)), "approved controller must own control transport")
        for service, operation in (("events", "describe-rule"), ("events", "list-targets-by-rule"),
                                   ("ssm", "send-command"), ("ssm", "enable-rule"),
                                   ("events", "put-rule"), ("s3api", "put-object")):
            response = {"status": "unavailable", "reason": {"untrusted": SECRET}}
            calls = []
            def read_fallback(*args):
                calls.append(args)
                return response
            with self.subTest(service=service, operation=operation), \
                    patch.object(subprocess, "run", side_effect=AssertionError("delegated read must not run a second process")) as run, \
                    contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
                result = controller.aws_call(read_fallback, service, operation, "--name", SECRET)
            self.assertIs(response, result)
            self.assertEqual([(service, operation, "--name", SECRET)], calls)
            self.assertEqual(0, run.call_count)
            self.assertEqual("", out.getvalue() + err.getvalue())

    def test_driver_does_not_emit_write_marker_for_read_or_unrelated_failure(self):
        for service, operation in (("events", "describe-rule"), ("events", "list-targets-by-rule"),
                                   ("ssm", "send-command"), ("ssm", "disable-rule"),
                                   ("events", SECRET), (SECRET, "enable-rule")):
            with self.subTest(service=service, operation=operation):
                self.call(service, operation, subprocess.CompletedProcess([], 254, SECRET, SECRET), driver=True)

    def test_empty_write_response_cannot_mask_nonzero_timeout_or_os_failure(self):
        cases = ((subprocess.CompletedProcess([], 254, "", SECRET), "AWS_DENIED_OR_FAILED"),
                 (subprocess.TimeoutExpired([SECRET], 25, output="", stderr=SECRET), "AWS_TIMEOUT"),
                 (OSError(SECRET), "AWS_EXECUTION_FAILED"), (UnicodeError(SECRET), "AWS_EXECUTION_FAILED"),
                 (RuntimeError(SECRET), "AWS_UNAVAILABLE"))
        for _, operation in sorted(WRITES):
            for response, reason in cases:
                with self.subTest(operation=operation, reason=reason):
                    self.assertEqual({"status": "unavailable", "reason": reason}, self.call("events", operation, response))


class AwsControlAdapterTransportTests(unittest.TestCase):
    def setUp(self):
        directory = self.enterContext(tempfile.TemporaryDirectory(prefix="issue154-control-transport-"))
        self.driver = CliDriver()
        self.release = FIXTURE.release(self.driver, directory)
        self.adapter = PIPELINE.AwsReleaseAdapter(self.release, "deploy", approved=True)
        self.enterContext(patch.dict(os.environ, AWS_REGION="ap-northeast-2"))
        self.enterContext(patch.object(subprocess, "run", side_effect=self.driver.subprocess))

    def claim(self):
        self.assertIs(self.adapter.claim_operation(FIXTURE.operation_id(), FIXTURE.identity()), True)

    def writes(self, service="events"):
        return [c for c in self.driver.cli_calls if c[0] == service
                and c[1] in ("enable-rule", "disable-rule", "send-command")]

    def run_transition(self):
        with contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
            result = PIPELINE._module("transition_orchestrator").run_transition(
                {"identity": FIXTURE.identity(), "action": "deploy", "approvalVerified": True}, self.adapter)
        self.messages = out.getvalue()
        self.assertEqual("", err.getvalue())
        self.assertNotIn(SECRET, self.messages)
        return result

    def test_both_exact_rules_pause_and_restore_through_real_transport_and_strict_readback(self):
        self.claim()
        expected = {name: "ENABLED" for name in RULES}
        for name in RULES:
            try:
                self.adapter.write_rule(name, "DISABLED")
            except ValueError:
                self.fail("documented zero-exit empty DisableRule response must reach strict readback")
            expected[name] = "DISABLED"
            self.assertEqual(expected, self.adapter.read_rules())
        for name in reversed(RULES):
            try:
                self.adapter.write_rule(name, "ENABLED")
            except ValueError:
                self.fail("documented zero-exit empty EnableRule response must reach strict readback")
            expected[name] = "ENABLED"
            self.assertEqual(expected, self.adapter.read_rules())
        self.assertEqual([("events", "disable-rule", ("--name", name)) for name in RULES]
                         + [("events", "enable-rule", ("--name", name)) for name in reversed(RULES)], self.writes())

    def test_successful_transition_sends_once_and_restores_both_rules_without_duplicate_writes(self):
        result = self.run_transition()
        self.assertEqual("", self.messages)
        self.assertEqual(("APPLIED", "APPLIED", "RESTORED"),
                         (result["outcome"], result["deploymentState"], result["rulesState"]))
        first_writes = copy.deepcopy(self.writes())
        self.assertEqual(4, len(first_writes))
        self.assertEqual(1, len(self.writes("ssm")))
        self.assertEqual({name: "ENABLED" for name in RULES}, self.adapter.read_rules())
        again = self.run_transition()
        self.assertEqual("APPLIED", again["outcome"])
        self.assertEqual(first_writes, self.writes())
        self.assertEqual(1, len(self.writes("ssm")))

    def test_empty_or_malformed_rule_and_target_metadata_cannot_authorize_write(self):
        self.claim()
        for operation in ("describe-rule", "list-targets-by-rule"):
            for body in ("", " \n", SECRET, "{", "{}", "[]", "null"):
                with self.subTest(operation=operation, body=repr(body)):
                    self.driver.responses[("events", operation)] = subprocess.CompletedProcess([], 0, body, SECRET)
                    with self.assertRaises(ValueError):
                        self.adapter.write_rule(RULES[0], "DISABLED")
                    self.assertEqual([], self.writes())
                    self.driver.responses.clear()

    def test_exact_target_binding_is_revalidated_for_both_rules(self):
        self.claim()
        mutations = (lambda t: t | {"Id": "OtherTarget"},
                     lambda t: t | {"Arn": "arn:aws:ssm:ap-northeast-2::document/OtherDocument"},
                     lambda t: t | {"RoleArn": "arn:aws:iam::123456789012:role/other-role"},
                     lambda t: t | {"RunCommandParameters": {"RunCommandTargets": [
                         {"Key": "InstanceIds", "Values": ["i-fffffffffffffffff"]}]}})
        for name in RULES:
            for mutation in mutations:
                self.driver.bad_target = mutation
                with self.subTest(name=name, mutation=mutation), self.assertRaises(ValueError):
                    self.adapter.write_rule(name, "DISABLED")
                self.assertEqual([], self.writes())

    def test_rule_identity_state_target_cardinality_and_pagination_remain_strict(self):
        self.claim()
        target = FIXTURE.FakeAwsDriver._aws(self.driver, "events", "list-targets-by-rule", "--rule", RULES[1])["Targets"][0]
        cases = (("describe-rule", {"Name": "unrelated-rule", "State": "ENABLED"}),
                 ("describe-rule", {"Name": RULES[1], "State": "ENABLED_WITH_ALL_CLOUDTRAIL_MANAGEMENT_EVENTS"}),
                 ("list-targets-by-rule", {"Targets": []}),
                 ("list-targets-by-rule", {"Targets": [target, target]}),
                 ("list-targets-by-rule", {"Targets": [target], "NextToken": SECRET}))
        for operation, data in cases:
            with self.subTest(operation=operation, data=data):
                self.driver.responses[("events", operation)] = subprocess.CompletedProcess([], 0, json.dumps(data), "")
                with self.assertRaises(ValueError):
                    self.adapter.write_rule(RULES[0], "DISABLED")
                self.assertEqual([], self.writes())
                self.driver.responses.clear()

    def test_claim_approval_and_exact_rule_still_gate_empty_output_writes(self):
        for name in RULES:
            with self.assertRaises(ValueError):
                self.adapter.write_rule(name, "DISABLED")
        self.claim()
        self.adapter.approved = False
        for name in RULES:
            with self.assertRaises(ValueError):
                self.adapter.write_rule(name, "DISABLED")
        self.adapter.approved = True
        with self.assertRaises(ValueError):
            self.adapter.write_rule("unrelated-rule", "DISABLED")
        other = PIPELINE.AwsReleaseAdapter(self.release, "deploy", approved=True)
        self.assertIs(other.claim_operation(FIXTURE.operation_id(), FIXTURE.identity()), False)
        with self.assertRaises(ValueError):
            other.write_rule(RULES[0], "DISABLED")
        self.assertEqual([], self.writes())

    def test_lost_pause_reply_is_read_back_restored_and_never_retried_or_submitted(self):
        key = ("events", "disable-rule")
        self.driver.responses[key] = subprocess.TimeoutExpired([SECRET], 25, stderr=SECRET)
        self.driver.applied_before_error.add(key)
        result = self.run_transition()
        self.assertEqual(diagnostic("disable-rule", "AWS_TIMEOUT"), self.messages,
                         "caught orchestration failure must retain a bounded transport diagnostic")
        self.assertEqual(("FAILED", "NOT_ATTEMPTED", "RESTORED"),
                         (result["outcome"], result["deploymentState"], result["rulesState"]))
        self.assertEqual([("events", "disable-rule", ("--name", RULES[0])),
                          ("events", "enable-rule", ("--name", RULES[0]))], self.writes())
        self.assertEqual([], self.writes("ssm"))
        self.assertEqual({name: "ENABLED" for name in RULES}, self.adapter.read_rules())
        prior = copy.deepcopy(self.writes())
        self.run_transition()
        self.assertEqual(prior, self.writes())
        self.assertEqual([], self.writes("ssm"))

    def test_lost_ssm_send_reply_remains_unknown_with_rules_paused_and_no_second_send(self):
        key = ("ssm", "send-command")
        self.driver.responses[key] = subprocess.TimeoutExpired([SECRET], 25, stderr=SECRET)
        self.driver.applied_before_error.add(key)
        result = self.run_transition()
        self.assertEqual(("RECOVERY_REQUIRED", "UNKNOWN", "PAUSED"),
                         (result["outcome"], result["deploymentState"], result["rulesState"]))
        self.assertEqual(1, len(self.writes("ssm")))
        self.assertEqual([("events", "disable-rule", ("--name", name)) for name in RULES], self.writes())
        self.assertEqual({name: "DISABLED" for name in RULES}, self.adapter.read_rules())
        again = self.run_transition()
        self.assertEqual("UNKNOWN", again["deploymentState"])
        self.assertEqual(1, len(self.writes("ssm")))
        self.assertEqual(2, len(self.writes()))


if __name__ == "__main__":
    unittest.main()
