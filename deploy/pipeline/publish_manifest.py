"""Build immutable release metadata without external service calls."""

import argparse
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import tarfile
import zlib

GUARD_FILES = tuple(sorted((
    "scripts/host-release-guard.sh", "scripts/install-host-release-guard.sh",
    "scripts/deploy-release.sh", "scripts/rollback-release.sh",
    "scripts/refresh-database-credential.sh", "compose.guard.yml",
    "meet-me-guarded-restart.service", "pipeline/approved_release.py",
    "pipeline/host-approved-release.py", "pipeline/run-approved-release.py",
    "pipeline/publish_manifest.py", "preflight/host-preflight.sh",
    "preflight/run-preflight.py", "preflight/ReadOnlyFlywayProbe.java",
    "runtime-auth-mode", "scripts/render-runtime-env.sh", "compose.production.yml",
    "runtime-ai-policy.json",
    "pipeline/release_completion.py", "pipeline/approval_gate.py",
    "pipeline/transition_orchestrator.py", "pipeline/release_pipeline.py", "transition-approval.json",
)))

MIGRATION_SCRIPTS = (
    'V1__create_core_domain.sql', 'V2__add_anonymous_room_lifecycle.sql',
    'V3__add_submission_gemini_pipeline.sql', 'V4__add_matching_results.sql',
    'V5__remove_meeting_duration.sql', 'V6__add_reliable_outbox_processing.sql',
    'V7__resume_delayed_matching_from_structuring.sql', 'V8__add_input_revision_rounds.sql',
    'V9__add_time_recommendations.sql', 'V10__add_bounded_analysis_invocations.sql',
    'V11__add_accounts_and_participant_ownership.sql', 'V12__add_guest_reaccess.sql',
    'V13__add_submission_clarifications.sql', 'V14__add_operator_access_audits.sql',
    'V15__add_parser_transport_observations.sql', 'V16__add_pre_submission_checks.sql',
    'V17__add_durable_ai_room_budgets.sql',
    'V18__align_recommendation_selection_attendance.sql',
    'V19__add_confirmed_result_reviews.sql',
)


def _require(value):
    if not value:
        raise ValueError("UNTRUSTED_RELEASE")


