"""ADR-046 release boundaries against real scripts, with local side effects only.

AWS, Docker, secret rendering and certificate export are stubs. Root ownership
is represented by id/stat logical stubs; /proc uses a local PID namespace projection.
These do not prove real host permissions or operational installation. Actual bash,
symlinks, files, process descriptors and Linux flock are used in TemporaryDirectory.
Prerequisite records test syntax/dispatch refusal, never prove an operator paused AWS.
"""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import unittest

REPOSITORY = Path(__file__).resolve().parents[2]
OLD = "example.invalid/app@sha256:" + "1" * 64
NEW = "example.invalid/app@sha256:" + "2" * 64
NEXT = "example.invalid/app@sha256:" + "3" * 64
EVIDENCE = "a" * 64
LIFECYCLE = ("deploy-release.sh", "rollback-release.sh", "refresh-database-credential.sh")

STUB = r'''#!/usr/bin/env python3
import errno,fcntl,json,os,pathlib,subprocess,sys,time
root=pathlib.Path(os.environ['V8_TEST_ROOT']); state=root/'fake-state.json'
data=json.loads(state.read_text()); args=sys.argv[1:]; name=pathlib.Path(sys.argv[0]).name
lock=pathlib.Path(os.environ['MEETME_DEPLOY_LOCK'])
held=False
if lock.exists():
 with lock.open('a') as f:
  try:fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB);fcntl.flock(f,fcntl.LOCK_UN)
  except BlockingIOError:held=True
phase=root/'host/state/phase'; marker=root/'host/state/minimum-contract'
event={'command':name,'args':args,'image':os.environ.get('APP_IMAGE'),'lock_held':held,
 'running':data['running'],'marker':marker.read_text() if marker.exists() else None,
 'phase':phase.read_text() if phase.exists() else None}
with (root/'calls.jsonl').open('a') as f:f.write(json.dumps(event)+'\n')
if name=='flock':
 result=subprocess.run([os.environ['V8_REAL_FLOCK']]+args,pass_fds=(9,))
 if result.returncode:sys.exit(result.returncode)
 if os.environ.get('V8_TEST_ROLE')=='installer-barrier':
  (root/'installer-lock-acquired').touch()
  deadline=time.monotonic()+8
  while not (root/'release-installer-barrier').exists():
   if time.monotonic()>deadline:sys.exit(91)
   time.sleep(.01)
 sys.exit(0)
if name=='id':
 if args==['-u'] or args==['-g']:print('0');sys.exit(0)
 sys.exit(subprocess.run([os.environ['V8_REAL_ID']]+args).returncode)
if name=='stat':
 # Logical owner0 only: preserve actual mode, device/inode, filetype and filesystem behavior.
 args=[arg.replace('%u','0').replace('%g','0') if '%' in arg else arg for arg in args]
 sys.exit(subprocess.run([os.environ['V8_REAL_STAT']]+args).returncode)
if name=='aws':
 if args[:2]==['ecr','get-login-password']:print('synthetic-login')
 elif args[:2]==['ssm','get-parameter']:print('synthetic-secret-id')
 elif args[:2]==['secretsmanager','get-secret-value']:print('{"password":"synthetic-fresh"}')
 else:sys.exit(93)
 sys.exit(0)
if name=='jq':
 value=json.load(sys.stdin);print(value['password']);sys.exit(0)
if name=='render-runtime-env.sh':
 pathlib.Path(args[0]).write_text('DATABASE_PASSWORD=synthetic-fresh\n');sys.exit(0)
if name=='export-certificate.sh':sys.exit(0)
if name=='sleep':sys.exit(0)
if name!='docker':sys.exit(93)
if args and args[0]=='info':sys.exit(0)
if args and args[0] in ('login','pull'):
 if args[0]=='login':sys.stdin.read()
 sys.exit(0)
if args and args[0]=='run' and args[-1]=='migrate':
 sys.exit(17 if data.get('migration_failure') else 0)
if args and (args[0]=='update' or args[:2]==['container','update']):data['restart']='no'
elif args and (args[0]=='stop' or args[:2]==['container','stop']):data['running']=False
elif args[:2]==['container','rm']:data['running']=False
elif args and args[0]=='compose':
 if 'stop' in args or 'down' in args:data['running']=False
 elif 'up' in args:
  data['running']=True;data['image']=os.environ.get('APP_IMAGE');data['password']='synthetic-fresh'
  data['health']='unhealthy' if data.get('health_failure') else 'healthy'
 else:sys.exit(93)
elif args and args[0]=='start':
 data['running']=True
elif args and (args[0]=='inspect' or args[:2]==['container','inspect']):
 fmt=args[args.index('--format')+1] if '--format' in args else None
 if fmt and 'Health.Status' in fmt:print(data['health'])
 elif fmt and 'State.Running' in fmt:print('true' if data['running'] else 'false')
 elif fmt and 'RestartPolicy' in fmt:print(data['restart'])
 elif fmt and ('Config.Image' in fmt or 'RepoDigests' in fmt):print(data['image'])
 elif fmt and 'com.docker.compose.project' in fmt:print('meet-me-production')
 elif fmt:sys.exit(93)
 else:print(json.dumps([{'State':{'Running':data['running'],'Health':{'Status':data['health']}},'Config':{'Image':data['image']},'HostConfig':{'RestartPolicy':{'Name':data['restart']}}}]))
 sys.exit(0)
elif args and args[0]=='exec':print(data['password']);sys.exit(0)
elif args and args[0]=='logs':sys.exit(0)
elif args[:2]==['image','prune']:sys.exit(0)
elif args and args[0]=='ps':print('meet-me-app' if data['running'] else '');sys.exit(0)
else:sys.exit(93)
state.write_text(json.dumps(data))
'''


