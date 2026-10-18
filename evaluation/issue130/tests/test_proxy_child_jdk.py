"""Synthetic PC01 child-JDK isolation; no production key, case, CA or network."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

PC01 = 'PC01_actual_Python_CLI_ENV_proxy_TLS16_and_replay0'
REPO = Path(__file__).resolve().parents[3]


class ChildJdkContract(unittest.TestCase):
    def run_context(self, context):
        java = Path(os.environ['ISSUE130_PC_JAVA']).resolve(strict=True)
        cp = Path(os.environ['ISSUE130_PC_CLASSPATH_FILE']).read_text().strip()
        with tempfile.TemporaryDirectory(prefix='issue130-pc-jdk-synthetic-') as directory:
            root = Path(directory)
            home = java.parent.parent
            if context == 'MISSING':
                home = root / 'does-not-exist'
            elif context == 'SYMLINK':
                home = root / 'inherited-symbolic-jdk'
                home.symlink_to(java.parent.parent, target_is_directory=True)
            environment = {k: v for k, v in os.environ.items() if k in ('PATH', 'HOME', 'TMPDIR')}
            environment.update(OPENAI_API_KEY='', GEMINI_API_KEY='', JAVA_HOME=str(home))
            result = subprocess.run(
                [str(java), '-Dissue130.sourceRoot=' + str(REPO), '-cp', cp,
                 'Issue130EnvironmentProxyContractTest', PC01],
                cwd=REPO, env=environment, capture_output=True, text=True, timeout=90)
            rows = []
            for line in result.stdout.splitlines():
                try:
                    rows.append(json.loads(line))
                except ValueError:
                    pass
            summary = next((r for r in reversed(rows) if isinstance(r, dict) and 'tests' in r), {})
            self.assertEqual(0, summary.get('errors'), 'PC01 must execute, not fail test setup')
            self.assertEqual(1, summary.get('tests'), 'exact frozen PC01 only')
            self.assertEqual(0, result.returncode, context + '_PARENT_HOME_MUST_NOT_CHANGE_PC01_CHILD_JDK')
            self.assertEqual(1, summary.get('passed'), 'PC01 whole-CLI16POST/replay0 assertions remain active')
            self.assertEqual(0, summary.get('assertionFailures'))
            self.assertEqual(0, summary.get('paidCalls'))
            self.assertIs(False, summary.get('sealedDataRead'))

    def test_PJ01_missing_inherited_home_uses_current_real_jvm(self):
        self.run_context('MISSING')

    def test_PJ02_symlink_inherited_home_uses_current_real_jvm(self):
        self.run_context('SYMLINK')

    def test_PJ03_valid_inherited_home_control(self):
        self.run_context('VALID')


if __name__ == '__main__':
    unittest.main(verbosity=2)