def _runtime_policy_bytes(raw):
    """Nonsecret reviewed artifact shape only; historical reading never adopts an operating policy or clock."""
    def unique(pairs):
        result = {}
        for key, value in pairs:
            _require(key not in result)
            result[key] = value
        return result
    _require(type(raw) is bytes and len(raw) <= 65536)
    data = json.loads(raw.decode("utf-8"), object_pairs_hook=unique)
    _require(type(data) is dict and data.get("protocol") == "meetme-managed-ai-policy-v1")
    if data.get("state") == "UNCONFIGURED":
        _require(set(data) == {"protocol", "state"})
        return
    _require(data.get("state") == "CONFIGURED"
             and set(data) == {"protocol", "state", "unit", "roomCap", "minimumConfidence", "operations"})
    unit = data["unit"]
    _require(type(unit) is dict and set(unit) == {"code", "scale"})
    code = unit["code"]
    _require(type(code) is str and bool(code.strip()) and code == code.strip()
             and len(code.encode("utf-16-le")) // 2 <= 32 and not any(ch in code for ch in ("\x00", "\r", "\n")))
    _require(type(unit["scale"]) is int and 0 <= unit["scale"] <= 38)
    amount = lambda value: type(value) is str and re.fullmatch(r"0|[1-9][0-9]{0,37}", value)
    _require(amount(data["roomCap"]) and int(data["roomCap"]) > 0)
    threshold = data["minimumConfidence"]
    _require(type(threshold) in (float, int) and 0 <= threshold <= 1)
    operations = data["operations"]
    expected = {
        "PRECHECK_CLASSIFICATION": "c8d01d4a04aa077e0488400dc3b4791753b8428db48c6712aa78f903608a4dfc",
        "PRECHECK_COUNT": "658a65563b34d43f854cc47dcd93d274d23789745e718b5f7ef49e1b59e8e307",
        "PRECHECK_GENERATION": "86e4593b78ec903e1c9b4bbbffe204d2b8343e1752d2b6353703ce3fb9b65714",
        "FINAL_PRIMARY": "0a3fc94ba00d368b4e7c9a41d0b231faab5058aa893dc11082ef786fc0a08d04",
        "FINAL_FALLBACK": "9c5021916c80178f58d235e55bbbad4889875560b710a505fbde5dedd1a824ab",
    }
    _require(type(operations) is list and len(operations) == len(expected))
    seen = set()
    for row in operations:
        _require(type(row) is dict and set(row) == {
            "operation", "profileFingerprint", "evidenceFingerprint", "maximumAmount", "validUntil"})
        _require(type(row["operation"]) is str and row["operation"] in expected and row["operation"] not in seen)
        seen.add(row["operation"])
        for key in ("profileFingerprint", "evidenceFingerprint"):
            _require(type(row[key]) is str and re.fullmatch(r"[0-9a-f]{64}", row[key]))
        _require(row["profileFingerprint"] == expected[row["operation"]])
        _require(amount(row["maximumAmount"]) and type(row["validUntil"]) is str
                 and re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?Z", row["validUntil"]))
        datetime.datetime.fromisoformat(row["validUntil"].replace("Z", "+00:00"))


def canonical_bytes(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def file_sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def flyway_checksum(raw):
    """Flyway 12.4 BufferedReader/readLine UTF-8 CRC32, including first-line BOM filtering."""
    try:
        text = raw.decode("utf-8-sig")
        checksum = zlib.crc32("".join(re.split(r"\r\n|\r|\n", text)).encode("utf-8"))
        return checksum if checksum < 2 ** 31 else checksum - 2 ** 32
    except (AttributeError, UnicodeError):
        raise ValueError("UNTRUSTED_RELEASE") from None


def source_path(root, relative, directory=False):
    """Reject source links before reading bytes, including every in-repository ancestor."""
    if not root.is_dir() or root.is_symlink():
        raise ValueError("UNTRUSTED_RELEASE")
    path = root
    parts = Path(relative).parts
    for index, part in enumerate(parts):
        path = path / part
        is_directory = index < len(parts) - 1 or directory
        if path.is_symlink() or not (path.is_dir() if is_directory else path.is_file()):
            raise ValueError("UNTRUSTED_RELEASE")
    return path


def build_manifest(repo_root, archive_path, environment):
    try:
        root = Path(repo_root)
        archive = Path(archive_path)
        if not archive.is_file() or archive.is_symlink():
            raise ValueError()
        if source_path(root, "deploy/runtime-auth-mode").read_bytes() not in (
                b"oauth-rs256-required", b"oauth-rs256-required\n"):
            raise ValueError()
        policy = source_path(root, "deploy/runtime-ai-policy.json")
        with policy.open("rb") as source:
            _runtime_policy_bytes(source.read(65537))
        api_path = source_path(root, "deploy/public-api-contract.json")
        api_spec = importlib.util.spec_from_file_location("publication_api_contract", source_path(root, "deploy/pipeline/release_pipeline.py"))
        api_validator = importlib.util.module_from_spec(api_spec)
        api_spec.loader.exec_module(api_validator)
        api_validator.validate_public_contract(api_validator.strict_json(api_path.read_bytes(), 131072))
        migration_directory = source_path(root, "src/main/resources/db/migration", directory=True)
        paths = list(migration_directory.iterdir())
        rows, catalog = [], []
        scripts = MIGRATION_SCRIPTS
        if {path.name for path in paths} != set(scripts):
            raise ValueError()
        for script in scripts:
            path = migration_directory / script
            version = script.split('__', 1)[0][1:]
            if not path.is_file() or path.is_symlink():
                raise ValueError()
            raw = path.read_bytes()
            rows.append({"version": str(version), "script": path.name, "checksum": flyway_checksum(raw)})
            catalog.append({"version": str(version), "script": path.name, "sha256": hashlib.sha256(raw).hexdigest()})
        guard_catalog = []
        for relative in GUARD_FILES:
            path = source_path(root, Path("deploy") / relative)
            guard_catalog.append({"path": relative, "sha256": file_sha256(path)})
        source_sha = environment["SOURCE_SHA"]
        run_id, attempt = environment["BUILD_RUN_ID"], environment["BUILD_RUN_ATTEMPT"]
        manifest = {
            "protocol": "meet-me-approved-release-v1", "sourceSha": source_sha,
            "buildRunId": run_id, "buildRunAttempt": attempt,
            "imageRepository": environment["ECR_REPOSITORY_URL"], "imageDigest": environment["IMAGE_DIGEST"],
            "releaseId": f"{source_sha}-{run_id}-{attempt}", "archiveSha256": file_sha256(archive),
            "schemaFingerprint": hashlib.sha256(canonical_bytes(catalog)).hexdigest(),
            "guardFingerprint": hashlib.sha256(canonical_bytes(guard_catalog)).hexdigest(), "migrations": rows,
            "origin": {"repository": "meet-me-duo/meet-me-server", "ref": "refs/heads/main", "workflow": "CI",
                       "event": "push", "conclusion": "success", "ciRunId": environment["CI_RUN_ID"],
                       "ciSha": environment["CI_SHA"]},
        }
        spec = importlib.util.spec_from_file_location("release_manifest_validation", root / "deploy/pipeline/approved_release.py")
        validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(validator)
        validator.validate_manifest(manifest, {key: manifest[key] for key in ("buildRunId", "buildRunAttempt", "imageRepository")})
        return manifest
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        raise ValueError("UNTRUSTED_RELEASE") from None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", default="release.tar.gz")
    parser.add_argument("--output", default="manifest.json")
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location(
        "publication_archive_validation", Path(__file__).with_name("run-approved-release.py"))
    archive_validator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(archive_validator)
    archive_validator.validate_archive(args.archive)
    archive_validator.validate_executable_helpers(args.archive)
    manifest = build_manifest(Path(__file__).resolve().parents[2], args.archive, os.environ)
    archive_validator._verify_package(args.archive, manifest)
    with tarfile.open(args.archive, "r:gz") as archive:
        expected = (Path(__file__).resolve().parent.parent / "public-api-contract.json").read_bytes()
        _require(archive.extractfile("public-api-contract.json").read(131073) == expected)
    with Path(args.output).open("x", encoding="utf-8") as destination:
        destination.write(canonical_bytes(manifest).decode("utf-8") + "\n")
    print("Immutable release manifest prepared.")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("Release manifest unavailable; source or publication metadata validation failed.")
        sys.exit(2)
