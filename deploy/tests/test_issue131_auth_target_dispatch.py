"""Required-auth decision comes from verified target bytes through real main, never the legacy running container."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path); module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module); return module

DRIVER = load("issue131_target_driver", ROOT / "pipeline/run-approved-release.py")
PUBLISHER = load("issue131_target_publisher", ROOT / "pipeline/publish_manifest.py")
AUTH17 = tuple(sorted(("scripts/host-release-guard.sh", "scripts/install-host-release-guard.sh", "scripts/deploy-release.sh",
    "scripts/rollback-release.sh", "scripts/refresh-database-credential.sh", "compose.guard.yml", "meet-me-guarded-restart.service",
    "pipeline/approved_release.py", "pipeline/host-approved-release.py", "pipeline/run-approved-release.py", "pipeline/publish_manifest.py",
    "preflight/host-preflight.sh", "preflight/run-preflight.py", "preflight/ReadOnlyFlywayProbe.java", "runtime-auth-mode",
    "scripts/render-runtime-env.sh", "compose.production.yml")))
CURRENT18 = tuple(sorted(AUTH17 + ("runtime-ai-policy.json",)))
LEGACY14 = tuple(name for name in AUTH17 if name not in ("runtime-auth-mode", "scripts/render-runtime-env.sh", "compose.production.yml"))
SHA = "a" * 40
REPOSITORY = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/meet-me/server"
IMAGE = REPOSITORY + "@sha256:" + "b" * 64
POLICY = b'{"protocol":"meetme-managed-ai-policy-v1","state":"UNCONFIGURED"}\n'
ENV = {"SOURCE_SHA": SHA, "BUILD_RUN_ID": "11", "BUILD_RUN_ATTEMPT": "1", "ECR_REPOSITORY_URL": REPOSITORY,
       "IMAGE_DIGEST": "sha256:" + "b" * 64, "CI_RUN_ID": "10", "CI_SHA": SHA}


def digest(body): return hashlib.sha256(body).hexdigest()
def canonical(value): return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()

def archive(root, contract):
    files = {name: (ROOT / name).read_bytes() for name in contract}
    files.update({"preflight/compiled/ReadOnlyFlywayProbe.class": bytes.fromhex("cafebabe"),
                  "runtime-provider-mode": b"gemini-luna-required\n", "compose.production.yml": (ROOT / "compose.production.yml").read_bytes()})
    if "runtime-ai-policy.json" in contract: files["runtime-ai-policy.json"] = POLICY
    p = root / ("archive-" + str(len(contract)) + ".tar.gz")
    with tarfile.open(p, "w:gz") as packed:
        for name, body in files.items():
            item = tarfile.TarInfo(name); item.size = len(body); item.mode = 0o644; packed.addfile(item, io.BytesIO(body))
    manifest = {"sourceSha": SHA, "releaseId": SHA + "-11-1", "imageRepository": REPOSITORY,
                "archiveSha256": digest(p.read_bytes()), "schemaFingerprint": digest(canonical([])), "migrations": [],
                "guardFingerprint": digest(canonical([{"path": name, "sha256": digest(files[name])} for name in contract]))}
    return p, manifest


class Issue131TargetAuthDispatchTests(unittest.TestCase):
    def test_DX04_publisher_fingerprints_exact_current_eighteen_including_exact_policy_bytes(self):
        with tempfile.TemporaryDirectory(prefix="issue131-publisher-") as tmp:
            root = Path(tmp); shutil.copytree(ROOT, root / "deploy", ignore=shutil.ignore_patterns("tests", "compiled", "__pycache__"))
            shutil.copytree(ROOT.parent / "src/main/resources/db/migration", root / "src/main/resources/db/migration")
            release = root / "release.tar.gz"; release.write_bytes(b"SYNTHETIC_PUBLISHER_ARCHIVE_REFERENCE")
            first = PUBLISHER.build_manifest(root, release, ENV)
            expected = digest(canonical([{"path": name, "sha256": digest((root / "deploy" / name).read_bytes())} for name in CURRENT18]))
            self.assertEqual(first["guardFingerprint"], expected)
            policy = root / "deploy/runtime-ai-policy.json"; policy.write_bytes(policy.read_bytes() + b" ")
            second = PUBLISHER.build_manifest(root, release, ENV)
            self.assertNotEqual(first["guardFingerprint"], second["guardFingerprint"], "Even logically equal policy bytes are immutable guard input")

    def test_DX04_current_publisher_refuses_missing_and_malformed_policy_artifact(self):
        for fault in ("missing", "unknown-protocol", "unknown-field"):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(prefix="issue131-publisher-invalid-") as tmp:
                root = Path(tmp); shutil.copytree(ROOT, root / "deploy", ignore=shutil.ignore_patterns("tests", "compiled", "__pycache__"))
                shutil.copytree(ROOT.parent / "src/main/resources/db/migration", root / "src/main/resources/db/migration")
                release = root / "release.tar.gz"; release.write_bytes(b"SYNTHETIC_REFERENCE")
                policy = root / "deploy/runtime-ai-policy.json"
                if fault == "missing": policy.unlink()
                else: policy.write_bytes(POLICY.replace(b"meetme-managed-ai-policy-v1", b"unknown") if fault == "unknown-protocol" else POLICY.replace(b'"UNCONFIGURED"', b'"UNCONFIGURED","verified":true'))
                with self.assertRaises(ValueError): PUBLISHER.build_manifest(root, release, ENV)

    def test_DX04_current_eighteen_archive_is_verified_and_declares_required_auth(self):
        with tempfile.TemporaryDirectory() as tmp:
            packed, manifest = archive(Path(tmp), CURRENT18)
            try: required = DRIVER._verify_package(packed, manifest)
            except Exception as failure: self.fail("Approved current eighteen-file artifact must verify: " + type(failure).__name__)
            self.assertIs(required, True)

    def test_DX04_historical_seventeen_archive_keeps_its_exact_bytes_and_auth_declaration(self):
        with tempfile.TemporaryDirectory() as tmp:
            packed, manifest = archive(Path(tmp), AUTH17)
            self.assertIs(DRIVER._verify_package(packed, manifest), True)

    def test_DX04_historical_fourteen_archive_does_not_gain_new_auth_requirements(self):
        with tempfile.TemporaryDirectory() as tmp:
            packed, manifest = archive(Path(tmp), LEGACY14)
            self.assertIs(DRIVER._verify_package(packed, manifest), False)

    def test_DX02_actual_main_propagates_verified_current_target_auth_to_initial_and_fresh_readiness(self):
        self.main_case(CURRENT18, True)

    def test_DX02_actual_main_propagates_historical_required_auth_to_both_readiness_calls(self):
        self.main_case(AUTH17, True)

    def test_DX02_actual_main_preserves_legacy_target_auth_for_initial_and_fresh_readiness(self):
        self.main_case(LEGACY14, False)

    def main_case(self, contract, expected_auth):
        with tempfile.TemporaryDirectory(prefix="issue131-main-target-") as temporary:
            root = Path(temporary); packed, manifest = archive(root, contract)
            event = root / "event.json"; event.write_text("{}")
            manifest_path = root / "manifest.json"; manifest_path.write_text(json.dumps(manifest))
            def download(bucket, key, output, maximum): shutil.copyfile(manifest_path if key.endswith("manifest.json") else packed, output)
            facts = {"history": [], "phase": "READY", "currentImage": IMAGE, "guardTrusted": True,
                     "automaticContract": "input_revision_v8", "refreshRuleStates": ["ENABLED", "ENABLED"], "pendingCommands": 0}
            approved = SimpleNamespace(validate_manifest=lambda *args: {"imageRef": IMAGE}, validate_deploy_facts=lambda *args: {"mode": "AUTO_READY"})
            environment = ENV | {"RELEASE_ACTION": "deploy", "APPROVE_FIRST_TRANSITION": "false", "GITHUB_EVENT_PATH": str(event),
                "GITHUB_REPOSITORY": "meet-me-duo/meet-me-server", "GITHUB_REF": "refs/heads/main", "GITHUB_EVENT_NAME": "workflow_dispatch",
                "RUNTIME_INSTANCE_ID": "i-" + "a" * 17, "AWS_REGION": "ap-northeast-2", "DEPLOYMENT_ARTIFACT_BUCKET": "synthetic-bucket",
                "OPENAI_API_KEY": "", "GEMINI_API_KEY": ""}
            old_directory = Path.cwd(); old_umask = os.umask(0o077)
            try:
                os.chdir(root)
                with patch.dict(os.environ, environment, clear=True), patch.object(DRIVER.sys, "argv", ["synthetic-driver"]), \
                     patch.object(DRIVER, "validate_workflow_event"), patch.object(DRIVER, "_verify_origin"), \
                     patch.object(DRIVER, "_download", side_effect=download), patch.object(DRIVER, "_load", return_value=approved), \
                     patch.object(DRIVER, "_collect_facts", return_value=(facts, True)) as collect, \
                     patch.object(DRIVER, "_ssm", return_value={"currentImage": IMAGE, "sourceSha": SHA}), contextlib.redirect_stdout(io.StringIO()):
                    try: DRIVER.main()
                    except Exception as failure: self.fail("Isolated real main must complete its synthetic deploy decision: " + type(failure).__name__)
                self.assertEqual(collect.call_count, 2)
                for call in collect.call_args_list:
                    actual = call.kwargs.get("auth_required", call.args[3] if len(call.args) > 3 else False)
                    self.assertIs(actual, expected_auth, "Both fact reads must use the verified target declaration")
            finally:
                os.chdir(old_directory); os.umask(old_umask)


if __name__ == "__main__": unittest.main()
