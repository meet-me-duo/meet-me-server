"""Issue 141: explicit maintenance approval cannot weaken normal release policy.

All facts are synthetic. No AWS, Docker or database process is invoked.
"""
import copy
import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("issue141_release_policy", ROOT / "pipeline/approved_release.py")
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)
REPOSITORY = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/meet-me/server"
IMAGE = REPOSITORY + "@sha256:" + "b" * 64


def manifest():
    source = "a" * 40
    return {
        "protocol": "meet-me-approved-release-v1", "sourceSha": source,
        "buildRunId": "141", "buildRunAttempt": "1", "imageRepository": REPOSITORY,
        "imageDigest": "sha256:" + "b" * 64, "releaseId": source + "-141-1",
        "archiveSha256": "c" * 64, "schemaFingerprint": "d" * 64,
        "guardFingerprint": "e" * 64,
        "migrations": [{"version": str(number), "script": f"V{number}__synthetic.sql", "checksum": number * 37}
                       for number in range(1, 11)],
        "origin": {"repository": "meet-me-duo/meet-me-server", "ref": "refs/heads/main",
                   "workflow": "CI", "event": "push", "conclusion": "success",
                   "ciRunId": "140", "ciSha": source},
    }


def facts():
    return {
        "history": [dict(row, success=True) for row in manifest()["migrations"][:7]],
        "phase": "V8_STARTED", "currentImage": REPOSITORY + "@sha256:" + "f" * 64,
        "guardTrusted": True, "automaticContract": None,
        "refreshRuleStates": ["DISABLED", "DISABLED"], "pendingCommands": 0,
    }


class Issue141MaintenancePolicyTests(unittest.TestCase):
    def test_explicit_resume_accepts_only_the_reviewed_sticky_maintenance_state(self):
        for prefix in range(7, 11):
            observed = facts()
            observed["history"] = [dict(row, success=True) for row in manifest()["migrations"][:prefix]]
            with self.subTest(prefix=prefix):
                self.assertEqual(
                    {"mode": "MAINTENANCE_RESUME_APPROVED"},
                    POLICY.validate_deploy_facts(manifest(), observed, "resume", True),
                )

    def test_resume_requires_literal_boolean_approval(self):
        for approval in (False, None, 0, 1, "true", "false"):
            with self.subTest(approval=approval), self.assertRaises(ValueError):
                POLICY.validate_deploy_facts(manifest(), facts(), "resume", approval)

    def test_resume_rejects_every_missing_safety_fact(self):
        mutations = [
            {"phase": phase} for phase in ("PRE_V8", "READY", "UNINSTALLED", "", None)
        ] + [
            {"guardTrusted": value} for value in (False, 1, "true", None)
        ] + [
            {"refreshRuleStates": value} for value in (
                ["ENABLED", "DISABLED"], ["DISABLED", "ENABLED"], ["ENABLED", "ENABLED"],
                ["DISABLED"], ["DISABLED", "DISABLED", "DISABLED"], [], None,
            )
        ] + [
            {"pendingCommands": value} for value in (1, -1, False, "0", None)
        ] + [
            {"currentImage": REPOSITORY + ":latest"},
            {"currentImage": "999999999999.dkr.ecr.ap-northeast-2.amazonaws.com/other@sha256:" + "f" * 64},
        ]
        for mutation in mutations:
            observed = facts() | mutation
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                POLICY.validate_deploy_facts(manifest(), observed, "resume", True)

    def test_resume_does_not_accept_unverified_or_nonprefix_history(self):
        original = facts()["history"]
        cases = [[], original[:6], original[1:], original[::-1], original + [original[-1]]]
        for changed_field, changed_value in (("checksum", 999), ("success", False), ("success", 1),
                                             ("script", "V7__different.sql"), ("version", "999")):
            changed = copy.deepcopy(original)
            changed[-1][changed_field] = changed_value
            cases.append(changed)
        for history in cases:
            with self.subTest(history=history), self.assertRaises(ValueError):
                POLICY.validate_deploy_facts(manifest(), facts() | {"history": history}, "resume", True)

    def test_resume_still_validates_the_complete_immutable_manifest(self):
        for mutation in (
            {"releaseId": "another-release"}, {"archiveSha256": "unknown"},
            {"guardFingerprint": "unknown"}, {"schemaFingerprint": "unknown"},
            {"imageDigest": "latest"}, {"buildRunAttempt": "0"},
            {"origin": manifest()["origin"] | {"conclusion": "failure"}},
        ):
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                POLICY.validate_deploy_facts(manifest() | mutation, facts(), "resume", True)

    def test_automatic_and_preflight_never_choose_maintenance_resume(self):
        for action in ("automatic", "preflight"):
            with self.subTest(action=action):
                self.assertEqual({"mode": "TRANSITION_REQUIRED"},
                                 POLICY.validate_deploy_facts(manifest(), facts(), action, True))

    def test_the_normal_ready_automatic_contract_remains_available(self):
        artifact = manifest()
        history = [dict(row, success=True) for row in artifact["migrations"]]
        record = {name: artifact[name] for name in (
            "sourceSha", "buildRunId", "buildRunAttempt", "schemaFingerprint", "guardFingerprint")}
        record.update(protocol="meet-me-automatic-release-v1", history=history, currentImage=IMAGE)
        observed = facts() | {"history": history, "phase": "READY", "currentImage": IMAGE,
                              "automaticContract": record, "refreshRuleStates": ["ENABLED", "ENABLED"]}
        for action in ("automatic", "preflight", "deploy"):
            with self.subTest(action=action):
                self.assertEqual({"mode": "AUTO_READY"},
                                 POLICY.validate_deploy_facts(artifact, observed, action, False))


if __name__ == "__main__":
    unittest.main()
