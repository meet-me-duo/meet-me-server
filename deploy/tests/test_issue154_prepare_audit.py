"""Independent real prepare_release envelope tests, delegated service boundaries fake.

These test source/event/publication/archive pinning in the actual controller.
Existing immutable package/policy validators remain separately tested; no actual
GitHub, AWS, host process or database operation is performed.
"""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from test_issue154_adapter import PIPELINE
from test_issue154_approval import fixture, canonical, REPOSITORY
from test_issue154_public_contract import contract


BASE = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("issue154_prepare_existing_driver", BASE / "pipeline/run-approved-release.py")
ENTRY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ENTRY)


def archive_bytes(value):
    buffer = io.BytesIO()
    raw = canonical(value)
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        member = tarfile.TarInfo("public-api-contract.json")
        member.size = len(raw)
        member.mode = 0o644
        archive.addfile(member, io.BytesIO(raw))
    return buffer.getvalue()


class PreparePolicy:
    @staticmethod
    def validate_manifest(manifest, expected):
        if any(manifest.get(key) != value for key, value in expected.items()):
            raise ValueError("immutable expected publication mismatch")
        return dict(manifest, imageRef=manifest["imageRepository"] + "@" + manifest["imageDigest"])

    @staticmethod
    def validate_deploy_facts(validated, facts, action):
        if action not in ("automatic", "preflight"):
            raise AssertionError("preparation attempted a deploy action")
        return {"mode": "AUTO_READY"}


class PrepareDriver:
    BASE = BASE
    MAX_ARCHIVE = 4 * 1024 * 1024
    REPOSITORY = REPOSITORY
    validate_workflow_event = staticmethod(ENTRY.validate_workflow_event)

    def __init__(self):
        _, _, proof = fixture()
        self.manifest = copy.deepcopy(proof["manifest"])
        self.archive = archive_bytes(contract())
        self.manifest["archiveSha256"] = hashlib.sha256(self.archive).hexdigest()
        self.downloads = []
        self.origins = []
        self.packages = []
        self.fact_reads = []

    def _load(self, name, path):
        if name != "pipeline_approved_policy":
            raise AssertionError("unexpected boundary")
        return PreparePolicy

    def _download(self, bucket, key, target, maximum):
        self.downloads.append((bucket, key, maximum))
        target.write_bytes(canonical(self.manifest) if key.endswith("manifest.json") else self.archive)

    def _verify_origin(self, manifest, action):
        self.origins.append((copy.deepcopy(manifest), action))

    def _verify_package(self, archive, manifest):
        self.packages.append((archive.name, manifest["archiveSha256"]))
        return True

    def _collect_facts(self, *args, **kwargs):
        self.fact_reads.append((args, kwargs))
        return {"refreshRuleStates": ["ENABLED", "ENABLED"], "pendingCommands": 0}, True


