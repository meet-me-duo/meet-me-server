"""Independent required-auth target metadata tests. All AWS/SSM/host values are local synthetic metadata."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

DRIVER = load("issue131_release_driver", ROOT / "pipeline/run-approved-release.py")
READER = load("issue131_preflight_reader", ROOT / "preflight/run-preflight.py")
BASELINE = load("issue131_baseline_metadata_fixture", ROOT / "tests/test_eventbridge_target_metadata.py")
INSTANCE = "i-" + "a" * 17
REGION = "ap-northeast-2"
REPOSITORY = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/meet-me/server"
IMAGE = REPOSITORY + "@sha256:" + "b" * 64
PREFIX = "/meet-me/production/"
AUTH = {
    "secret/google-client-id": "SecureString", "secret/google-client-secret": "SecureString",
    "secret/kakao-client-id": "SecureString", "secret/kakao-client-secret": "SecureString",
    "config/google-redirect-uri": "String", "config/kakao-redirect-uri": "String",
    "config/auth-signing-key-id": "String", "config/auth-issuer": "String",
    "config/auth-audience": "String", "config/auth-web-callback-uri": "String", "config/allowed-origins": "String",
}


def parameters():
    return {PREFIX + name: {"Name": PREFIX + name, "Type": kind, "Version": 1} for name, kind in
            (AUTH | {"secret/openai-api-key": "SecureString"}).items()}


def aws_metadata(rows, next_token=None, calls=None):
    def read(service, operation, *args):
        if calls is not None:
            calls.append((service, operation, *args))
        if (service, operation) == ("ssm", "describe-parameters"):
            text = " ".join(map(str, args))
            requested = [name for name in parameters() if name in text]
            picked = [copy.deepcopy(row) for name, row in rows.items() if not requested or name in requested]
            result = {"Parameters": picked}
            if next_token is not None:
                result["NextToken"] = next_token
            return result
        return BASELINE.facts_call(service, operation, *args)
    return read


def host_observation():
    directory = {"exists": True, "directory": True, "noSymlink": True, "uid": 10001, "gid": 10001,
                 "mode": "0o500", "readableByApp": True}
    file = {"exists": True, "regular": True, "noSymlink": True, "uid": 10001, "gid": 10001,
            "mode": "0o400", "readableByApp": True}
    return {"protocol": "meetme-readonly-preflight-v1", "flyway": {"status": "success", "database": "synthetic", "serverVersion": "PostgreSQL 18", "history": []}, "host": {
        "containerBefore": {"present": True, "running": True, "pid": 1234, "restartCount": 0,
                            "restartPolicy": "no", "image": IMAGE, "health": "healthy"},
        "containerAfter": {"running": True, "pid": 1234, "restartCount": 0},
        "authFiles": {"directory": directory, "jwks": copy.deepcopy(file), "signingKey": copy.deepcopy(file)},
        # The currently running historical release has no auth bind. The prospective target supplies it.
        "authMount": {"required": False, "present": False, "bind": False, "sourceMatches": False,
                      "targetMatches": False, "readOnly": False},
    }}


class Issue131RequiredAuthMetadataTests(unittest.TestCase):
    def check_unready(self, rows, next_token=None):
        with patch.object(DRIVER, "_aws", side_effect=aws_metadata(rows, next_token)):
            try:
                result = DRIVER._aws_facts(INSTANCE, REGION, REPOSITORY, auth_required=True)
            except (KeyError, TypeError, ValueError, NotImplementedError):
                return
        self.assertIs(result[2], False, "Incomplete target auth must not be ready merely because OpenAI exists")

    def test_DX02_exact_eleven_typed_auth_parameters_are_required_in_addition_to_provider_metadata(self):
        calls = []
        with patch.object(DRIVER, "_aws", side_effect=aws_metadata(parameters(), calls=calls)):
            try:
                result = DRIVER._aws_facts(INSTANCE, REGION, REPOSITORY, auth_required=True)
            except Exception as failure:
                self.fail("Complete required metadata must be accepted: " + type(failure).__name__)
        self.assertEqual(result, (["ENABLED", "ENABLED"], 0, True))
        ssm = [call for call in calls if call[:2] == ("ssm", "describe-parameters")]
        self.assertTrue(ssm)
        queried = " ".join(str(item) for call in ssm for item in call)
        for short in AUTH:
            self.assertIn(PREFIX + short, queried)
        self.assertTrue(all(call[1] not in ("get-parameter", "get-parameters", "get-secret-value") for call in calls))
        self.assertNotIn("--with-decryption", queried)

    def test_DX02_each_missing_required_auth_parameter_blocks_readiness(self):
        for short in AUTH:
            rows = parameters(); rows.pop(PREFIX + short)
            with self.subTest(parameter=short):
                self.check_unready(rows)

    def test_DX02_each_wrong_type_and_invalid_version_blocks_readiness(self):
        for short, kind in AUTH.items():
            for invalid in ({"Type": "String" if kind == "SecureString" else "SecureString"},
                            {"Version": 0}, {"Version": True}, {"Version": "1"}):
                rows = parameters(); rows[PREFIX + short].update(invalid)
                with self.subTest(parameter=short, invalid=invalid):
                    self.check_unready(rows)

    def test_DX02_truncated_metadata_is_not_complete_readiness(self):
        self.check_unready(parameters(), "synthetic-next-page")

    def test_DX02_openai_only_is_not_complete_target_auth(self):
        self.check_unready({name: row for name, row in parameters().items() if name.endswith("/secret/openai-api-key")})

    def test_DX03_legacy_current_without_future_mount_does_not_block_a_complete_prospective_target(self):
        observed = host_observation()
        with patch.object(DRIVER, "_host_preflight", return_value=observed), \
             patch.object(DRIVER, "_host_state", return_value={"currentImage": IMAGE, "phase": "READY", "guardTrusted": True,
                                                              "automaticContract": "input_revision_v8"}), \
             patch.object(DRIVER, "_aws_facts", return_value=(["ENABLED", "ENABLED"], 0, True)) as aws:
            try:
                result = DRIVER._collect_facts(INSTANCE, REGION, {"imageRepository": REPOSITORY}, auth_required=True)
            except Exception as failure:
                self.fail("Complete prospective metadata must start from a legacy current container: " + type(failure).__name__)
        self.assertIs(result[1], True)
        self.assertTrue(aws.call_args.kwargs.get("auth_required") or (len(aws.call_args.args) == 4 and aws.call_args.args[3]))

    def test_DX03_missing_wrong_type_symlink_unreadable_target_files_block_readiness(self):
        for name in ("directory", "jwks", "signingKey"):
            for fault in ("missing", "symlink", "wrong-kind", "unreadable"):
                observed = host_observation(); item = observed["host"]["authFiles"][name]
                if fault == "missing": observed["host"]["authFiles"][name] = {"exists": False}
                elif fault == "symlink": item["noSymlink"] = False
                elif fault == "wrong-kind": item["directory" if name == "directory" else "regular"] = False
                else: item["readableByApp"] = False
                with self.subTest(item=name, fault=fault), patch.object(DRIVER, "_host_preflight", return_value=observed), \
                     patch.object(DRIVER, "_host_state", return_value={"currentImage": IMAGE, "phase": "READY", "guardTrusted": True,
                                                                      "automaticContract": "input_revision_v8"}), \
                     patch.object(DRIVER, "_aws_facts", return_value=(["ENABLED", "ENABLED"], 0, True)):
                    try:
                        result = DRIVER._collect_facts(INSTANCE, REGION, {"imageRepository": REPOSITORY}, auth_required=True)
                    except (KeyError, TypeError, ValueError, NotImplementedError):
                        continue
                    self.assertIs(result[1], False)

    def test_DX03_required_existing_auth_mount_needs_exact_bind_source_target_and_readonly(self):
        for fault in ("present", "bind", "sourceMatches", "targetMatches", "readOnly"):
            observed = host_observation(); mount = observed["host"]["authMount"]
            mount.update({key: True for key in mount}); mount[fault] = False
            with self.subTest(fault=fault), patch.object(DRIVER, "_host_preflight", return_value=observed), \
                 patch.object(DRIVER, "_host_state", return_value={"currentImage": IMAGE, "phase": "READY", "guardTrusted": True,
                                                                  "automaticContract": "input_revision_v8"}), \
                 patch.object(DRIVER, "_aws_facts", return_value=(["ENABLED", "ENABLED"], 0, True)):
                try:
                    result = DRIVER._collect_facts(INSTANCE, REGION, {"imageRepository": REPOSITORY}, auth_required=True)
                except (KeyError, TypeError, ValueError, NotImplementedError):
                    continue
                self.assertIs(result[1], False)

    def test_DX03_reader_accepts_only_the_declared_metadata_and_rejects_key_contents_or_hashes(self):
        observed = host_observation()
        try:
            valid = READER.summarize_invocation("Success", json.dumps(observed), "")
        except Exception as failure:
            self.fail("Declared auth metadata must survive strict reader: " + type(failure).__name__)
        self.assertEqual(valid["host"]["authFiles"], observed["host"]["authFiles"])
        for extra in ({"body": "SYNTHETIC_PRIVATE_MATERIAL"}, {"sha256": "a" * 64}):
            changed = copy.deepcopy(observed); changed["host"]["authFiles"]["signingKey"].update(extra)
            with self.assertRaises(ValueError):
                READER.summarize_invocation("Success", json.dumps(changed), "")

    def test_DX03_actual_host_invocation_declares_target_auth_mode_with_no_key_file_body_commands(self):
        with tempfile.TemporaryDirectory(prefix="issue131-host-invocation-") as root:
            base = Path(root); (base / "preflight/compiled").mkdir(parents=True)
            (base / "preflight/compiled/ReadOnlyFlywayProbe.class").write_bytes(bytes.fromhex("cafebabe"))
            (base / "preflight/host-preflight.sh").write_text((ROOT / "preflight/host-preflight.sh").read_text())
            observed = host_observation()
            with patch.object(DRIVER, "BASE", base), patch.object(DRIVER, "_ssm", return_value=observed) as ssm:
                try:
                    result = DRIVER._host_preflight(INSTANCE, auth_required=True)
                except Exception as failure:
                    self.fail("Auth metadata invocation must use the existing bounded read-only probe: " + type(failure).__name__)
            self.assertEqual(result, observed)
            command = ssm.call_args.args[1]
            self.assertIn("oauth-rs256-required", command)
            # Metadata paths are legitimate; copying or hashing private material is not.
            self.assertNotIn("cat /opt/meet-me/auth/", command)
            self.assertNotIn("sha256sum /opt/meet-me/auth/", command)


if __name__ == "__main__":
    unittest.main()
