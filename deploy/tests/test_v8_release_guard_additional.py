"""Independent follow-up verification; initial 19 frozen test bytes remain unchanged."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import unittest

from test_v8_release_guard import EVIDENCE, NEW, NEXT, OLD, REPOSITORY, STUB, Sandbox


class AdditionalSandbox(Sandbox):
    def __init__(self, case):
        super().__init__(case)
        # Model Compose's app image/restart merge from the real -f files, so APP_IMAGE
        # alone cannot hide an incompatible literal image in a stored legacy YAML.
        extension = '''
effective_image=os.environ.get('APP_IMAGE');effective_restart=data.get('restart')
if name=='docker' and args and args[0]=='compose' and 'up' in args:
 import re
 for index,part in enumerate(args):
  if part!='-f':continue
  path=pathlib.Path(args[index+1]);text=path.read_text()
  app=text.split('app:',1)[1] if 'app:' in text else ''
  app=app.split('nginx:',1)[0]
  for key in ('image','restart'):
   matches=re.findall(r'(?m)^\\s*'+key+r':\\s*(.*?)\\s*$',app)
   if not matches:continue
   value=matches[-1].strip("\\\"'")
   if key=='image':effective_image=os.environ.get('APP_IMAGE') if '${APP_IMAGE' in value else value
   else:effective_restart=value
if name=='docker' and data.get('engine_failure'):
 if args and (args[0]=='inspect' or args[:2]==['container','inspect']):sys.exit(1)
 if args and args[0]=='info':sys.exit(92)
if name=='docker' and data.get('container_exists') is False:
 if args and (args[0]=='inspect' or args[:2]==['container','inspect']):sys.exit(1)
'''
        code = STUB.replace("event={'command':name", extension + "\nevent={'effective_image':effective_image,'effective_restart':effective_restart,'command':name")
        code = code.replace("data['image']=os.environ.get('APP_IMAGE')", "data['image']=effective_image;data['restart']=effective_restart;data['container_exists']=True")
        code = code.replace("data['health']='unhealthy'", "data['image']=OLD_PLACEHOLDER if data.get('wrong_launched_image') else data['image']\n  data['health']='unhealthy'")
        code = code.replace("OLD_PLACEHOLDER", repr(OLD))
        self.executable(self.bin / "docker", code)


class V8ReleaseGuardAdditionalTest(unittest.TestCase):
    def setUp(self):
        self.s = AdditionalSandbox(self)
        self.addCleanup(self.s.close)

    def test_private_override_forces_approved_image_and_no_restart_from_old_compose(self):
        self.s.deploy(check=True)
        self.s.old.joinpath(".release.env").write_text(f"APP_IMAGE={NEXT}\n")
        compose = REPOSITORY / "deploy/tests/fixtures/pre-v8/compose.production.yml"
        text = compose.read_text().replace("${APP_IMAGE:?APP_IMAGE is required}", OLD)
        self.s.old.joinpath("compose.production.yml").write_text(text)
        self.s.clear_calls()
        self.s.action("rollback", self.s.old, check=True)
        starts = self.s.starts()
        self.assertTrue(starts)
        for call in starts:
            self.assertEqual(NEXT, call["effective_image"], "Legacy literal old image must be overridden before launch")
            self.assertEqual("no", call["effective_restart"])
            files = [call["args"][i + 1] for i, value in enumerate(call["args"]) if value == "-f"]
            self.assertEqual(str(self.s.host / "guard/compose.guard.yml"), files[-1])
        manifest = json.loads(self.s.state.joinpath("installation-manifest.json").read_text())
        override = self.s.host / "guard/compose.guard.yml"
        self.assertEqual(hashlib.sha256(override.read_bytes()).hexdigest(), manifest["override_sha256"])
        self.s.clear_calls()
        self.s.action("restart", check=True)
        self.assertTrue(all(c["effective_image"] == NEXT and c["effective_restart"] == "no" for c in self.s.starts()))

    def test_changed_private_override_rejects_before_start_not_only_after_image_inspection(self):
        self.s.deploy(check=True)
        self.s.host.joinpath("guard/compose.guard.yml").write_text(f"services:\n  app:\n    image: {OLD}\n    restart: unless-stopped\n")
        self.s.clear_calls()
        self.assertNotEqual(0, self.s.action("restart").returncode)
        self.s.assert_no_external_mutation()

    def test_engine_failure_is_not_container_absence_and_cannot_begin_migration(self):
        self.s.install()
        self.s.update(engine_failure=True, container_exists=False, running=False)
        self.s.clear_calls()
        result = self.s.action("deploy", self.s.new, NEW)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.s.migrations())
        self.assertFalse(self.s.starts())
        self.assertFalse(self.s.marker().exists())

    def test_truly_absent_old_container_with_healthy_engine_allows_approved_deploy(self):
        self.s.install()
        self.s.update(container_exists=False, running=False)
        self.s.clear_calls()
        self.s.action("deploy", self.s.new, NEW, check=True)
        self.assertEqual("READY\n", self.s.state.joinpath("phase").read_text())
        self.assertTrue(self.s.migrations())
        self.assertTrue(self.s.starts())

    def test_failed_atomic_phase_or_marker_write_never_runs_migration_or_launch(self):
        for artifact in ("phase", "minimum-contract"):
            with self.subTest(artifact=artifact):
                sandbox = AdditionalSandbox(self)
                try:
                    sandbox.install()
                    sandbox.clear_calls()
                    # Logical filesystem fault only: mv remains real for every other target.
                    stub = """#!/usr/bin/env python3
