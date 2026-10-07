"""Fresh independent behavioral regressions; initial frozen 19+12 unchanged."""
import unittest

from test_v8_release_guard import EVIDENCE, NEW, NEXT, OLD
from test_v8_release_guard_additional import AdditionalSandbox


class V8ReleaseGuardReviewBoundariesTest(unittest.TestCase):
    def assert_read_only_calls(self, sandbox):
        sandbox.assert_no_external_mutation()
        writes = [call for call in sandbox.calls() if call['command'] == 'docker' and
                  call['args'][0] in ('update', 'stop', 'start', 'compose', 'run', 'pull', 'login')]
        self.assertEqual([], writes, 'Invalid approval/current metadata must fail before Docker mutation')

    def test_policy_rejects_extra_empty_tab_fields_before_external_mutation(self):
        rows = {
            'trailing_empty': f'{NEW}\tinput_revision_v8\t{EVIDENCE}\t\n',
            'leading_empty': f'\t{NEW}\tinput_revision_v8\t{EVIDENCE}\n',
            'empty_between': f'{NEW}\t\tinput_revision_v8\t{EVIDENCE}\n',
            'extra_empty_between': f'{NEW}\tinput_revision_v8\t\t{EVIDENCE}\n',
        }
        for name, row in rows.items():
            with self.subTest(name=name):
                s = AdditionalSandbox(self)
                try:
                    s.deploy(check=True)
                    s.state.joinpath('compatible-images.tsv').write_text(row)
                    s.clear_calls()
                    result = s.action('deploy', s.new, NEW)
                    self.assertNotEqual(0, result.returncode,
                                        'Approval policy must reject an extra empty TAB field, not collapse it')
                    self.assert_read_only_calls(s)
                    self.assertEqual('READY\n', s.state.joinpath('phase').read_text())
                finally:
                    s.close()

    def test_rollback_denies_unapproved_current_even_with_an_approved_target(self):
        s = AdditionalSandbox(self)
        try:
            s.deploy(check=True)
            # Match actual image to current metadata: the only invalid invariant is
            # current approval, rather than a missing run/container or image mismatch.
            s.new.joinpath('.release.env').write_text(f'APP_IMAGE={OLD}\n')
            s.update(image=OLD, running=True, health='healthy')
            s.clear_calls()
            result = s.action('rollback', s.next)
            self.assertNotEqual(0, result.returncode,
                                'Non-deploy rollback must reject an unapproved current image before mutation')
            self.assert_read_only_calls(s)
            self.assertEqual(s.new, s.current.resolve())
            self.assertEqual('READY\n', s.state.joinpath('phase').read_text())
            # Explicit approved deployment is the permitted repair mechanism.
            s.action('deploy', s.next, NEXT, check=True)
            self.assertEqual(s.next, s.current.resolve())
        finally:
            s.close()


if __name__ == '__main__':
    unittest.main(verbosity=2)
