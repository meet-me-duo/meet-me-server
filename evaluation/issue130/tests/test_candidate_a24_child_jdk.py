"""Three contexts for the same frozen15 wrapper cases; synthetic-only, not45 unique cases."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]


class CandidateChildJdkContract(unittest.TestCase):
    def run_context(self, context):
        java = Path(os.environ['ISSUE130_CI_JAVA']).resolve(strict=True)
        cp = Path(os.environ['ISSUE130_CI_CLASSPATH_FILE']).read_text().strip()
        with tempfile.TemporaryDirectory(prefix='issue130-a24-jdk-context-') as directory:
            home = java.parent.parent
            if context == 'MISSING':
                home = Path(directory) / 'missing-jdk'
            elif context == 'SYMLINK':
                home = Path(directory) / 'symbolic-jdk'
                home.symlink_to(java.parent.parent, target_is_directory=True)
            env = {k: v for k, v in os.environ.items() if k in ('PATH', 'HOME', 'TMPDIR')}
            env.update(OPENAI_API_KEY='', GEMINI_API_KEY='', JAVA_HOME=str(home),
                       ISSUE130_TEST_JAVA=str(java), ISSUE130_TEST_SOURCE_ROOT=str(REPO),
                       ISSUE130_TEST_CLASSPATH=cp, PYTHONDONTWRITEBYTECODE='1')
            result = subprocess.run([os.environ['ISSUE130_CI_PYTHON'],
                                     str(REPO / 'evaluation/issue130/tests/test_candidate_a24.py')],
                                    cwd=REPO, env=env, capture_output=True, text=True, timeout=120)
            self.assertRegex(result.stderr, r'Ran 15 tests in ', 'All original15 cases must actually execute')
            self.assertNotIn('SYNTHETIC_A24_FIXTURE_SETUP_FAILED', result.stderr)
            self.assertEqual(0, result.returncode, context + '_HOME_MUST_NOT_CHANGE_ORIGINAL15_WRAPPER_ORACLES')
            self.assertIn('\nOK\n', result.stderr)

    def test_ACJ01_missing_parent_home_original15(self):
        self.run_context('MISSING')

    def test_ACJ02_symlink_parent_home_original15(self):
        self.run_context('SYMLINK')

    def test_ACJ03_valid_home_original15_control(self):
        self.run_context('VALID')


if __name__ == '__main__':
    unittest.main(verbosity=2)
