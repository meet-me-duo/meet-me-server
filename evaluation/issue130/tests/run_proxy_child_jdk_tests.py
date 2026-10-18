#!/usr/bin/env python3
"""Current JVM fixture isolation and whitelist metadata; cached compiled synthetic classes only."""
import argparse
import fcntl
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import time
import unittest

REPO = Path(__file__).resolve().parents[3]
TEST_ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evaluation-classpath-file', required=True)
    parser.add_argument('--java', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    output.chmod(0o700)
    os.environ.update(OPENAI_API_KEY='', GEMINI_API_KEY='',
                      ISSUE130_PC_JAVA=str(Path(args.java).resolve(strict=True)),
                      ISSUE130_PC_CLASSPATH_FILE=str(Path(args.evaluation_classpath_file).resolve(strict=True)))
    files = [TEST_ROOT / n for n in ('test_proxy_child_jdk.py', 'test_proxy_child_observation.py',
                                    'run_proxy_child_jdk_tests.py', 'Issue130EnvironmentProxyContractTest.java',
                                    'run_environment_proxy_tests.py', 'test_proxy_failure_reporting.py')]
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    started = time.monotonic()
    with open('/tmp/meetme-gradle.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        before = {str(p.relative_to(REPO)): digest(p) for p in files}
        suite = unittest.TestSuite()
        for filename in ('test_proxy_child_jdk.py', 'test_proxy_child_observation.py', 'test_proxy_failure_reporting.py'):
            spec = importlib.util.spec_from_file_location(filename[:-3], TEST_ROOT / filename)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            suite.addTests(unittest.defaultTestLoader.loadTestsFromModule(module))
        stream = io.StringIO()
        result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
        after = {str(p.relative_to(REPO)): digest(p) for p in files}
        (output / 'run.log').write_text(stream.getvalue())
        (output / 'source-before.json').write_text(json.dumps(before, indent=2) + '\n')
        (output / 'source-after.json').write_text(json.dumps(after, indent=2) + '\n')
        receipt = dict(status='PASS' if result.wasSuccessful() and before == after else 'FAILED',
                       tests=result.testsRun, failures=len(result.failures), errors=len(result.errors),
                       skipped=len(result.skipped),
                       failedTests=[str(t) for t, _ in result.failures],
                       errorTests=[str(t) for t, _ in result.errors],
                       sourceBeforeAfterEqual=before == after,
                       elapsedSeconds=round(time.monotonic() - started, 3),
                       actualModelCalls=0, sealedDataRead=False, realKeysBlank=True,
                       remoteCiCauseConfirmed=False)
        (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print(json.dumps(receipt))
        return 0 if receipt['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
