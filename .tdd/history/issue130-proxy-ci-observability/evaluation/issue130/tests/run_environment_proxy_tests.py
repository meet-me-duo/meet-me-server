#!/usr/bin/env python3
"""Additive issue130 environment-proxy contracts, synthetic/private fixtures only."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

REPO = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output')
    parser.add_argument('--java-test', action='append', default=[])
    parser.add_argument('--python-test', action='append', default=[])
    args = parser.parse_args()
    out = Path(args.output) if args.output else Path(tempfile.mkdtemp(prefix='issue130-env-proxy-synthetic-'))
    out.mkdir(parents=True, exist_ok=True)
    out.chmod(0o700)
    env = os.environ.copy()
    env.update(OPENAI_API_KEY='', GEMINI_API_KEY='')
    started = time.monotonic()
    with (out / 'compile.log').open('w') as stream:
        build = subprocess.run([sys.executable, 'evaluation/issue130/tests/run_synthetic_tests.py', '--compile-only', '--output', str(out / 'compile')],
                               cwd=REPO, env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=360)
    if build.returncode:
        print(json.dumps({'status': 'SETUP_FAILURE', 'stage': 'compile', 'exitCode': build.returncode, 'evidence': str(out)}))
        return 2
    with open('/tmp/meetme-gradle.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        before = json.loads((out / 'compile/source-after.json').read_text())
        current = {p: hashlib.sha256((REPO / p).read_bytes()).hexdigest() for p in before}
        if before != current:
            raise RuntimeError('SOURCE_DRIFT')
        cp = (out / 'compile/evaluation-runtime.classpath').read_text().strip()
        java = str(Path(env.get('JAVA_HOME') or '/tmp/meetme-jdk17') / 'bin/java')
        java_exit = python_exit = 0
        rows = []
        if not args.python_test:
            with (out / 'java.jsonl').open('w') as stream:
                run = subprocess.run([java, f'-Dissue130.sourceRoot={REPO}', '-cp', cp, 'Issue130EnvironmentProxyContractTest', *args.java_test],
                                     cwd=REPO, env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=180)
            java_exit = run.returncode
            for line in (out / 'java.jsonl').read_text().splitlines():
                try:
                    rows.append(json.loads(line))
                except ValueError:
                    pass
        if not args.java_test:
            childenv = dict(env, ISSUE130_TEST_SOURCE_ROOT=str(REPO), ISSUE130_TEST_JAVA=java, ISSUE130_TEST_CLASSPATH=cp)
            with (out / 'python.log').open('w') as stream:
                run = subprocess.run([sys.executable, 'evaluation/issue130/tests/test_environment_proxy.py', *args.python_test],
                                     cwd=REPO, env=childenv, stdout=stream, stderr=subprocess.STDOUT, timeout=90)
            python_exit = run.returncode
        after = {p: hashlib.sha256((REPO / p).read_bytes()).hexdigest() for p in before}
        (out / 'source-before.json').write_text(json.dumps(before, indent=2) + '\n')
        (out / 'source-after.json').write_text(json.dumps(after, indent=2) + '\n')
        result = {'status': 'PASS' if not (java_exit or python_exit) else 'FAILED', 'javaExitCode': java_exit,
                  'pythonExitCode': python_exit, 'javaSummary': rows[-1] if rows else None,
                  'sourceBeforeAfterEqual': before == after, 'durationSeconds': round(time.monotonic() - started, 3),
                  'evidence': str(out), 'paidCalls': 0, 'sealedDataRead': False}
        (out / 'receipt.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result))
        return 0 if not (java_exit or python_exit) and before == after else 1


if __name__ == '__main__':
    raise SystemExit(main())
