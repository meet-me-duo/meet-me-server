"""Build immutable release metadata without external service calls."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import zlib

GUARD_FILES = tuple(sorted((
    "scripts/host-release-guard.sh", "scripts/install-host-release-guard.sh",
    "scripts/deploy-release.sh", "scripts/rollback-release.sh",
    "scripts/refresh-database-credential.sh", "compose.guard.yml",
    "meet-me-guarded-restart.service", "pipeline/approved_release.py",
    "pipeline/host-approved-release.py", "pipeline/run-approved-release.py",
    "pipeline/publish_manifest.py", "preflight/host-preflight.sh",
    "preflight/run-preflight.py", "preflight/ReadOnlyFlywayProbe.java",
)))


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


def build_manifest(repo_root, archive_path, environment):
    try:
        root = Path(repo_root)
        archive = Path(archive_path)
        if not archive.is_file() or archive.is_symlink():
            raise ValueError()
        migration_directory = root / "src/main/resources/db/migration"
        paths = list(migration_directory.glob("V*__*.sql"))
        rows, catalog = [], []
        if len(paths) != 10:
            raise ValueError()
        for version in range(1, 11):
            matches = [path for path in paths if re.fullmatch(rf"V{version}__[A-Za-z0-9_]+\.sql", path.name)]
            if len(matches) != 1 or matches[0].is_symlink():
                raise ValueError()
            path = matches[0]
            raw = path.read_bytes()
            rows.append({"version": str(version), "script": path.name, "checksum": flyway_checksum(raw)})
            catalog.append({"version": str(version), "script": path.name, "sha256": hashlib.sha256(raw).hexdigest()})
        guard_catalog = []
        for relative in GUARD_FILES:
            path = root / "deploy" / relative
            if not path.is_file() or path.is_symlink():
                raise ValueError()
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
    manifest = build_manifest(Path(__file__).resolve().parents[2], args.archive, os.environ)
    with Path(args.output).open("x", encoding="utf-8") as destination:
        destination.write(canonical_bytes(manifest).decode("utf-8") + "\n")
    print("Immutable release manifest prepared.")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("Release manifest unavailable; source or publication metadata validation failed.")
        sys.exit(2)
