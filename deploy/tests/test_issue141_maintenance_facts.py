"""Issue 141: stopped maintenance facts use a separate path and no app revival."""
import importlib.util
import os
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


DRIVER = load("issue141_maintenance_driver", ROOT / "pipeline/run-approved-release.py")
FIXTURE = load("issue141_policy_fixture", ROOT / "tests/test_issue141_maintenance_policy.py")
HOST = load("issue141_maintenance_host", ROOT / "pipeline/host-approved-release.py") if os.name == "posix" else None
INSTANCE = "i-" + "a" * 17
REGION = "ap-northeast-2"
OLD_IMAGE = FIXTURE.REPOSITORY + "@sha256:" + "f" * 64


def observation():
    auth_file = {"exists": True, "regular": True, "noSymlink": True, "readableByApp": True}
    return {"protocol": "meetme-readonly-preflight-v1", "flyway": {
        "status": "success", "database": "synthetic", "serverVersion": "PostgreSQL 18",
        "history": FIXTURE.facts()["history"],
    }, "host": {
        "containerBefore": {"present": True, "running": False, "pid": 0, "restartCount": 3,
                            "restartPolicy": "no", "image": OLD_IMAGE, "health": ""},
        "containerAfter": {"running": False, "pid": 0, "restartCount": 3},
        "phase": "V8_STARTED", "minimum-contract": "input_revision_v8",
        "authFiles": {"directory": {"exists": True, "directory": True, "noSymlink": True, "readableByApp": True},
                      "jwks": dict(auth_file), "signingKey": dict(auth_file)},
        "authMount": {"required": False, "present": False, "bind": False,
                      "sourceMatches": False, "targetMatches": False, "readOnly": False},
    }}


def host_state():
    return {"protocol": "meet-me-release-state-v1", "phase": "V8_STARTED", "guardTrusted": True,
            "currentImage": OLD_IMAGE, "automaticContract": None}


class Issue141MaintenanceFactsTests(unittest.TestCase):
    def collect(self, observed, maintenance_resume=True, aws_facts=None):
        artifact = FIXTURE.manifest() | {"imageRef": FIXTURE.IMAGE}
        with patch.object(DRIVER, "_host_preflight", return_value=observed) as preflight, \
             patch.object(DRIVER, "_host_state", return_value=host_state()), \
             patch.object(DRIVER, "_aws_facts", return_value=aws_facts or (["DISABLED", "DISABLED"], 0, True)), \
             patch.object(DRIVER, "_ssm", side_effect=AssertionError("Facts collection must not deploy or start an app")):
            result = DRIVER._collect_facts(INSTANCE, REGION, artifact, auth_required=True,
                                           maintenance_resume=maintenance_resume)
            return result, preflight.call_args

    def test_stopped_stable_app_can_supply_explicit_maintenance_facts(self):
        (result, ready), call = self.collect(observation())
        self.assertIs(ready, True)
        self.assertEqual(result["phase"], "V8_STARTED")
        self.assertEqual(result["currentImage"], OLD_IMAGE)
        self.assertEqual(result["history"], FIXTURE.facts()["history"])
        maintenance_image = call.kwargs.get("maintenance_image", call.args[2] if len(call.args) > 2 else None)
        self.assertEqual(maintenance_image, FIXTURE.IMAGE, "The isolated DB reader must be pinned to the target image digest")

    def test_normal_facts_still_reject_a_stopped_app(self):
        with self.assertRaises(ValueError):
            self.collect(observation(), maintenance_resume=False)

    def test_maintenance_facts_require_the_app_to_remain_stopped_and_stable(self):
        for section, mutation in (
            ("containerBefore", {"running": True}), ("containerAfter", {"running": True}),
            ("containerBefore", {"pid": 123}), ("containerAfter", {"pid": 123}),
            ("containerAfter", {"restartCount": 4}),
            ("containerBefore", {"image": FIXTURE.IMAGE}),
        ):
            observed = observation()
            observed["host"][section].update(mutation)
            with self.subTest(section=section, mutation=mutation), self.assertRaises(ValueError):
                self.collect(observed)

    def test_failed_or_missing_db_history_is_never_replaced_by_an_empty_success(self):
        for flyway in ({"status": "unavailable", "reason": "HELPER_FAILED"},
                       {"status": "unavailable", "reason": "SQL_08006"}, {}):
            observed = observation()
            observed["flyway"] = flyway
            with self.subTest(flyway=flyway), self.assertRaises((ValueError, KeyError)):
                self.collect(observed)

    def test_untrusted_phase_and_active_commands_are_rejected_before_probe_dispatch(self):
        artifact = FIXTURE.manifest() | {"imageRef": FIXTURE.IMAGE}
        for changed_state, aws_facts in (
            (host_state() | {"guardTrusted": False}, (["DISABLED", "DISABLED"], 0, True)),
            (host_state() | {"phase": "READY"}, (["DISABLED", "DISABLED"], 0, True)),
            (host_state(), (["ENABLED", "DISABLED"], 0, True)),
            (host_state(), (["DISABLED", "DISABLED"], 1, True)),
        ):
            with self.subTest(state=changed_state, aws=aws_facts), \
                 patch.object(DRIVER, "_host_state", return_value=changed_state), \
                 patch.object(DRIVER, "_aws_facts", return_value=aws_facts), \
                 patch.object(DRIVER, "_host_preflight") as preflight:
                with self.assertRaises(ValueError):
                    DRIVER._collect_facts(INSTANCE, REGION, artifact, auth_required=True, maintenance_resume=True)
                preflight.assert_not_called()

    def test_state_changed_during_isolated_probe_is_rejected(self):
        artifact = FIXTURE.manifest() | {"imageRef": FIXTURE.IMAGE}
        with patch.object(DRIVER, "_host_state", side_effect=[host_state(), host_state() | {"phase": "READY"}]), \
             patch.object(DRIVER, "_aws_facts", return_value=(["DISABLED", "DISABLED"], 0, True)), \
             patch.object(DRIVER, "_host_preflight", return_value=observation()):
            with self.assertRaises(ValueError):
                DRIVER._collect_facts(INSTANCE, REGION, artifact, auth_required=True, maintenance_resume=True)


