"""Issue 141: artifact executable permissions are an actual POSIX boundary.

The end-to-end packaging check uses Git's index (including staged mode changes),
GNU tar and an actual umask. It never executes an archived runtime helper.
"""
import importlib.util
import io
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("issue141_artifact_driver", ROOT / "deploy/pipeline/run-approved-release.py")
DRIVER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DRIVER)
HELPERS = ("scripts/render-runtime-env.sh", "scripts/export-certificate.sh")
PACKAGE_MEMBERS = (
    "compose.production.yml", "compose.guard.yml", "meet-me-guarded-restart.service",
    "container-entrypoint.sh", "runtime-provider-mode", "runtime-auth-mode", "runtime-ai-policy.json",
    "nginx", "scripts", "pipeline", "preflight", "migrations",
)


def synthetic_archive(path, bad_helper=None, mode=0o755, missing=False, symlink=False):
    with tarfile.open(path, "w:gz") as archive:
        for name in HELPERS:
            if name == bad_helper and missing:
                continue
            member = tarfile.TarInfo(name)
            member.mode = mode if name == bad_helper else 0o755
            body = b"#!/usr/bin/env bash\nexit 0\n"
            if name == bad_helper and symlink:
                member.type = tarfile.SYMTYPE
                member.linkname = "/outside"
                archive.addfile(member)
            else:
                member.size = len(body)
                archive.addfile(member, io.BytesIO(body))


class Issue141ArchiveValidationTests(unittest.TestCase):
    def test_regular_owner_executable_helpers_are_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "release.tar.gz"
            synthetic_archive(archive)
            DRIVER.validate_executable_helpers(archive)

    def test_each_helper_must_exist_and_have_its_owner_execute_bit(self):
        for helper in HELPERS:
            for mode in (0o000, 0o600, 0o644, 0o077):
                with self.subTest(helper=helper, mode=oct(mode)), tempfile.TemporaryDirectory() as temporary:
                    archive = Path(temporary) / "release.tar.gz"
                    synthetic_archive(archive, helper, mode=mode)
                    with self.assertRaises(ValueError):
                        DRIVER.validate_executable_helpers(archive)
            for fault in ("missing", "symlink"):
                with self.subTest(helper=helper, fault=fault), tempfile.TemporaryDirectory() as temporary:
                    archive = Path(temporary) / "release.tar.gz"
                    synthetic_archive(archive, helper, **{fault: True})
                    with self.assertRaises(ValueError):
                        DRIVER.validate_executable_helpers(archive)

    def test_package_consumption_checks_executable_metadata_before_trusting_content(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "release.tar.gz"
            synthetic_archive(archive)
            with patch.object(DRIVER, "validate_executable_helpers", side_effect=ValueError("NONEXECUTABLE_HELPER")) as check:
                with self.assertRaisesRegex(ValueError, "NONEXECUTABLE_HELPER"):
                    DRIVER._verify_package(archive, {})
                check.assert_called_once_with(archive)


@unittest.skipUnless(os.name == "posix", "Real GNU tar and POSIX mode checks run on Linux CI/WSL")
class Issue141ActualArtifactModeTests(unittest.TestCase):
    def test_git_index_to_workflow_archive_to_umask077_preserves_helper_executability(self):
        self.assertIsNotNone(shutil.which("git"), "Git is required for the actual artifact test")
        self.assertIsNotNone(shutil.which("tar"), "GNU tar is required for the actual artifact test")
        git = lambda *args: subprocess.run(["git", "-C", str(ROOT), *args], check=True,
                                          capture_output=True, text=True).stdout.strip()
        for helper in HELPERS:
            indexed = git("ls-files", "--stage", "--", "deploy/" + helper)
            self.assertTrue(indexed.startswith("100755 "), "The Git index must preserve owner execute: " + helper)
        tree = git("write-tree")
        with tempfile.TemporaryDirectory(prefix="issue141-real-artifact-") as temporary:
            directory = Path(temporary)
            source = directory / "source.tar"
            subprocess.run(["git", "-C", str(ROOT), "archive", "--format=tar", "--output", str(source), tree, "deploy",
                            "src/main/resources/db/migration"], check=True, capture_output=True)
            checkout = directory / "checkout"
            checkout.mkdir()
            subprocess.run(["tar", "-xf", str(source), "-C", str(checkout)], check=True, capture_output=True,
                           preexec_fn=lambda: os.umask(0o022))
            deploy = checkout / "deploy"
            migrations = deploy / "migrations"
            migrations.mkdir()
            for sql in (checkout / "src/main/resources/db/migration").glob("*.sql"):
                shutil.copyfile(sql, migrations / sql.name)
            package = directory / "release.tar.gz"
            subprocess.run(["tar", "-czf", str(package), "-C", str(deploy), *PACKAGE_MEMBERS],
                           check=True, capture_output=True, preexec_fn=lambda: os.umask(0o077))
            with tarfile.open(package, "r:gz") as archive:
                for helper in HELPERS:
                    self.assertTrue(archive.getmember(helper).mode & stat.S_IXUSR)
            release = directory / "release"
            release.mkdir(mode=0o700)
            subprocess.run(["tar", "-xzf", str(package), "--no-same-owner", "--no-same-permissions", "-C", str(release)],
                           check=True, capture_output=True, preexec_fn=lambda: os.umask(0o077))
            for helper in HELPERS:
                metadata = (release / helper).lstat()
                self.assertTrue(stat.S_ISREG(metadata.st_mode))
                self.assertEqual(0o700, stat.S_IMODE(metadata.st_mode), helper)
                self.assertTrue(os.access(release / helper, os.X_OK), helper)
            DRIVER.validate_executable_helpers(package)


if __name__ == "__main__":
    unittest.main()
