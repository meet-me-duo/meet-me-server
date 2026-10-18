#!/usr/bin/env python3
"""Compile isolated JDK-only guard decisions; no application or network is started."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    java = Path(args.java).resolve(strict=True)
    javac = java.parent / 'javac'
    root = Path(__file__).resolve().parent
    output = Path(args.output)
    with open('/tmp/meetme-gradle.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        output.mkdir(parents=True, exist_ok=False)
        classes = output / 'classes'
        classes.mkdir()
        sources = [root / 'ManagedBrowserLoopbackGuard.java', root / 'ManagedBrowserLoopbackGuardContractTest.java', Path(__file__).resolve()]
        digest = lambda: {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
        before = digest()
        env = dict(os.environ, OPENAI_API_KEY='', GEMINI_API_KEY='', JAVA_HOME=str(java.parent.parent))
        # Do not inherit environment-injected agents, trust or proxy options into this synthetic test.
        for key in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'):
            env.pop(key, None)
        start = time.monotonic()
        compile_run = subprocess.run([str(javac), '-d', str(classes), *map(str, sources[:2])], env=env, capture_output=True, text=True, timeout=60)
        (output / 'compile.log').write_text(compile_run.stdout + compile_run.stderr)
        receipt = {'compileExitCode': compile_run.returncode, 'sourceBefore': before}
        if compile_run.returncode == 0:
            run = subprocess.run([str(java), '-cp', str(classes), 'com.meetme.integration.managed.ManagedBrowserLoopbackGuardContractTest'], env=env, capture_output=True, text=True, timeout=30)
            (output / 'run.log').write_text(run.stdout + run.stderr)
            receipt.update(json.loads(run.stdout.splitlines()[0]))
            receipt['exitCode'] = run.returncode
        else:
            receipt.update(tests=0, failures=0, errors=0, classification='COMPILE_SETUP_ERROR_NOT_RED')
        receipt['sourceAfter'] = digest()
        receipt['sourceBeforeAfterEqual'] = before == receipt['sourceAfter']
        receipt['elapsedSeconds'] = time.monotonic() - start
        (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print(json.dumps(receipt))
        return 0 if compile_run.returncode == 0 and receipt.get('exitCode') == 0 else 1


if __name__ == '__main__':
    raise SystemExit(main())
