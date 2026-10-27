"""Exercise the maintenance JDBC-only probe with a synthetic local filesystem.

The embedded Python is the real remote reader. Docker is a strict mock; its
arguments and temporary env file are observed without executing any container.
Only the fixed host-root literal and logical owner/ancestor metadata are mapped.
"""
import base64
import contextlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
IMAGE = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/meet-me/server@sha256:" + "b" * 64
PASSWORD = "synthetic-probe-password-never-log"


def production_reader():
    source = (ROOT / "preflight/host-preflight.sh").read_text()
    marker = 'flyway=$(python3 - "$1" "$maintenance_image" "$host_metadata" <<\'PY\'\n'
    if source.count(marker) != 1:
        raise AssertionError("The explicit maintenance reader boundary must remain unique")
    return source.split(marker, 1)[1].split("\nPY\n)", 1)[0]


@unittest.skipUnless(os.name == "posix", "Linux reader requires nofollow and POSIX metadata")
class Issue141StoppedProbeTests(unittest.TestCase):
    def invoke(self, fault=None):
        with tempfile.TemporaryDirectory(prefix="issue141-probe-") as temporary:
            host = Path(temporary) / "host"
            release = host / "releases" / ("a" * 40 + "-141-1")
            release.mkdir(parents=True)
            environment = release / ".env.runtime"
            environment.write_text("DATABASE_URL=jdbc:postgresql://synthetic/meetme\nDATABASE_USERNAME=synthetic\n"
                                   "DATABASE_PASSWORD=" + PASSWORD + "\nUNRELATED_SECRET=must-not-be-forwarded\n")
            environment.chmod(0o600)
            if fault == "duplicate-key":
                with environment.open("a") as stream:
                    stream.write("DATABASE_PASSWORD=duplicate\n")
            elif fault == "bad-mode":
                environment.chmod(0o644)
            elif fault == "symlink":
                target = release / "elsewhere"
                environment.rename(target)
                environment.symlink_to(target)
            metadata = {"containerBefore": {"present": True, "running": False, "pid": 0},
                        "phase": "V8_STARTED", "minimum-contract": "input_revision_v8",
                        "currentRelease": str(release)}
            if fault == "running":
                metadata["containerBefore"]["running"] = True
            elif fault == "pid":
                metadata["containerBefore"]["pid"] = 99
            elif fault == "marker":
                metadata["minimum-contract"] = "unknown"
            elif fault == "phase":
                metadata["phase"] = "READY"

            original_lstat = Path.lstat
            original_fstat = os.fstat

            def owner0(info):
                fields = list(info)
                fields[4] = 0
                return os.stat_result(fields)

            def logical_lstat(path, *args, **kwargs):
                info = owner0(original_lstat(path, *args, **kwargs))
                if stat.S_ISDIR(info.st_mode):
                    fields = list(info)
                    fields[0] &= ~0o022
                    return os.stat_result(fields)
                return info

            calls = []
            forwarded = None

            def docker(arguments, **kwargs):
                nonlocal forwarded
                calls.append(list(arguments))
                if arguments[:2] == ["docker", "rm"]:
                    return subprocess.CompletedProcess(arguments, 0, "", "")
                self.assertEqual(arguments[:2], ["docker", "run"])
                self.assertEqual(arguments[arguments.index("--entrypoint") + 1], "timeout")
                self.assertIn(IMAGE, arguments)
                self.assertIn("--pull=never", arguments)
                self.assertIn("--read-only", arguments)
                self.assertIn("--cap-drop=ALL", arguments)
                self.assertIn("--security-opt=no-new-privileges", arguments)
                self.assertEqual(arguments[arguments.index("--user") + 1], "10001:10001")
                self.assertIn("-Dloader.main=ReadOnlyFlywayProbe", arguments)
                self.assertNotIn("migrate", arguments)
                self.assertNotIn(PASSWORD, " ".join(arguments))
                mount = arguments[arguments.index("--mount") + 1]
                classes = Path(next(part[4:] for part in mount.split(",") if part.startswith("src=")))
                self.assertEqual(stat.S_IMODE(classes.stat().st_mode), 0o755,
                                 "The mounted directory must remain traversable by UID10001 even under umask077")
                self.assertEqual(stat.S_IMODE((classes / "ReadOnlyFlywayProbe.class").stat().st_mode), 0o644)
                env_path = Path(arguments[arguments.index("--env-file") + 1])
                self.assertEqual(stat.S_IMODE(env_path.stat().st_mode), 0o600)
                forwarded = env_path.read_text()
                self.assertEqual(set(line.partition("=")[0] for line in forwarded.splitlines()),
                                 {"DATABASE_URL", "DATABASE_USERNAME", "DATABASE_PASSWORD"})
                self.assertNotIn("UNRELATED_SECRET", forwarded)
                if fault == "timeout":
                    raise subprocess.TimeoutExpired(arguments, 40)
                response = {"status": "success", "database": "meetme", "serverVersion": "18", "history": []}
                if fault == "wrong-database":
                    response["database"] = "another-database"
                elif fault == "failed-probe":
                    response = {"status": "unavailable", "reason": "SQL_08006"}
                output = "invalid-json" if fault == "invalid-json" else json.dumps(response)
                return subprocess.CompletedProcess(arguments, 1 if fault == "docker-failure" else 0,
                                                   output, PASSWORD)

            output = io.StringIO()
            reader = production_reader().replace("/opt/meet-me", str(host))
            with patch.object(Path, "lstat", logical_lstat), \
                 patch.object(os, "fstat", side_effect=lambda fd: owner0(original_fstat(fd))), \
                 patch.object(subprocess, "run", side_effect=docker), \
                 patch.object(sys, "argv", ["reader", base64.b64encode(bytes.fromhex("cafebabe")).decode(),
                                             IMAGE, json.dumps(metadata)]), \
                 contextlib.redirect_stdout(output):
                exec(compile(reader, "host-preflight-maintenance-reader", "exec"), {})
            printed = output.getvalue()
            self.assertNotIn(PASSWORD, printed)
            self.assertNotIn("UNRELATED_SECRET", printed)
            return json.loads(printed), calls, forwarded

    def test_standalone_reader_forwards_only_database_credentials_and_uses_the_exact_image(self):
        result, calls, forwarded = self.invoke()
        self.assertEqual(result["status"], "success")
        self.assertEqual(result["database"], "meetme")
        self.assertEqual(sum(call[:2] == ["docker", "run"] for call in calls), 1)
        self.assertEqual(calls[-1][:3], ["docker", "rm", "-f"])
        self.assertIn(PASSWORD, forwarded)
        self.assertTrue(all(call[:2] in (["docker", "run"], ["docker", "rm"]) for call in calls))

    def test_invalid_maintenance_state_or_secret_metadata_never_starts_even_the_probe(self):
        for fault in ("running", "pid", "phase", "marker", "bad-mode", "duplicate-key", "symlink"):
            with self.subTest(fault=fault):
                result, calls, _ = self.invoke(fault)
                self.assertEqual(result["status"], "unavailable")
                self.assertEqual(calls, [])

    def test_uid10001_can_traverse_probe_bind_after_actual_maintenance_umask077(self):
        previous_umask = os.umask(0o077)
        try:
            result, _, _ = self.invoke()
        finally:
            os.umask(previous_umask)
        self.assertEqual(result["status"], "success")

    def test_probe_failure_wrong_database_and_timeout_fail_closed_and_cleanup_only_the_probe(self):
        for fault in ("wrong-database", "failed-probe", "invalid-json", "docker-failure", "timeout"):
            with self.subTest(fault=fault):
                result, calls, _ = self.invoke(fault)
                self.assertEqual(result["status"], "unavailable")
                self.assertEqual(calls[-1][:3], ["docker", "rm", "-f"])
                run = next(call for call in calls if call[:2] == ["docker", "run"])
                self.assertEqual(calls[-1][-1], run[run.index("--name") + 1])
                self.assertNotEqual(calls[-1][-1], "meet-me-app")


if __name__ == "__main__":
    unittest.main()
