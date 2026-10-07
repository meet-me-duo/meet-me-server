"""Independent detectors with each competing guard invariant made valid.

FD access denial uses a real child-held archive inode plus a local namespace
alias whose pathname is unreadable. This models permission-denied FD metadata;
it is not proof of native host root permissions or real operator drain.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest

from test_v8_release_guard import NEW, NEXT, OLD, REPOSITORY
from test_v8_release_guard_additional import AdditionalSandbox


class V8ReleaseGuardDetectorsTest(unittest.TestCase):
    def setUp(self):
        self.s = AdditionalSandbox(self)
        self.addCleanup(self.s.close)

    def test_started_phase_blocks_non_deploy_with_approved_matching_current_image(self):
        self.s.deploy(check=True)
        self.s.state.joinpath('phase').write_text('V8_STARTED\n')
        # Current link, metadata, actual image, credentials and approval are valid.
        # A removed phase gate must not be masked by an OLD image mismatch.
        self.s.update(image=NEW, running=False, health='healthy', password='synthetic-fresh')
        self.s.new.joinpath('.env.runtime').write_text('DATABASE_PASSWORD=synthetic-fresh\n')
        for action, args in [('rollback', (self.s.next,)), ('restart', ()), ('refresh', ())]:
            with self.subTest(action=action):
                self.s.clear_calls()
                result = self.s.action(action, *args)
                self.assertNotEqual(0, result.returncode,
                                    'V8_STARTED must deny non-deploy even when current image is approved and matches')
                self.s.assert_no_external_mutation()
                self.assertFalse(any(call['command'] == 'docker' and call['args'][0] in ('stop', 'update')
                                     for call in self.s.calls()))
                self.assertEqual('V8_STARTED\n', self.s.state.joinpath('phase').read_text())
                self.assertEqual(self.s.new, self.s.current.resolve())

    def test_prelaunch_override_never_starts_old_literal_image_even_if_action_later_fails(self):
        self.s.deploy(check=True)
        self.s.old.joinpath('.release.env').write_text(f'APP_IMAGE={NEXT}\n')
        text = REPOSITORY.joinpath('deploy/tests/fixtures/pre-v8/compose.production.yml').read_text()
        self.s.old.joinpath('compose.production.yml').write_text(
            text.replace('${APP_IMAGE:?APP_IMAGE is required}', OLD))
        self.s.clear_calls()
        # Force a post-launch failure: a failure exit must not hide an earlier OLD writer start.
        self.s.update(health_failure=True)
        result = self.s.action('rollback', self.s.old)
        self.assertNotEqual(0, result.returncode)
        starts = self.s.starts()
        self.assertTrue(starts, 'Approved target must reach the controlled launch before health failure')
        self.assertTrue(all(call['effective_image'] == NEXT for call in starts),
                        'No incompatible literal image may start, including an action that later fails')
        self.assertTrue(all(call['effective_restart'] == 'no' for call in starts))
        self.assertEqual('V8_STARTED\n', self.s.state.joinpath('phase').read_text())
        self.assertFalse(json.loads(self.s.data.read_text())['running'])

    def test_known_live_retired_fd_with_access_denied_projection_rejects_before_mutation(self):
        self.s.deploy(check=True)
        manifest = json.loads(self.s.state.joinpath('installation-manifest.json').read_text())
        pin = Path(manifest['retired_scripts'][0]['archive'])
        child = subprocess.Popen(
            [sys.executable, '-c', 'import os,sys; f=open(sys.argv[1],"rb"); '
             'print(f.fileno(),flush=True); sys.stdin.read()', str(pin)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, env=self.s.env)
        denied = self.s.root / 'denied-fd-alias'
        denied.mkdir()
        alias = denied / 'held-pin'
        os.link(pin, alias)
        try:
            descriptor = int(child.stdout.readline().strip())
            oracle = Path(f'/proc/{child.pid}/fd/{descriptor}')
            actual = oracle.stat()
            expected = pin.stat()
            self.assertEqual((expected.st_dev, expected.st_ino), (actual.st_dev, actual.st_ino))
            self.assertIsNone(child.poll(), 'FD owner must still be alive')
            projection = self.s.proc / str(child.pid) / 'fd'
            projection.mkdir(parents=True)
            leaf = projection / str(descriptor)
            leaf.symlink_to(alias)
            denied.chmod(0)
            self.assertTrue(leaf.is_symlink(), 'FD leaf remains visible via lstat')
            visible = subprocess.run(['bash', '-c', '[[ -e "$1" ]]', '_', str(leaf)],
                                     env=self.s.env, capture_output=True, text=True)
            self.assertEqual(1, visible.returncode,
                             'Shell -e must report the permission-denied FD alias as unresolvable')
            with self.assertRaises(PermissionError):
                leaf.stat()
            # Oracle remains genuinely open and pinned while the logical namespace
            # denies access; this is not a closed/deleted descriptor fixture.
            self.assertEqual(expected.st_ino, oracle.stat().st_ino)
            self.s.clear_calls()
            result = self.s.action('restart')
            self.assertNotEqual(0, result.returncode,
                                'Known live FD with visible symlink and denied metadata must fail closed')
            self.s.assert_no_external_mutation()
        finally:
            denied.chmod(0o700)
            if child.stdin:
                child.stdin.close()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
            child.stdout.close()
            child.stderr.close()


if __name__ == '__main__':
    unittest.main(verbosity=2)