class Issue154PrepareAuditTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="issue154-prepare-test-")
        self.addCleanup(self.directory.cleanup)
        self.driver = PrepareDriver()
        self.event = {"repository": {"full_name": REPOSITORY}, "workflow_run": {
            "id": 500, "name": "CI", "event": "push", "conclusion": "success",
            "head_branch": "main", "head_sha": "a" * 40,
            "repository": {"full_name": REPOSITORY}, "head_repository": {"full_name": REPOSITORY},
        }}
        self.path = Path(self.directory.name) / "event.json"
        self.env = {
            "GITHUB_EVENT_PATH": str(self.path), "GITHUB_REPOSITORY": REPOSITORY,
            "GITHUB_REF": "refs/heads/main", "GITHUB_EVENT_NAME": "workflow_run",
            "SOURCE_SHA": "a" * 40, "RELEASE_ACTION": "automatic", "APPROVE_FIRST_TRANSITION": "false",
            "RUNTIME_INSTANCE_ID": "i-1234567890abcdef0", "AWS_REGION": "ap-northeast-2",
            "DEPLOYMENT_ARTIFACT_BUCKET": "synthetic-deployment-artifacts",
            "BUILD_RUN_ID": "501", "BUILD_RUN_ATTEMPT": "1",
            "ECR_REPOSITORY_URL": self.driver.manifest["imageRepository"],
            "GITHUB_RUN_ID": "501", "GITHUB_RUN_ATTEMPT": "1",
        }

    def prepare(self, tip=None, observe=True):
        self.path.write_text(json.dumps(self.event))
        with patch.object(PIPELINE, "_github", return_value={"commit": {"sha": "a" * 40 if tip is None else tip}}):
            with PIPELINE.prepare_release(self.driver, self.env, observe=observe) as prepared:
                return copy.copy(prepared)

    def test_actual_preparer_binds_event_publication_archive_and_candidate_contract(self):
        prepared = self.prepare()
        self.assertEqual("a" * 40, prepared["identity"]["sourceSha"])
        self.assertEqual("501", prepared["identity"]["buildRunId"])
        self.assertEqual(contract(), prepared["publicContract"])
        self.assertEqual("AUTO_READY", prepared["mode"])
        self.assertEqual(1, len(self.driver.fact_reads))
        self.assertEqual(["releases/builds/501/1/manifest.json", "releases/builds/501/1/release.tar.gz"],
                         [row[1] for row in self.driver.downloads])

    def test_stale_main_tip_is_rejected_before_archive_or_host_observation(self):
        with self.assertRaises(ValueError):
            self.prepare(tip="f" * 40)
        self.assertEqual(1, len(self.driver.downloads))
        self.assertFalse(self.driver.packages)
        self.assertFalse(self.driver.fact_reads)

    def test_ci_run_or_source_binding_mismatch_cannot_reach_host_observation(self):
        for field, value in (("id", 999), ("head_sha", "f" * 40)):
            self.event["workflow_run"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.prepare()
            self.assertFalse(self.driver.fact_reads)
            self.event["workflow_run"][field] = 500 if field == "id" else "a" * 40

    def test_candidate_source_mismatch_cannot_be_hidden_by_otherwise_valid_event(self):
        self.driver.manifest["sourceSha"] = "f" * 40
        with self.assertRaises(ValueError):
            self.prepare()
        self.assertFalse(self.driver.fact_reads)

    def test_archive_digest_mismatch_is_rejected_before_package_or_host_calls(self):
        self.driver.archive += b"synthetic altered bytes"
        with self.assertRaises(ValueError):
            self.prepare()
        self.assertFalse(self.driver.packages)
        self.assertFalse(self.driver.fact_reads)

    def test_malformed_bound_public_contract_is_rejected_before_host_probe(self):
        self.driver.archive = archive_bytes(contract() | {"extra": "untrusted-field"})
        self.driver.manifest["archiveSha256"] = hashlib.sha256(self.driver.archive).hexdigest()
        with self.assertRaises(ValueError):
            self.prepare()
        self.assertFalse(self.driver.fact_reads)

    def test_other_repository_or_nonmain_event_is_rejected_before_any_download(self):
        for changed in ("repository", "ref"):
            if changed == "repository":
                self.event["repository"]["full_name"] = "untrusted/fork"
            else:
                self.env["GITHUB_REF"] = "refs/heads/develop"
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                self.prepare()
            self.assertFalse(self.driver.downloads)
            self.event["repository"]["full_name"] = REPOSITORY
            self.env["GITHUB_REF"] = "refs/heads/main"

    def test_immutable_publication_run_and_attempt_cannot_be_substituted(self):
        for key, value in (("BUILD_RUN_ID", "999"), ("BUILD_RUN_ATTEMPT", "2")):
            original = self.env[key]
            self.env[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.prepare()
            self.assertFalse(self.driver.fact_reads)
            self.env[key] = original

    def test_readonly_reconciliation_does_not_require_a_live_app_or_host_probe(self):
        self.env.update(GITHUB_EVENT_NAME="workflow_dispatch", RELEASE_ACTION="reconcile",
                        APPROVE_FIRST_TRANSITION="true", GITHUB_RUN_ID="600")
        self.event = {"repository": {"full_name": REPOSITORY}, "inputs": {"action": "reconcile"}}
        prepared = self.prepare(observe=False)
        self.assertEqual("reconcile", prepared["requestedAction"])
        self.assertEqual("preflight", prepared["action"])
        self.assertIs(prepared["approve"], False)
        self.assertIsNone(prepared["facts"])
        self.assertFalse(self.driver.fact_reads)
        self.assertEqual("600", prepared["identity"]["workflowRunId"])


if __name__ == "__main__":
    unittest.main()

