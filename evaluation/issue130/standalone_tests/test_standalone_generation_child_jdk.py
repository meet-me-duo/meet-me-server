"""Two selected-Java contexts reuse the original25 B wrapper cases, not50 unique cases."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
ORIGINAL = REPO / 'evaluation/issue130/standalone_tests/test_standalone_generation.py'


class StandaloneSelectedJavaContract(unittest.TestCase):
    def run_context(self, symbolic):
        java = Path(os.environ['ISSUE130_B_CONTEXT_JAVA']).resolve(strict=True)
        classpath = Path(os.environ['ISSUE130_B_CONTEXT_CLASSPATH_FILE']).read_text().strip()
        context = 'SYMLINK_ANCESTOR' if symbolic else 'CANONICAL'
        output = Path(os.environ.get('ISSUE130_B_CONTEXT_OUTPUT') or
                      tempfile.mkdtemp(prefix='issue130-b-selected-java-output-'))
        output.mkdir(parents=True, exist_ok=True)
        output.chmod(0o700)
        before = hashlib.sha256(ORIGINAL.read_bytes()).hexdigest()
        with tempfile.TemporaryDirectory(prefix='issue130-b-selected-java-') as directory:
            selected = java
            if symbolic:
                link = Path(directory) / 'selected-jdk'
                link.symlink_to(java.parent.parent, target_is_directory=True)
                selected = link / 'bin/java'
                self.assertTrue(link.is_symlink(), 'Real ancestor-symlink fixture must be exercised')
                self.assertEqual(java, selected.resolve(strict=True), 'Both contexts select the same Java')
            env = {key: value for key, value in os.environ.items() if key in ('PATH', 'HOME', 'TMPDIR')}
            env.update(OPENAI_API_KEY='', GEMINI_API_KEY='',
                       JAVA_HOME=str(selected.parent.parent), ISSUE130_TEST_JAVA=str(selected),
                       ISSUE130_TEST_SOURCE_ROOT=str(REPO), ISSUE130_TEST_CLASSPATH=classpath,
                       PYTHONDONTWRITEBYTECODE='1')
            result = subprocess.run([sys.executable, str(ORIGINAL)], cwd=REPO, env=env,
                                    capture_output=True, text=True, timeout=180)
        text = result.stdout + result.stderr
        (output / (context.lower() + '.log')).write_text(text)
        failed = re.findall(r'^FAIL: (test_\w+)', text, re.M)
        errors = re.findall(r'^ERROR: (test_\w+)', text, re.M)
        counts = re.search(r'Ran (\d+) tests? in ', text)
        (output / (context.lower() + '.json')).write_text(json.dumps({
            'context': context, 'exitCode': result.returncode,
            'tests': int(counts.group(1)) if counts else 0, 'failedMethods': failed,
            'errorMethods': errors, 'originalSha256': before,
            'originalUnchanged': before == hashlib.sha256(ORIGINAL.read_bytes()).hexdigest(),
            'actualModelCalls': 0, 'privateInputsRead': False}, indent=2) + '\n')
        self.assertEqual(before, hashlib.sha256(ORIGINAL.read_bytes()).hexdigest())
        self.assertIsNotNone(counts, 'Original entire Python suite must execute')
        self.assertEqual(25, int(counts.group(1)), 'All original25 cases must actually execute')
        self.assertFalse(errors, 'No fixture-export/setup errors: ' + ','.join(errors))
        self.assertEqual(0, result.returncode,
                         context + '_SELECTED_JAVA_MUST_PRESERVE_ORIGINAL25_ORACLES: ' + ','.join(failed))
        self.assertIn('\nOK\n', result.stderr)

    def test_BCJ01_canonical_selected_java_original25_control(self):
        self.run_context(False)

    def test_BCJ02_symlink_ancestor_selected_java_original25(self):
        self.run_context(True)


if __name__ == '__main__':
    unittest.main(verbosity=2)
