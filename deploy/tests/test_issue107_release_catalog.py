"""#107 independent immutable catalog/approval tests. Offline synthetic metadata only; no host or AWS mutation."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zlib

ROOT = Path(__file__).resolve().parents[2]


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


publisher = load("issue107_publisher", "deploy/pipeline/publish_manifest.py")
approval = load("issue107_approval", "deploy/pipeline/approved_release.py")
host = load("issue107_host", "deploy/pipeline/host-approved-release.py")
VERSIONS = list(range(1, 11)) + [13]
REPOSITORY = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/meet-me"
SOURCE = "a" * 40
ENVIRONMENT = {
    "SOURCE_SHA": SOURCE, "BUILD_RUN_ID": "12345", "BUILD_RUN_ATTEMPT": "2",
    "CI_RUN_ID": "67890", "CI_SHA": SOURCE, "ECR_REPOSITORY_URL": REPOSITORY,
    "IMAGE_DIGEST": "sha256:" + "b" * 64,
}
OLD_CHECKSUMS = [751905489, -717392933, -1899585498, 1674511551, -809392941,
                 1476091850, -273770541, 79860964, 1241531971, 40203105]


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def rows(versions=VERSIONS):
    return [{"version": str(v), "script": f"V{v}__fixture.sql", "checksum": v * 37} for v in versions]


def manifest(versions=VERSIONS):
    return {
        "protocol": "meet-me-approved-release-v1", "sourceSha": SOURCE,
        "buildRunId": "12345", "buildRunAttempt": "2", "imageRepository": REPOSITORY,
        "imageDigest": ENVIRONMENT["IMAGE_DIGEST"], "releaseId": SOURCE + "-12345-2",
        "archiveSha256": "c" * 64, "schemaFingerprint": "d" * 64, "guardFingerprint": "e" * 64,
        "migrations": rows(versions),
        "origin": {"repository": "meet-me-duo/meet-me-server", "ref": "refs/heads/main", "workflow": "CI",
                   "event": "push", "conclusion": "success", "ciRunId": "67890", "ciSha": SOURCE},
    }


def expected():
    return {key: manifest()[key] for key in ("buildRunId", "buildRunAttempt", "imageRepository")}


def history(value):
    return [dict(row, success=True) for row in value["migrations"]]


def record(value):
    return {"protocol": "meet-me-automatic-release-v1", "schemaFingerprint": value["schemaFingerprint"],
            "guardFingerprint": value["guardFingerprint"], "history": history(value),
            "currentImage": REPOSITORY + "@sha256:" + "f" * 64,
            "sourceSha": "9" * 40, "buildRunId": "100", "buildRunAttempt": "1"}


def ready(value):
    contract = record(value)
    return {"history": history(value), "phase": "READY", "currentImage": contract["currentImage"],
            "guardTrusted": True, "automaticContract": contract,
            "refreshRuleStates": ["ENABLED", "ENABLED"], "pendingCommands": 0}


class ReleaseCatalogContractTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="meetme107-catalog-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.sql = self.root / "src/main/resources/db/migration"
        self.sql.mkdir(parents=True)
        for relative in publisher.GUARD_FILES:
            target = self.root / "deploy" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / "deploy" / relative, target)
        self.archive = self.root / "release.tar.gz"
        self.archive.write_bytes(b"synthetic immutable archive bytes")

    def add(self, version, raw=None, name=None):
        target = self.sql / (name or f"V{version}__fixture.sql")
        target.write_bytes(raw if raw is not None else f"-- synthetic {version}\nSELECT {version};\n".encode())
        return target

    def base(self, include13=True):
        for version in reversed(range(1, 11)):
            source = next((ROOT / "src/main/resources/db/migration").glob(f"V{version}__*.sql"))
            self.add(version, source.read_bytes(), source.name)
        if include13:
            self.add(13)

    def build(self):
        return publisher.build_manifest(self.root, self.archive, ENVIRONMENT)

    def reject_build(self):
        with self.assertRaisesRegex(ValueError, "^UNTRUSTED_RELEASE$"):
            self.build()

    def test_publisher_numeric_order_and_gap_preserve_all_ten_existing_checksums(self):
        self.base()
        value = self.build()
        self.assertEqual([str(v) for v in VERSIONS], [r["version"] for r in value["migrations"]])
        self.assertEqual(OLD_CHECKSUMS, [r["checksum"] for r in value["migrations"][:10]])
        self.assertEqual(value, {k: v for k, v in approval.validate_manifest(value, expected()).items() if k != "imageRef"})

    def test_v10_manifest_remains_byte_compatible_and_v13_changes_exact_catalog_fingerprint(self):
        self.base(include13=False)
        old = self.build()
        catalog = [{"version": str(v), "script": next(self.sql.glob(f"V{v}__*.sql")).name,
                    "sha256": hashlib.sha256(next(self.sql.glob(f"V{v}__*.sql")).read_bytes()).hexdigest()}
                   for v in range(1, 11)]
        self.assertEqual(hashlib.sha256(canonical(catalog)).hexdigest(), old["schemaFingerprint"])
        extra = self.add(13)
        new = self.build()
        catalog.append({"version": "13", "script": extra.name, "sha256": hashlib.sha256(extra.read_bytes()).hexdigest()})
        self.assertEqual(hashlib.sha256(canonical(catalog)).hexdigest(), new["schemaFingerprint"])
        self.assertNotEqual(old["schemaFingerprint"], new["schemaFingerprint"])
        self.assertEqual(old["migrations"], new["migrations"][:10])
        extra.write_bytes(extra.read_bytes() + b"-- exact new bytes\n")
        changed = self.build()
        self.assertNotEqual(new["schemaFingerprint"], changed["schemaFingerprint"])
        self.assertEqual(new["guardFingerprint"], changed["guardFingerprint"])

    def test_publisher_accepts_sparse_high_versions_and_sorts_numerically(self):
        self.base()
        self.add(100)
        self.add(20)
        self.add(11)
        self.assertEqual([str(v) for v in list(range(1, 12)) + [13, 20, 100]],
                         [r["version"] for r in self.build()["migrations"]])

    def test_publisher_rejects_duplicate_noncanonical_and_missing_prefix_versions(self):
        self.base()
        self.add(13, name="V13__duplicate.sql")
        self.reject_build()
        (self.sql / "V13__duplicate.sql").unlink()
        for name in ("V013__leading_zero.sql", "V0__zero.sql", "V-1__negative.sql", "V13.1__decimal.sql",
                     "Vx__not_numeric.sql", "v14__lowercase.sql", "V14_bad.sql", "V14__.sql", "V14__bad-name.sql", "V14__case.SQL"):
            with self.subTest(name=name):
                path = self.add(14, name=name)
                self.reject_build()
                path.unlink()
        next(self.sql.glob("V5__*.sql")).unlink()
        self.reject_build()

    def test_publisher_rejects_empty_catalog_and_symlink_or_directory_migrations(self):
        self.reject_build()
        self.base()
        original = self.sql / "V13__fixture.sql"
        original.unlink()
        outside = self.root / "outside.sql"
        outside.write_text("SELECT 13;\n")
        original.symlink_to(outside)
        self.reject_build()
        original.unlink()
        original.mkdir()
        self.reject_build()
        original.rmdir()
        self.add(13)
        moved = self.root / "moved-migrations"
        self.sql.rename(moved)
        self.sql.symlink_to(moved, target_is_directory=True)
        self.reject_build()

    def test_catalog_count_limit_is_256_not_version_ceiling(self):
        self.base(include13=False)
        for version in range(13, 259):
            self.add(version)
        value = self.build()
        self.assertEqual(256, len(value["migrations"]))
        self.assertEqual("258", value["migrations"][-1]["version"])
        approval.validate_manifest(value, expected())
        self.add(1000)
        self.reject_build()

    def test_flyway_v10_checksum_bytes_keep_bom_newline_and_signed_crc_semantics(self):
        raw = b"\xef\xbb\xbf-- synthetic\r\nSELECT 13;\r\n"
        expected_crc = zlib.crc32(b"-- syntheticSELECT 13;")
        expected_signed = expected_crc if expected_crc < 2 ** 31 else expected_crc - 2 ** 32
        self.assertEqual(expected_signed, publisher.flyway_checksum(raw))

    def test_validator_preserves_v10_accepts_gap13_and_rejects_noncanonical_order_and_cardinality(self):
        for versions in (list(range(1, 11)), VERSIONS, list(range(1, 11)) + list(range(13, 259))):
            value = manifest(versions)
            self.assertEqual(value["migrations"], approval.validate_manifest(value, expected())["migrations"])
        invalid = []
        for versions in ([], list(range(1, 10)), [1, 2, 3, 4, 6, 7, 8, 9, 10, 13], VERSIONS[::-1], VERSIONS + [13],
                         list(range(1, 11)) + list(range(13, 260))):
            invalid.append(manifest(versions))
        for version in ("013", "0", "-1", "13.0", 13, True):
            value = manifest()
            value["migrations"][-1]["version"] = version
            invalid.append(value)
        for script in ("V013__fixture.sql", "V14__fixture.sql", "../V13__fixture.sql", "V13__bad-name.sql"):
            value = manifest()
            value["migrations"][-1]["script"] = script
            invalid.append(value)
        for checksum in (True, "481", 2 ** 31, -(2 ** 31) - 1):
            value = manifest()
            value["migrations"][-1]["checksum"] = checksum
            invalid.append(value)
        for value in invalid:
            with self.subTest(rows=value["migrations"]):
                with self.assertRaisesRegex(ValueError, "^UNTRUSTED_RELEASE$"):
                    approval.validate_manifest(value, expected())

    def test_old_approved_v10_contract_requires_explicit_transition_to_v13(self):
        old = manifest(list(range(1, 11)))
        new = manifest()
        new["schemaFingerprint"] = "1" * 64
        new["guardFingerprint"] = "2" * 64
        facts = ready(old)
        for action in ("automatic", "preflight"):
            self.assertEqual({"mode": "TRANSITION_REQUIRED"}, approval.validate_deploy_facts(new, facts, action))
        with self.assertRaisesRegex(ValueError, "^UNSAFE_RELEASE_STATE$"):
            approval.validate_deploy_facts(new, facts, "deploy", False)
        with self.assertRaisesRegex(ValueError, "^UNSAFE_RELEASE_STATE$"):
            approval.validate_deploy_facts(new, facts, "deploy", True)
        facts["refreshRuleStates"] = ["DISABLED", "DISABLED"]
        self.assertEqual({"mode": "TRANSITION_APPROVED"}, approval.validate_deploy_facts(new, facts, "deploy", True))
        facts["pendingCommands"] = 1
        with self.assertRaisesRegex(ValueError, "^UNSAFE_RELEASE_STATE$"):
            approval.validate_deploy_facts(new, facts, "deploy", True)

    def test_new_complete_history_and_record_allow_auto_only_for_exact_fingerprints_and_rows(self):
        value = manifest()
        facts = ready(value)
        for action in ("automatic", "preflight", "deploy"):
            self.assertEqual({"mode": "AUTO_READY"}, approval.validate_deploy_facts(value, facts, action))
        for field in ("schemaFingerprint", "guardFingerprint"):
            tampered = copy.deepcopy(facts)
            tampered["automaticContract"][field] = "0" * 64
            self.assertEqual({"mode": "TRANSITION_REQUIRED"}, approval.validate_deploy_facts(value, tampered, "automatic"))
        for key in ("history", "automaticContract"):
            tampered = copy.deepcopy(facts)
            target = tampered["history"] if key == "history" else tampered["automaticContract"]["history"]
            target[-1]["checksum"] += 1
            if key == "history":
                with self.assertRaisesRegex(ValueError, "^UNSAFE_RELEASE_STATE$"):
                    approval.validate_deploy_facts(value, tampered, "automatic")
            else:
                self.assertEqual({"mode": "TRANSITION_REQUIRED"}, approval.validate_deploy_facts(value, tampered, "automatic"))
        missing = copy.deepcopy(facts)
        missing["history"] = missing["history"][:-1]
        self.assertEqual({"mode": "TRANSITION_REQUIRED"}, approval.validate_deploy_facts(value, missing, "automatic"))

    def test_history_must_be_exact_successful_prefix_and_never_invent_gap_versions(self):
        value = manifest()
        for length in (7, 8, 9, 10, 11):
            facts = ready(value)
            facts["history"] = history(value)[:length]
            facts["automaticContract"] = None
            facts["refreshRuleStates"] = ["DISABLED", "DISABLED"]
            self.assertEqual({"mode": "TRANSITION_APPROVED"}, approval.validate_deploy_facts(value, facts, "deploy", True))
        for bad in (history(value)[:6], history(value)[:-1] + [dict(rows([11])[0], success=True)],
                    history(value)[::-1], history(value) + [dict(rows([13])[0], success=True)]):
            facts = ready(value)
            facts["history"] = bad
            with self.assertRaisesRegex(ValueError, "^UNSAFE_RELEASE_STATE$"):
                approval.validate_deploy_facts(value, facts, "automatic")

    def test_full_automatic_record_requires_all_first_ten_versions_not_only_history_minimum_seven(self):
        value = manifest()
        for missing in (8, 9, 10):
            contract = record(value)
            contract["history"] = [row for row in contract["history"] if row["version"] != str(missing)]
            facts = ready(value)
            facts["automaticContract"] = contract
            with self.subTest(missing=missing):
                with self.assertRaisesRegex(ValueError, "^UNSAFE_RELEASE_STATE$"):
                    approval.validate_deploy_facts(value, facts, "automatic")
                marker = self.root / "automatic-release-contract.json"
                marker.touch()
                with patch.object(host, "CONTRACT", marker), patch.object(host, "_bytes", return_value=canonical(contract)):
                    with self.assertRaises(ValueError):
                        host._record()

    def test_host_record_reader_accepts_v10_and_v13_contracts_without_host_mutation(self):
        marker = self.root / "automatic-release-contract.json"
        marker.touch()
        for versions in (list(range(1, 11)), VERSIONS):
            contract = record(manifest(versions))
            # Isolate record metadata parsing; real root ownership/permissions remain separate guard tests.
            with patch.object(host, "CONTRACT", marker), patch.object(host, "_bytes", return_value=canonical(contract)):
                self.assertEqual(contract, host._record())
        for bad in (history(manifest())[::-1], history(manifest()) + [dict(rows([13])[0], success=True)], history(manifest())[:9]):
            contract = record(manifest())
            contract["history"] = bad
            with patch.object(host, "CONTRACT", marker), patch.object(host, "_bytes", return_value=canonical(contract)):
                with self.assertRaises(ValueError):
                    host._record()


if __name__ == "__main__":
    unittest.main()
