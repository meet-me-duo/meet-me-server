#!/usr/bin/env python3
"""Run unchanged101 contracts then independent atomic0600 check; loopback/synthetic only."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

REPO = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--private-only', action='store_true')
    parser.add_argument('--output')
    args = parser.parse_args()
    out = Path(args.output) if args.output else Path(tempfile.mkdtemp(prefix='issue130-all-synthetic-'))
    out.mkdir(parents=True, exist_ok=True)
    out.chmod(0o700)
    env = os.environ.copy()
    env.update(OPENAI_API_KEY='', GEMINI_API_KEY='')
    command = [sys.executable, 'evaluation/issue130/tests/run_synthetic_tests.py', '--output', str(out / 'base')]
    if args.private_only:
        command.append('--compile-only')
    with (out / 'base.log').open('w') as stream:
        compiled = subprocess.run(command, cwd=REPO, env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=420)
    if compiled.returncode:
        print(json.dumps({'status': 'BASE_FAILED', 'exitCode': compiled.returncode, 'evidence': str(out)}))
        return compiled.returncode
    with open('/tmp/meetme-gradle.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        baseline = json.loads((out / 'base/source-after.json').read_text())
        current = {name: hashlib.sha256((REPO / name).read_bytes()).hexdigest() for name in baseline}
        if current != baseline:
            print(json.dumps({'status': 'SOURCE_DRIFT', 'evidence': str(out)}))
            return 2
        runtime = (out / 'base/evaluation-runtime.classpath').read_text().strip()
        java = str(Path(env.get('JAVA_HOME') or '/tmp/meetme-jdk17') / 'bin/java')
        with (out / 'private-creation.jsonl').open('w') as stream:
            test = subprocess.run([java, '-cp', runtime, 'Issue130PrivateCreationContractTest'],
                                  cwd=REPO, env=env, stdout=stream, stderr=subprocess.STDOUT,
                                  timeout=20, preexec_fn=lambda: os.umask(0o022))
        rows = []
        for line in (out / 'private-creation.jsonl').read_text().splitlines():
            try:
                rows.append(json.loads(line))
            except ValueError:
                pass
        after = {name: hashlib.sha256((REPO / name).read_bytes()).hexdigest() for name in baseline}
        result = {'privateCreation': rows[-1] if rows else None, 'exitCode': test.returncode,
                  'baseRanFull101': not args.private_only, 'sourceBeforeAfterEqual': baseline == after,
                  'umask': '022', 'evidence': str(out), 'paidCalls': 0, 'sealedDataRead': False}
        (out / 'receipt.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result))
        return test.returncode if baseline == after else 2


if __name__ == '__main__':
    raise SystemExit(main())