class Sandbox:
    def __init__(self, case):
        self.case = case
        self.temp = tempfile.TemporaryDirectory(prefix="meetme-v8-guard-")
        self.root = Path(self.temp.name)
        self.host = self.root / "host"
        self.state = self.host / "state"
        self.state.mkdir(parents=True)
        self.proc = self.root / "logical-proc"
        self.proc.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ("aws", "docker", "flock", "sleep", "id", "stat"):
            self.executable(self.bin / name, STUB)
        self.old = self.release("old", OLD, historical=True)
        self.new = self.release("new", NEW)
        self.next = self.release("next", NEXT)
        self.current = self.host / "current"
        self.current.symlink_to(self.old)
        self.lock = self.host / "release.lock"
        self.data = self.root / "fake-state.json"
        self.data.write_text(json.dumps({"running": True, "restart": "unless-stopped", "image": OLD,
                                        "health": "healthy", "password": "synthetic-old"}))
        self.state.joinpath("compatible-images.tsv").write_text(
            f"{NEW}\tinput_revision_v8\t{EVIDENCE}\n{NEXT}\tinput_revision_v8\t{EVIDENCE}\n")
        self.prerequisite = self.root / "prerequisite.json"
        self.prerequisite.write_text(json.dumps({"version": 1, "host_root": str(self.host),
                                                "automation_paused": True, "legacy_invocations_drained": True,
                                                "restart_policy_reviewed": True,
                                                "operator_evidence_sha256": EVIDENCE}))
        self.prerequisite.chmod(0o600)
        self.state.joinpath("compatible-images.tsv").chmod(0o600)
        self.env = {**os.environ, "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
                    "MEETME_HOST_ROOT": str(self.host), "MEETME_CURRENT_LINK": str(self.current),
                    "MEETME_DEPLOY_LOCK": str(self.lock),
                    "MEETME_REFRESH_PENDING_FILE": str(self.root / "refresh.pending"),
                    "V8_TEST_ROOT": str(self.root), "V8_REAL_FLOCK": shutil.which("flock"),
                    "V8_REAL_ID": shutil.which("id"), "V8_REAL_STAT": shutil.which("stat"),
                    "AWS_REGION": "synthetic-region"}
        # Never inherit real credential material into these offline subprocesses.
        for key in list(self.env):
            if key.startswith("AWS_") and key != "AWS_REGION" or key == "GEMINI_API_KEY":
                self.env.pop(key)

    def executable(self, path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        path.chmod(0o755)

    def release(self, name, image, historical=False):
        path = self.host / "releases" / name
        shutil.copytree(REPOSITORY / "deploy", path, ignore=shutil.ignore_patterns("tests"))
        if historical:
            for script in LIFECYCLE:
                shutil.copy2(REPOSITORY / "deploy/tests/fixtures/pre-v8" / script, path / "scripts" / script)
        # Fixed host literals in preguard scripts are mapped to the temporary logical host only.
        # No control flow or command is substituted; future scripts use MEETME_HOST_ROOT.
        for script in (path / "scripts").glob("*.sh"):
            text = script.read_text().replace("/opt/meet-me", str(self.host)).replace("/proc", str(self.proc))
            text = text.replace("/var/lock/meet-me-release.lock", str(self.host / "release.lock"))
            script.write_text(text)
        self.executable(path / "scripts/render-runtime-env.sh", STUB)
        self.executable(path / "scripts/export-certificate.sh", STUB)
        path.joinpath(".release.env").write_text(f"APP_IMAGE={image}\n")
        path.joinpath(".env.runtime").write_text("DATABASE_PASSWORD=synthetic-old\n")
        return path

    @property
    def launcher(self):
        return self.host / "guard/host-release-guard.sh"

    def command(self, script, args=(), role=None, check=False):
        env = {**self.env}
        if role:
            env["V8_TEST_ROLE"] = role
        result = subprocess.run(["bash", str(script), *map(str, args)], env=env,
                                capture_output=True, text=True, timeout=15)
        self.case.assertNotEqual(93, result.returncode, "Offline stub received an unknown external command")
        if check:
            self.case.assertEqual(0, result.returncode, result.stderr)
        return result

    def install(self, check=True):
        source = self.new / "scripts/install-host-release-guard.sh"
        self.case.assertTrue(source.is_file(), "ADR-046 requires an installer for durable common host launch authority")
        return self.command(source, (self.host, self.new, self.prerequisite), check=check)

    def action(self, verb, *args, check=False):
        self.case.assertTrue(self.launcher.is_file(), "Installed common host launcher is required")
        return self.command(self.launcher, (verb, *args), check=check)

    def deploy(self, release=None, image=NEW, check=False):
        # Current unguarded scripts still execute so pre-implementation RED demonstrates actual unsafe behavior.
        if self.new.joinpath("scripts/install-host-release-guard.sh").is_file():
            if not self.launcher.exists():
                self.install()
            return self.action("deploy", release or self.new, image, check=check)
        return self.command(self.new / "scripts/deploy-release.sh", (release or self.new, image), check=check)

    def calls(self):
        path = self.root / "calls.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def clear_calls(self):
        self.root.joinpath("calls.jsonl").unlink(missing_ok=True)

    def update(self, **values):
        data = json.loads(self.data.read_text())
        data.update(values)
        self.data.write_text(json.dumps(data))

    def starts(self):
        return [c for c in self.calls() if c["command"] == "docker" and
                (c["args"][0] == "start" or c["args"][0] == "compose" and "up" in c["args"])]

    def migrations(self):
        return [c for c in self.calls() if c["command"] == "docker" and c["args"][0] == "run" and c["args"][-1] == "migrate"]

    def marker(self):
        return self.state / "minimum-contract"

    def assert_no_external_mutation(self):
        self.case.assertFalse(self.starts())
        self.case.assertFalse(self.migrations())
        self.case.assertFalse(any(c["command"] == "aws" for c in self.calls()))

    def close(self):
        self.temp.cleanup()


class V8ReleaseGuardTest(unittest.TestCase):
    def setUp(self):
        self.s = Sandbox(self)
        self.addCleanup(self.s.close)

    def test_quiesce_and_sticky_marker_precede_real_migration_command(self):
        self.s.deploy(check=True)
        calls = self.s.calls()
        migrate = self.s.migrations()
        self.assertEqual(1, len(migrate))
        self.assertFalse(migrate[0]["running"], "Existing app writers/workers must stop before migration")
        self.assertEqual("input_revision_v8\n", migrate[0]["marker"])
        self.assertEqual("V8_STARTED", migrate[0]["phase"].strip())
        docker = [c for c in calls if c["command"] == "docker"]
        index = docker.index(migrate[0])
        self.assertTrue(any(c["args"][0] == "update" and "--restart=no" in c["args"] for c in docker[:index]))
        self.assertTrue(all(c["lock_held"] for c in docker))
        self.assertEqual(self.s.new, self.s.current.resolve())
        self.assertEqual("READY", self.s.state.joinpath("phase").read_text().strip())

    def test_health_failure_never_revives_previous_incompatible_image(self):
        self.s.update(health_failure=True)
        result = self.s.deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any(c["image"] == OLD for c in self.s.starts()), "Health failure must not fall back to old app")
        self.assertEqual("input_revision_v8\n", self.s.marker().read_text())
        self.assertFalse(json.loads(self.s.data.read_text())["running"], "Failed rollout remains quiescent")
        self.assertEqual("V8_STARTED", self.s.state.joinpath("phase").read_text().strip())

    def test_migration_failure_preserves_marker_and_never_starts_an_app(self):
        self.s.update(migration_failure=True)
        result = self.s.deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.s.starts())
        self.assertTrue(self.s.marker().is_file(), "Attempted migration requires durable sticky marker")
        self.assertEqual("input_revision_v8\n", self.s.marker().read_text())
        self.assertEqual("V8_STARTED", self.s.state.joinpath("phase").read_text().strip())

    def test_common_install_rewrites_all_stored_legacy_entrypoints_and_pins_inodes(self):
        originals = {name: (self.s.old / "scripts" / name).stat() for name in LIFECYCLE}
        self.s.install()
        self.assertTrue(self.s.launcher.is_file())
        manifest = self.s.state / "installation-manifest.json"
        manifest_data = json.loads(manifest.read_text())
        self.assertEqual(1, manifest_data["version"])
        self.assertEqual(str(self.s.host), manifest_data["host_root"])
        self.assertEqual(hashlib.sha256(self.s.launcher.read_bytes()).hexdigest(), manifest_data["launcher_sha256"])
        self.assertEqual(set(LIFECYCLE), set(manifest_data["wrappers"]))
        self.assertEqual(EVIDENCE, manifest_data["operator_evidence_sha256"])
        self.assertTrue(manifest_data["retired_scripts"])
        self.assertTrue(all(isinstance(x["device"], str) and isinstance(x["inode"], str)
                            for x in manifest_data["retired_scripts"]), "Decimal strings retain exact inode precision")
        self.assertEqual(json.loads(self.s.prerequisite.read_text()),
                         json.loads(self.s.state.joinpath("prerequisites.json").read_text()))
        self.assertEqual("PRE_V8\n", self.s.state.joinpath("phase").read_text())
        self.assertFalse(json.loads(self.s.data.read_text())["running"])
        self.assertEqual("no", json.loads(self.s.data.read_text())["restart"])
        archive = list(self.s.host.joinpath("guard/legacy-archive").rglob("*"))
        for name, old in originals.items():
            entry = self.s.old / "scripts" / name
            self.assertNotEqual((old.st_dev, old.st_ino), (entry.stat().st_dev, entry.stat().st_ino))
            self.assertTrue(any(p.is_file() and (p.stat().st_dev, p.stat().st_ino) == (old.st_dev, old.st_ino) for p in archive),
                            "Original inode must be pinned, not copied as new bytes")
        result = self.s.command(self.s.old / "scripts/rollback-release.sh", (self.s.old,))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.s.starts())

    def test_missing_or_false_operator_prerequisite_refuses_install(self):
        original = json.loads(self.s.prerequisite.read_text())
        for key in ("automation_paused", "legacy_invocations_drained", "restart_policy_reviewed", "operator_evidence_sha256"):
            with self.subTest(key=key):
                record = dict(original)
                record.pop(key)
                self.s.prerequisite.write_text(json.dumps(record))
                self.assertNotEqual(0, self.s.install(check=False).returncode)
                self.assertFalse(self.s.marker().exists())
                self.s.assert_no_external_mutation()
                self.s.clear_calls()
        self.s.prerequisite.write_text(json.dumps({**original, "automation_paused": False}))
        self.assertNotEqual(0, self.s.install(check=False).returncode)
        self.assertFalse(self.s.marker().exists())

    def test_old_deploy_rollback_refresh_and_restart_dispatch_deny_after_transition(self):
        self.s.deploy(check=True)
        self.s.current.unlink()
        self.s.current.symlink_to(self.s.old)
        for script, args in (("deploy-release.sh", (self.s.old, OLD)), ("rollback-release.sh", (self.s.old,)),
                             ("refresh-database-credential.sh", ())):
            with self.subTest(script=script):
                self.s.clear_calls()
                result = self.s.command(self.s.old / "scripts" / script, args)
                self.assertNotEqual(0, result.returncode)
                self.s.assert_no_external_mutation()
        self.s.clear_calls()
        self.assertNotEqual(0, self.s.action("restart").returncode)
        self.s.assert_no_external_mutation()

    def test_approved_rollforward_recovers_failed_transition_and_safe_refresh(self):
        self.s.update(health_failure=True)
        self.assertNotEqual(0, self.s.deploy().returncode)
        before = self.s.marker().read_bytes()
        self.s.update(health_failure=False)
        self.s.clear_calls()
        self.s.action("deploy", self.s.next, NEXT, check=True)
        self.assertEqual(self.s.next, self.s.current.resolve())
        self.assertEqual(before, self.s.marker().read_bytes())
        self.assertEqual("READY", self.s.state.joinpath("phase").read_text().strip())
        self.s.update(password="synthetic-old")
        self.s.next.joinpath(".env.runtime").write_text("DATABASE_PASSWORD=synthetic-old\n")
        self.s.clear_calls()
        self.s.action("refresh", check=True)
        self.assertTrue(self.s.starts())
        self.assertTrue(all(c["image"] == NEXT for c in self.s.starts()))
        self.assertEqual(before, self.s.marker().read_bytes())
        self.assertTrue(all(c["lock_held"] for c in self.s.calls() if c["command"] in ("docker", "aws")))

    def test_missing_or_corrupt_marker_after_ready_fails_closed(self):
        self.s.deploy(check=True)
        for marker in (None, "corrupt\n", "input_revision_v8\ntrailing\n"):
            with self.subTest(marker=marker):
                if marker is None:
                    self.s.marker().unlink(missing_ok=True)
                else:
                    self.s.marker().write_text(marker)
                self.s.clear_calls()
                self.assertNotEqual(0, self.s.action("restart").returncode)
                self.s.assert_no_external_mutation()

    def test_missing_policy_unapproved_or_malformed_digest_fails_before_aws(self):
        self.s.install()
        for image in (OLD, "example.invalid/app:main", "example.invalid/app@sha256:abc", NEW + "suffix"):
            with self.subTest(image=image):
                self.s.clear_calls()
                self.assertNotEqual(0, self.s.action("deploy", self.s.new, image).returncode)
                self.s.assert_no_external_mutation()
                self.assertFalse(self.s.marker().exists())
        self.s.state.joinpath("compatible-images.tsv").unlink()
        self.s.clear_calls()
        self.assertNotEqual(0, self.s.action("deploy", self.s.new, NEW).returncode)
        self.s.assert_no_external_mutation()

    def test_corrupt_digest_contract_or_evidence_fails_closed(self):
        self.s.install()
        for row in (f"{NEW}\t8\t{EVIDENCE}\n", f"{NEW}\tinput_revision_v8\tmissing\n", f"{NEW}\tinput_revision_v8\t{EVIDENCE}\n{NEW}\tinput_revision_v8\t{'b'*64}\n"):
            with self.subTest(row=row):
                self.s.state.joinpath("compatible-images.tsv").write_text(row)
                self.s.clear_calls()
                self.assertNotEqual(0, self.s.action("deploy", self.s.new, NEW).returncode)
                self.s.assert_no_external_mutation()

    def test_missing_guard_or_changed_legacy_wrapper_cannot_fall_back_to_original_source(self):
        self.s.install()
        self.s.old.joinpath("scripts/rollback-release.sh").write_text("#!/bin/bash\necho unexpected\n")
        self.s.clear_calls()
        self.assertNotEqual(0, self.s.action("deploy", self.s.new, NEW).returncode)
        self.s.assert_no_external_mutation()
        self.s.launcher.unlink()
        result = self.s.command(self.s.old / "scripts/refresh-database-credential.sh")
        self.assertNotEqual(0, result.returncode)
        self.s.assert_no_external_mutation()

    def test_started_phase_denies_all_non_deploy_actions_even_for_approved_current(self):
        self.s.update(migration_failure=True)
        self.assertNotEqual(0, self.s.deploy().returncode)
        self.s.current.unlink()
        self.s.current.symlink_to(self.s.new)
        for verb, args in (("rollback", (self.s.new,)), ("refresh", ()), ("restart", ())):
            with self.subTest(verb=verb):
                self.s.clear_calls()
                self.assertNotEqual(0, self.s.action(verb, *args).returncode)
                self.s.assert_no_external_mutation()

    def test_new_unregistered_legacy_release_source_blocks_runtime_until_guarded(self):
        self.s.install()
        self.s.release("late-old", OLD, historical=True)
        self.s.clear_calls()
        self.assertNotEqual(0, self.s.action("deploy", self.s.new, NEW).returncode)
        self.s.assert_no_external_mutation()

    def test_old_open_inode_waiting_real_lock_refuses_install_then_drain_permits_it(self):
        source = self.s.new / "scripts/install-host-release-guard.sh"
        self.assertTrue(source.is_file(), "ADR-046 requires guard installation to scan queued old script inodes")
        env = {**self.s.env, "V8_TEST_ROLE": "installer-barrier"}
        installer = subprocess.Popen(["bash", str(source), str(self.s.host), str(self.s.new), str(self.s.prerequisite)],
                                     env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
        legacy = None
        try:
            self.wait_until(lambda: self.s.root.joinpath("installer-lock-acquired").exists())
            legacy = subprocess.Popen(["bash", str(self.s.old / "scripts/refresh-database-credential.sh")],
                                      env={**self.s.env, "V8_TEST_ROLE": "old-waiter"},
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
            inode = self.s.old.joinpath("scripts/refresh-database-credential.sh").stat()
            # A local PID namespace projection exposes the real child FD metadata, not cmdline or secrets.
            projected = self.s.proc / str(legacy.pid)
            projected.mkdir()
            projected.joinpath("fd").symlink_to(Path(f"/proc/{legacy.pid}/fd"))
            def opened():
                for fd in Path(f"/proc/{legacy.pid}/fd").iterdir():
                    try:
                        stat = fd.stat()
                        if (stat.st_dev, stat.st_ino) == (inode.st_dev, inode.st_ino):
                            return True
                    except FileNotFoundError:
                        continue
                return False
            self.wait_until(opened)
            self.s.root.joinpath("release-installer-barrier").touch()
            _, error = installer.communicate(timeout=10)
            self.assertNotEqual(0, installer.returncode, error)
            self.assertFalse(self.s.marker().exists())
            self.assertFalse(self.s.migrations())
        finally:
            for proc in (legacy, installer):
                if proc and proc.poll() is None:
                    os.killpg(proc.pid, signal.SIGTERM)
                    proc.communicate(timeout=5)
        projected.joinpath("fd").unlink()
        projected.rmdir()
        self.s.install()
        self.assertTrue(self.s.launcher.is_file())
        self.assertFalse(self.s.marker().exists(), "Installation alone is not migration")

    def test_common_real_lock_serializes_start_dispatch(self):
        self.s.deploy(check=True)
        self.s.clear_calls()
        with self.s.lock.open("a") as held:
            fcntl.flock(held, fcntl.LOCK_EX)
            command = subprocess.Popen(["bash", str(self.s.launcher), "restart"], env=self.s.env,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                self.wait_until(lambda: any(c["command"] == "flock" for c in self.s.calls()))
                self.assertIsNone(command.poll())
                self.s.assert_no_external_mutation()
            finally:
                fcntl.flock(held, fcntl.LOCK_UN)
            _, error = command.communicate(timeout=10)
            self.assertEqual(0, command.returncode, error)
        self.assertTrue(all(c["lock_held"] for c in self.s.calls() if c["command"] in ("docker", "aws")))

    def test_missing_phase_or_manifest_and_writable_policy_cannot_restart(self):
        self.s.deploy(check=True)
        for name in ("phase", "installation-manifest.json", "prerequisites.json"):
            with self.subTest(name=name):
                artifact = self.s.state / name
                saved = artifact.read_bytes()
                artifact.unlink()
                self.s.clear_calls()
                self.assertNotEqual(0, self.s.action("restart").returncode)
                self.s.assert_no_external_mutation()
                artifact.write_bytes(saved)
                artifact.chmod(0o600)
        policy = self.s.state / "compatible-images.tsv"
        policy.chmod(0o666)
        self.s.clear_calls()
        self.assertNotEqual(0, self.s.action("restart").returncode)
        self.s.assert_no_external_mutation()

    def test_unapproved_running_container_cannot_pass_restart_by_current_metadata(self):
        self.s.deploy(check=True)
        self.s.update(image=OLD)
        self.s.clear_calls()
        self.assertNotEqual(0, self.s.action("restart").returncode)
        self.s.assert_no_external_mutation()

    def test_guarded_boot_artifact_and_compose_disable_automatic_app_restart(self):
        compose = REPOSITORY.joinpath("deploy/compose.production.yml").read_text()
        app = compose.split("  app:", 1)[1].split("  nginx:", 1)[0]
        self.assertRegex(app, r"restart:\s*['\"]?no['\"]?", "Old writer cannot restart automatically after host reboot")
        unit = REPOSITORY / "deploy/meet-me-guarded-restart.service"
        self.assertTrue(unit.is_file(), "Guarded restart unit must be staged for separately approved installation")
        self.assertIn("ExecStart=/opt/meet-me/guard/host-release-guard.sh restart", unit.read_text())
        self.s.install()
        self.assertTrue(self.s.host.joinpath("guard/meet-me-guarded-restart.service").is_file())
        self.assertFalse(any(c["command"] == "systemctl" for c in self.s.calls()), "Tests do not install/enable host service")

    def test_pre_v8_ordinary_maintenance_is_not_v8_compatibility_evidence(self):
        result = self.s.command(self.s.old / "scripts/refresh-database-credential.sh", check=True)
        self.assertEqual(0, result.returncode)
        self.assertTrue(any(c["image"] == OLD for c in self.s.starts()))
        self.assertFalse(self.s.marker().exists())
        self.assertFalse(self.s.launcher.exists())

    def wait_until(self, predicate):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(.01)
        self.fail("Local synchronization boundary did not arrive; this is fixture failure, not RED")


if __name__ == "__main__":
    unittest.main(verbosity=2)
