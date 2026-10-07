"""Unattested preexisting guard artifacts must not be adopted or overwrite entrypoints."""
import unittest

from test_v8_release_guard import LIFECYCLE
from test_v8_release_guard_additional import AdditionalSandbox


class V8ReleaseGuardInstallerBoundariesTest(unittest.TestCase):
    def test_unknown_preexisting_guard_rejects_before_rewriting_legacy_entrypoints(self):
        for artifact in ('launcher', 'override', 'both'):
            with self.subTest(artifact=artifact):
                s = AdditionalSandbox(self)
                try:
                    guard = s.host / 'guard'
                    guard.mkdir(mode=0o700)
                    unknown = {}
                    if artifact in ('launcher', 'both'):
                        launcher = guard / 'host-release-guard.sh'
                        launcher.write_text('#!/usr/bin/env bash\necho unattested-placeholder >&2\nexit 99\n')
                        launcher.chmod(0o750)
                        unknown[launcher] = launcher.read_bytes()
                    if artifact in ('override', 'both'):
                        override = guard / 'compose.guard.yml'
                        override.write_text('services:\n  app:\n    image: unattested-placeholder\n')
                        override.chmod(0o600)
                        unknown[override] = override.read_bytes()
                    originals = {}
                    for name in LIFECYCLE:
                        path = s.old / 'scripts' / name
                        stat = path.stat()
                        originals[path] = (path.read_bytes(), stat.st_dev, stat.st_ino)
                    s.clear_calls()
                    result = s.install(check=False)
                    self.assertNotEqual(0, result.returncode,
                                        'Unattested preexisting guard must require reviewed recovery, not adoption')
                    for path, original in originals.items():
                        stat = path.stat()
                        self.assertEqual(original, (path.read_bytes(), stat.st_dev, stat.st_ino),
                                         'Unknown guard must reject before rewriting any stored legacy entrypoint')
                    for path, original in unknown.items():
                        self.assertEqual(original, path.read_bytes(),
                                         'Unknown preexisting artifact must remain untouched for reviewed recovery')
                    self.assertFalse(s.state.joinpath('installation-manifest.json').exists())
                    self.assertFalse(s.state.joinpath('phase').exists())
                    self.assertFalse(s.marker().exists())
                    s.assert_no_external_mutation()
                finally:
                    s.close()


if __name__ == '__main__':
    unittest.main(verbosity=2)