@unittest.skipUnless(os.name == "posix", "Root host helper uses Linux fcntl and executable mode semantics")
class Issue141HelperPreAuthorityTests(unittest.TestCase):
    def helpers(self, directory):
        release = Path(directory) / "releases" / FIXTURE.manifest()["releaseId"]
        scripts = release / "scripts"
        scripts.mkdir(parents=True)
        for name in ("render-runtime-env.sh", "export-certificate.sh"):
            path = scripts / name
            path.write_text("#!/usr/bin/env bash\nexit 0\n")
            path.chmod(0o700)
        return release

    def test_actual_helper_modes_are_checked_without_running_the_helpers(self):
        with tempfile.TemporaryDirectory() as temporary:
            release = self.helpers(temporary)
            with patch.object(HOST, "_safe", side_effect=lambda path, directory=False: Path(path)), \
                 patch.object(HOST, "_run", side_effect=AssertionError("Executable inspection cannot run scripts")):
                HOST._require_executable_helpers(release)
                for name in ("render-runtime-env.sh", "export-certificate.sh"):
                    path = release / "scripts" / name
                    for mode in (0o000, 0o600, 0o644, 0o066):
                        path.chmod(mode)
                        with self.subTest(helper=name, mode=oct(mode)), self.assertRaises(ValueError):
                            HOST._require_executable_helpers(release)
                    path.chmod(0o700)

    def test_nonexecutable_helpers_fail_before_facts_or_any_authority_mutation(self):
        for name in ("render-runtime-env.sh", "export-certificate.sh"):
            with self.subTest(helper=name), tempfile.TemporaryDirectory() as temporary:
                release = self.helpers(temporary)
                (release / "scripts" / name).chmod(0o600)
                artifact = FIXTURE.manifest() | {"imageRef": FIXTURE.IMAGE}
                attestation = {"action": "deploy", "approveFirstTransition": True,
                               "refreshRuleStates": ["DISABLED", "DISABLED"], "pendingCommands": 0,
                               "manifestSha256": "a" * 64}
                with patch.object(HOST, "ROOT", Path(temporary)), \
                     patch.object(HOST.os, "geteuid", return_value=0), \
                     patch.object(HOST, "_validated", return_value=artifact), \
                     patch.object(HOST, "_attestation", return_value=attestation), \
                     patch.object(HOST, "_safe", side_effect=lambda path, directory=False: Path(path)), \
                     patch.object(HOST, "_archive") as archive, \
                     patch.object(HOST, "_facts", side_effect=AssertionError("Facts must not precede executable validation")) as facts, \
                     patch.object(HOST, "_atomic", side_effect=AssertionError("No authority files may be changed")) as atomic, \
                     patch.object(HOST, "_run", side_effect=AssertionError("No installer, Docker, or AWS command may run")) as run:
                    with self.assertRaises(ValueError):
                        HOST.deploy(artifact, attestation)
                    archive.assert_called_once_with(release, artifact)
                    facts.assert_not_called()
                    atomic.assert_not_called()
                    run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
