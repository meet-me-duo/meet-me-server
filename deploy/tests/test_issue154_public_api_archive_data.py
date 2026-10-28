"""Independent archive requirement for readonly API DATA, separate from guard compatibility."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("issue154_public_data_driver", ROOT / "pipeline/run-approved-release.py")
DRIVER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DRIVER)
GUARD23 = tuple(sorted((
    "scripts/host-release-guard.sh", "scripts/install-host-release-guard.sh", "scripts/deploy-release.sh",
    "scripts/rollback-release.sh", "scripts/refresh-database-credential.sh", "compose.guard.yml",
    "meet-me-guarded-restart.service", "pipeline/approved_release.py", "pipeline/host-approved-release.py",
    "pipeline/run-approved-release.py", "pipeline/publish_manifest.py", "preflight/host-preflight.sh",
    "preflight/run-preflight.py", "preflight/ReadOnlyFlywayProbe.java", "runtime-auth-mode",
    "scripts/render-runtime-env.sh", "compose.production.yml", "runtime-ai-policy.json",
    "pipeline/release_completion.py", "pipeline/transition_orchestrator.py", "pipeline/approval_gate.py",
    "pipeline/release_pipeline.py", "transition-approval.json",
)))


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def files():
    result = {name: (ROOT / name).read_bytes() for name in GUARD23}
    result.update({"public-api-contract.json": (ROOT / "public-api-contract.json").read_bytes(),
                   "preflight/compiled/ReadOnlyFlywayProbe.class": bytes.fromhex("cafebabe"),
                   "runtime-provider-mode": b"gemini-luna-required\n",
                   "scripts/export-certificate.sh": (ROOT / "scripts/export-certificate.sh").read_bytes()})
    return result


def packed(directory, contents):
    path = directory / "release.tar.gz"
    with tarfile.open(path, "w:gz") as archive:
        for name, raw in contents.items():
            item = tarfile.TarInfo(name)
            item.size = len(raw)
            item.mode = 0o755 if name in ("scripts/render-runtime-env.sh", "scripts/export-certificate.sh") else 0o644
            archive.addfile(item, io.BytesIO(raw))
    return path, {"archiveSha256": digest(path.read_bytes()), "migrations": [],
                  "schemaFingerprint": digest(canonical([])),
                  "guardFingerprint": digest(canonical([{"path": name, "sha256": digest(contents[name])}
                                                        for name in GUARD23]))}


class Issue154PublicApiArchiveDataTests(unittest.TestCase):
    def test_complete_new_archive_requires_data_without_changing_twenty_three_guard_inventory(self):
        self.assertEqual(23, len(GUARD23))
        self.assertNotIn("public-api-contract.json", GUARD23)
        with tempfile.TemporaryDirectory() as temporary:
            archive, manifest = packed(Path(temporary), files())
            self.assertIs(DRIVER._verify_package(archive, manifest), True)

    def test_omitted_public_data_is_rejected_even_when_all_guard_bytes_and_archive_hash_match(self):
        contents = files()
        contents.pop("public-api-contract.json")
        with tempfile.TemporaryDirectory() as temporary:
            archive, manifest = packed(Path(temporary), contents)
            with self.assertRaisesRegex(ValueError, "^UNTRUSTED_RELEASE_STATE$"):
                DRIVER._verify_package(archive, manifest)

    def test_public_data_is_archive_integrity_input_without_becoming_guard_compatibility_input(self):
        contents = files()
        with tempfile.TemporaryDirectory() as temporary:
            archive, before = packed(Path(temporary), contents)
            self.assertIs(DRIVER._verify_package(archive, before), True)
            contents["public-api-contract.json"] += b"\n"
            archive, after = packed(Path(temporary), contents)
            self.assertNotEqual(before["archiveSha256"], after["archiveSha256"])
            self.assertEqual(before["guardFingerprint"], after["guardFingerprint"])
            self.assertIs(DRIVER._verify_package(archive, after), True)


if __name__ == "__main__":
    unittest.main()