import os,subprocess,sys
from pathlib import Path
if Path(sys.argv[-1]).name==os.environ['V8_WRITE_FAIL_TARGET']:sys.exit(74)
sys.exit(subprocess.run([os.environ['V8_REAL_MV']]+sys.argv[1:]).returncode)
"""
                    sandbox.executable(sandbox.bin / "mv", stub)
                    sandbox.env["V8_WRITE_FAIL_TARGET"] = artifact
                    sandbox.env["V8_REAL_MV"] = shutil.which("mv")
                    result = sandbox.action("deploy", sandbox.new, NEW)
                    self.assertNotEqual(0, result.returncode)
                    self.assertFalse(sandbox.migrations())
                    self.assertFalse(sandbox.starts())
                finally:
                    sandbox.close()

    def test_reinstall_preserves_started_marker_and_never_downgrades_phase(self):
        self.s.update(migration_failure=True)
        self.assertNotEqual(0, self.s.deploy().returncode)
        marker = self.s.marker().read_bytes()
        self.s.clear_calls()
        self.s.install()
        self.assertEqual(marker, self.s.marker().read_bytes())
        self.assertEqual("V8_STARTED\n", self.s.state.joinpath("phase").read_text())
        self.assertFalse(self.s.migrations())
        self.assertFalse(self.s.starts())

    def test_reinstall_keeps_ready_current_selection_and_compatible_digest_ledger(self):
        self.s.deploy(check=True)
        marker = self.s.marker().read_bytes()
        policy = self.s.state.joinpath("compatible-images.tsv").read_bytes()
        self.s.clear_calls()
        self.s.install()
        self.assertEqual(marker, self.s.marker().read_bytes())
        self.assertEqual(policy, self.s.state.joinpath("compatible-images.tsv").read_bytes())
        self.assertEqual("READY\n", self.s.state.joinpath("phase").read_text())
        self.assertEqual(self.s.new, self.s.current.resolve())
        self.assertFalse(self.s.migrations())
        self.assertFalse(self.s.starts())

    def test_wrong_image_after_health_check_stops_app_and_keeps_marker_without_old_fallback(self):
        self.s.install()
        self.s.update(wrong_launched_image=True)
        self.s.clear_calls()
        self.assertNotEqual(0, self.s.action("deploy", self.s.new, NEW).returncode)
        self.assertEqual("input_revision_v8\n", self.s.marker().read_text())
        self.assertEqual("V8_STARTED\n", self.s.state.joinpath("phase").read_text())
        self.assertFalse(json.loads(self.s.data.read_text())["running"])
        self.assertFalse(any(c["image"] == OLD for c in self.s.starts()))

    def test_ready_restart_health_failure_enters_sticky_maintenance(self):
        self.s.deploy(check=True)
        self.s.update(health_failure=True)
        self.s.clear_calls()
        self.assertNotEqual(0, self.s.action("restart").returncode)
        self.assertEqual("V8_STARTED\n", self.s.state.joinpath("phase").read_text())
        self.assertEqual("input_revision_v8\n", self.s.marker().read_text())
        self.assertFalse(json.loads(self.s.data.read_text())["running"])
        self.assertFalse(any(c["image"] == OLD for c in self.s.starts()))

    def test_open_pinned_retired_source_is_rejected_on_every_launch(self):
        self.s.deploy(check=True)
        manifest = json.loads(self.s.state.joinpath("installation-manifest.json").read_text())
        retired = Path(manifest["retired_scripts"][0]["archive"])
        projection = self.s.proc / str(os.getpid())
        projection.mkdir()
        projection.joinpath("fd").symlink_to(Path(f"/proc/{os.getpid()}/fd"))
        with retired.open("rb"):
            self.s.clear_calls()
            self.assertNotEqual(0, self.s.action("restart").returncode)
            self.s.assert_no_external_mutation()
        projection.joinpath("fd").unlink()
        projection.rmdir()
        self.s.action("restart", check=True)

    def test_proc_visibility_failure_refuses_instead_of_claiming_drain(self):
        self.s.deploy(check=True)
        projection = self.s.proc / str(os.getpid())
        fd = projection / "fd"
        fd.mkdir(parents=True)
        fd.chmod(0)
        try:
            self.s.clear_calls()
            self.assertNotEqual(0, self.s.action("restart").returncode)
            self.s.assert_no_external_mutation()
        finally:
            fd.chmod(0o700)

    def test_symlink_policy_or_mismatched_operator_evidence_cannot_authorize_restart(self):
        self.s.deploy(check=True)
        policy = self.s.state / "compatible-images.tsv"
        original = policy.read_bytes()
        alternate = self.s.root / "alternate-policy.tsv"
        alternate.write_bytes(original)
        policy.unlink()
        policy.symlink_to(alternate)
        self.s.clear_calls()
        self.assertNotEqual(0, self.s.action("restart").returncode)
        self.s.assert_no_external_mutation()
        policy.unlink()
        policy.write_bytes(original)
        policy.chmod(0o600)
        prereq = self.s.state / "prerequisites.json"
        record = json.loads(prereq.read_text())
        record["operator_evidence_sha256"] = "b" * 64
        prereq.write_text(json.dumps(record))
        self.s.clear_calls()
        self.assertNotEqual(0, self.s.action("restart").returncode)
        self.s.assert_no_external_mutation()


if __name__ == "__main__":
    unittest.main(verbosity=2)
