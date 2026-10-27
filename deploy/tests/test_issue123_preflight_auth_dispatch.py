"""Independent synthetic main-preflight contract: reviewed auth marker, typed names only, no production calls."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SHA = "a" * 40
INSTANCE = "i-" + "a" * 17
COMMAND = "a" * 8 + "-" + "b" * 4 + "-" + "c" * 4 + "-" + "d" * 4 + "-" + "e" * 12
PARAMETER_ROOT = "/meet-me/production/"
AUTH_TYPES = {
    "secret/google-client-id": "SecureString",
    "secret/google-client-secret": "SecureString",
    "secret/kakao-client-id": "SecureString",
    "secret/kakao-client-secret": "SecureString",
    "config/google-redirect-uri": "String",
    "config/kakao-redirect-uri": "String",
    "config/auth-signing-key-id": "String",
    "config/auth-issuer": "String",
    "config/auth-audience": "String",
    "config/auth-web-callback-uri": "String",
    "config/allowed-origins": "String",
}
PROVIDER_TYPES = {"secret/openai-api-key": "SecureString", "secret/gemini-api-key": "SecureString",
                  "secret/typesafe-api-key": "SecureString"}
SYNTHETIC_PRIVATE_VALUE = "SYNTHETIC_PARAMETER_VALUE_MUST_NEVER_ESCAPE"


def observed_host():
    def file(directory=False):
        return {"exists": True, "directory" if directory else "regular": True,
                "noSymlink": True, "uid": 10001, "gid": 10001,
                "mode": "0o500" if directory else "0o400", "readableByApp": True}
    return {"protocol": "meetme-readonly-preflight-v1", "host": {
        "authFiles": {"directory": file(True), "jwks": file(), "signingKey": file()},
        "authMount": {key: True for key in ("required", "present", "bind", "sourceMatches", "targetMatches", "readOnly")},
        "containerBefore": {"present": True, "running": True, "pid": 123, "restartCount": 0,
                            "restartPolicy": "no", "health": "healthy",
                            "image": "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/meet-me/server@sha256:" + "b" * 64},
        "containerAfter": {"running": True, "pid": 123, "restartCount": 0},
    }, "flyway": {"status": "success", "database": "meetme", "serverVersion": "17.6", "history": []}}


class PreflightHarness:
    def __init__(self, root, marker=b"oauth-rs256-required\n", fault=None):
        self.root = root
        self.base = root / "deploy/preflight"
        self.base.mkdir(parents=True)
        driver_path = self.base / "run-preflight.py"
        driver_path.write_bytes((ROOT / "preflight/run-preflight.py").read_bytes())
        spec = importlib.util.spec_from_file_location("issue123_synthetic_preflight", driver_path)
        self.driver = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.driver)
        (self.base / "compiled").mkdir()
        (self.base / "compiled/ReadOnlyFlywayProbe.class").write_bytes(bytes.fromhex("cafebabe") + b"SYNTHETIC")
        (self.base / "host-preflight.sh").write_text("#!/usr/bin/env bash\n# SYNTHETIC_REVIEWED_HOST_READER\n")
        self.marker = self.base.parent / "runtime-auth-mode"
        if marker is not None:
            self.marker.write_bytes(marker)
        self.event = root / "event.json"
        self.event.write_text(json.dumps({"repository": {"full_name": "meet-me-duo/meet-me-server"}}))
        self.calls = []
        self.fault = fault
        self.environment = {
            "GITHUB_EVENT_PATH": str(self.event), "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_REPOSITORY": "meet-me-duo/meet-me-server", "GITHUB_REF": "refs/heads/main",
            "SOURCE_SHA": SHA, "GITHUB_SHA": SHA, "RUNTIME_INSTANCE_ID": INSTANCE,
            "AWS_REGION": "ap-northeast-2",
        }

    def aws(self, service, operation, *args):
        self.calls.append((service, operation, args))
        if (service, operation) == ("ec2", "describe-instances"):
            data = {"Reservations": [{"Instances": [{"InstanceId": INSTANCE, "State": {"Name": "running"}}]}]}
        elif (service, operation) == ("ssm", "describe-instance-information"):
            data = {"InstanceInformationList": [{"InstanceId": INSTANCE, "PingStatus": "Online", "PlatformType": "Linux"}]}
        elif (service, operation) == ("rds", "describe-db-instances"):
            data = {"DBInstances": [{"DBInstanceIdentifier": "meet-me-production", "DBInstanceStatus": "available"}]}
        elif (service, operation) == ("events", "describe-rule"):
            data = {"Name": args[args.index("--name") + 1], "State": "ENABLED"}
        elif (service, operation) == ("events", "list-targets-by-rule"):
            data = {"Targets": [{"Id": "synthetic", "Arn": "synthetic", "RoleArn": "synthetic",
                                 "RunCommandParameters": {"RunCommandTargets": [{"Key": "InstanceIds", "Values": [INSTANCE]}]}}]}
        elif (service, operation) == ("ssm", "describe-parameters"):
            names = set(re.findall(r"/meet-me/production/(?:secret|config)/[a-z0-9-]+", " ".join(args)))
            target = PARAMETER_ROOT + "secret/google-client-id"
            if self.fault == "denied" and target in names:
                return {"status": "unavailable", "reason": "AWS_DENIED_OR_FAILED"}
            types = AUTH_TYPES | PROVIDER_TYPES
            rows = [{"Name": name, "Type": types[name.removeprefix(PARAMETER_ROOT)], "Version": 1,
                     "Description": SYNTHETIC_PRIVATE_VALUE, "Value": SYNTHETIC_PRIVATE_VALUE} for name in sorted(names)
                    if name.removeprefix(PARAMETER_ROOT) in types]
            if self.fault == "missing":
                rows = [row for row in rows if row["Name"] != target]
            elif self.fault == "wrong-type":
                for row in rows:
                    if row["Name"] == target:
                        row["Type"] = "String"
            data = {"Parameters": rows}
        elif (service, operation) == ("ssm", "send-command"):
            data = {"Command": {"CommandId": COMMAND}}
        elif (service, operation) == ("ssm", "get-command-invocation"):
            data = {"Status": "Success", "StandardOutputContent": json.dumps(observed_host()), "StandardErrorContent": ""}
        else:
            raise AssertionError("Unexpected AWS operation in isolated preflight: " + service + "/" + operation)
        return {"status": "success", "data": data}

    def run(self, arguments=("--main",)):
        previous = Path.cwd()
        try:
            os.chdir(self.root)
            with patch.dict(os.environ, self.environment, clear=True), \
                    patch.object(self.driver.sys, "argv", ["synthetic-preflight", *arguments]), \
                    patch.object(self.driver, "aws_call", side_effect=self.aws), \
                    patch.object(self.driver, "active_commands", return_value={"status": "success", "data": []}), \
                    patch.object(self.driver.urllib.request, "urlopen", side_effect=lambda *a, **k: contextlib.nullcontext(SimpleNamespace(status=200))), \
                    contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
                try:
                    self.driver.main()
                finally:
                    self.output = out.getvalue() + err.getvalue()
        finally:
            os.chdir(previous)


class Issue123PreflightAuthDispatchTest(unittest.TestCase):
    def test_required_marker_reaches_exact_single_configured_instance_command(self):
        with tempfile.TemporaryDirectory(prefix="issue123-preflight-") as temporary:
            h = PreflightHarness(Path(temporary))
            h.run()
            sends = [call for call in h.calls if call[:2] == ("ssm", "send-command")]
            self.assertEqual(len(sends), 1)
            args = sends[0][2]
            self.assertEqual(args[args.index("--instance-ids") + 1], INSTANCE)
            self.assertEqual(args[args.index("--document-name") + 1], "AWS-RunShellScript")
            parameters = json.loads(args[args.index("--parameters") + 1])
            self.assertEqual(parameters["executionTimeout"], ["60"])
            first_line = parameters["commands"][0].splitlines()[0]
            self.assertRegex(first_line, r"^bash -s -- [A-Za-z0-9+/]+={0,2} oauth-rs256-required <<'MEETME_READONLY_SCRIPT'$",
                             "A host reader that supports auth checks does nothing unless the reviewed mode is actually passed")
            self.assertNotIn(SYNTHETIC_PRIVATE_VALUE, h.output)

    def test_all_auth_metadata_names_and_types_are_checked_without_fetching_values(self):
        with tempfile.TemporaryDirectory(prefix="issue123-preflight-metadata-") as temporary:
            h = PreflightHarness(Path(temporary))
            h.run()
            metadata = [call for call in h.calls if call[:2] == ("ssm", "describe-parameters")]
            checked = set(re.findall(r"/meet-me/production/(?:secret|config)/[a-z0-9-]+", " ".join(" ".join(call[2]) for call in metadata)))
            self.assertTrue({PARAMETER_ROOT + name for name in AUTH_TYPES} <= checked)
            for service, operation, args in h.calls:
                self.assertNotIn(operation, ("get-parameter", "get-parameters", "get-parameters-by-path"))
                self.assertNotIn("--with-decryption", args)
            summary = json.loads((h.root / "production-preflight-summary.json").read_text())
            projected = []
            def walk(value):
                if isinstance(value, dict):
                    if "Name" in value and str(value["Name"]).startswith(PARAMETER_ROOT):
                        projected.append(value)
                    for nested in value.values(): walk(nested)
                elif isinstance(value, list):
                    for nested in value: walk(nested)
            walk(summary)
            self.assertTrue(projected)
            self.assertTrue(all(set(row) == {"Name", "Type", "Version"} for row in projected))
            self.assertNotIn(SYNTHETIC_PRIVATE_VALUE, json.dumps(summary) + h.output)

    def test_missing_wrong_type_and_denied_auth_metadata_cannot_report_a_ready_preflight(self):
        for fault in ("missing", "wrong-type", "denied"):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(prefix="issue123-preflight-incomplete-") as temporary:
                h = PreflightHarness(Path(temporary), fault=fault)
                with self.assertRaises((ValueError, SystemExit)) as result:
                    h.run()
                if isinstance(result.exception, SystemExit):
                    self.assertNotEqual(result.exception.code, 0)
                self.assertNotIn(SYNTHETIC_PRIVATE_VALUE, h.output)

    def test_missing_malformed_nonregular_or_symlink_marker_denies_before_any_aws_operation(self):
        cases = {"missing": None, "unknown": b"legacy\n", "whitespace": b" oauth-rs256-required\n",
                 "trailing": b"oauth-rs256-required\n\n", "NUL": b"oauth-rs256-required\0",
                 "malformed-utf8": b"\xff", "symlink": None, "directory": None}
        for name, body in cases.items():
            for arguments in (("--main", "--validate"), ("--main",)):
                with self.subTest(marker=name, arguments=arguments), tempfile.TemporaryDirectory(prefix="issue123-preflight-marker-") as temporary:
                    h = PreflightHarness(Path(temporary), marker=body)
                    if name == "symlink":
                        target = h.root / "untrusted-marker"
                        target.write_bytes(b"oauth-rs256-required\n")
                        h.marker.symlink_to(target)
                    elif name == "directory":
                        h.marker.mkdir()
                    with self.assertRaises(ValueError):
                        h.run(arguments)
                    self.assertEqual(h.calls, [], "Source identity and reviewed mode must be validated before production authority")

    def test_both_exact_reviewed_marker_encodings_are_accepted_by_validation_without_aws(self):
        for marker in (b"oauth-rs256-required", b"oauth-rs256-required\n"):
            with self.subTest(marker=marker), tempfile.TemporaryDirectory(prefix="issue123-preflight-valid-marker-") as temporary:
                h = PreflightHarness(Path(temporary), marker=marker)
                h.run(("--main", "--validate"))
                self.assertEqual(h.calls, [])

    def test_existing_main_dispatch_identity_restrictions_remain_before_aws(self):
        cases = {"GITHUB_REF": "refs/heads/develop", "GITHUB_EVENT_NAME": "push", "GITHUB_SHA": "b" * 40,
                 "GITHUB_REPOSITORY": "different/repository"}
        for key, value in cases.items():
            with self.subTest(key=key), tempfile.TemporaryDirectory(prefix="issue123-preflight-target-") as temporary:
                h = PreflightHarness(Path(temporary))
                h.environment[key] = value
                with self.assertRaises(ValueError):
                    h.run()
                self.assertEqual(h.calls, [])

    def test_existing_main_only_workflow_checks_out_the_reviewed_auth_marker_without_new_role(self):
        workflow = (ROOT.parent / ".github/workflows/production-preflight.yml").read_text()
        self.assertIn("deploy/runtime-auth-mode", workflow)
        self.assertIn("github.ref == 'refs/heads/main'", workflow)
        self.assertIn("role-to-assume: ${{ vars.AWS_DEPLOY_ROLE_ARN }}", workflow)
        self.assertIn("environment: production", workflow)


if __name__ == "__main__":
    unittest.main()
