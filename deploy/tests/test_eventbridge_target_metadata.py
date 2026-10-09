"""AWS Target schema regressions, with no credentials or remote operations."""
import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ROOT = Path(__file__).resolve().parents[1]
driver = load("release_driver", ROOT / "pipeline/run-approved-release.py")
preflight = load("preflight_metadata", ROOT / "preflight/run-preflight.py")
INSTANCE = "i-" + "a" * 17
REGION = "ap-northeast-2"
ACCOUNT = "123456789012"
REPOSITORY = ACCOUNT + ".dkr.ecr." + REGION + ".amazonaws.com/meet-me/server"
TARGETS = [{"Key": "InstanceIds", "Values": [INSTANCE]}]


def target(suffix):
    return {
        "Id": "RefreshDatabaseCredential" if suffix == "rotated" else "ReconcileDatabaseCredential",
        "Arn": "arn:aws:ssm:" + REGION + "::document/AWS-RunShellScript",
        "RoleArn": "arn:aws:iam::" + ACCOUNT + ":role/meet-me-production-db-credential-refresh",
        "RunCommandParameters": {"RunCommandTargets": copy.deepcopy(TARGETS)},
        "Input": "synthetic-confidential-command-body",
    }


def facts_call(service, operation, *args):
    if (service, operation) == ("ec2", "describe-instances"):
        return {"Reservations": [{"Instances": [{"InstanceId": INSTANCE, "State": {"Name": "running"}}]}]}
    if (service, operation) == ("ssm", "describe-instance-information"):
        return {"InstanceInformationList": [{"InstanceId": INSTANCE, "PingStatus": "Online"}]}
    if (service, operation) == ("events", "describe-rule"):
        return {"Name": args[1], "State": "ENABLED"}
    if (service, operation) == ("events", "list-targets-by-rule"):
        return {"Targets": [target(args[1].rsplit("-", 1)[1])]}
    if (service, operation) == ("ssm", "list-commands"):
        return {"Commands": []}
    if (service, operation) == ("ssm", "describe-parameters"):
        return {"Parameters": [{"Name": "/meet-me/production/secret/openai-api-key", "Type": "SecureString", "Version": 1}]}
    raise AssertionError("Unexpected API operation: " + service + "/" + operation)


class TargetMetadataTests(unittest.TestCase):
    def test_documented_nested_target_is_accepted_by_release_facts(self):
        with patch.object(driver, "_aws", side_effect=facts_call):
            try:
                result = driver._aws_facts(INSTANCE, REGION, REPOSITORY)
            except (KeyError, TypeError, ValueError) as failure:
                result = type(failure).__name__
            self.assertEqual(result, (["ENABLED", "ENABLED"], 0, True))

    def test_metadata_projection_flattens_only_declared_fields_and_omits_input(self):
        item = target("rotated")
        result = preflight.project({"status": "success", "data": {"Targets": [item]}}, preflight.refresh_target_metadata)
        self.assertEqual(result, {"status": "success", "data": [{k: item[k] for k in ("Id", "Arn", "RoleArn")} | {"RunCommandTargets": TARGETS}]})
        self.assertNotIn("synthetic-confidential", str(result))

    def test_top_level_shadow_cannot_override_wrong_or_missing_nested_target(self):
        for nested in (None, {}, {"RunCommandTargets": []}, {"RunCommandTargets": [{"Key": "InstanceIds", "Values": ["i-" + "b" * 17]}]}):
            item = target("rotated")
            item["RunCommandTargets"] = copy.deepcopy(TARGETS)
            if nested is None:
                item.pop("RunCommandParameters")
            else:
                item["RunCommandParameters"] = nested
            def read(service, operation, *args):
                if (service, operation) == ("events", "list-targets-by-rule"):
                    current = target(args[1].rsplit("-", 1)[1])
                    current["RunCommandTargets"] = copy.deepcopy(TARGETS)
                    if nested is None:
                        current.pop("RunCommandParameters")
                    else:
                        current["RunCommandParameters"] = copy.deepcopy(nested)
                    return {"Targets": [current]}
                return facts_call(service, operation, *args)
            with self.subTest(nested=nested), patch.object(driver, "_aws", side_effect=read):
                with self.assertRaises((KeyError, ValueError, TypeError)):
                    driver._aws_facts(INSTANCE, REGION, REPOSITORY)

    def test_missing_nested_metadata_remains_unavailable_in_preflight(self):
        item = target("rotated")
        item.pop("RunCommandParameters")
        item["RunCommandTargets"] = copy.deepcopy(TARGETS)
        result = preflight.project({"status": "success", "data": {"Targets": [item]}}, preflight.refresh_target_metadata)
        self.assertEqual(result, {"status": "unavailable", "reason": "UNEXPECTED_METADATA"})


if __name__ == "__main__":
    unittest.main()
