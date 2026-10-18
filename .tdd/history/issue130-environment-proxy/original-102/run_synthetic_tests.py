#!/usr/bin/env python3
"""Independent issue130 synthetic checks; never reads evaluation case/gold paths or real keys."""
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


def fingerprints():
    selected = list((REPO / 'src/main').rglob('*.kt'))
    selected += list((REPO / 'evaluation/issue130').rglob('*.java'))
    selected += list((REPO / 'evaluation/issue130').rglob('*.py'))
    return {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(selected)}


def run_logged(command, env, target, timeout):
    with target.open('w') as stream:
        return subprocess.run(command, cwd=REPO, env=env, stdout=stream,
                              stderr=subprocess.STDOUT, timeout=timeout).returncode


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--compile-only', action='store_true')
    parser.add_argument('--java-only', action='store_true')
    parser.add_argument('--java-test', action='append', default=[])
    parser.add_argument('--output')
    args = parser.parse_args()
    out = Path(args.output) if args.output else Path(tempfile.mkdtemp(prefix='issue130-synthetic-'))
    out.mkdir(parents=True, exist_ok=True)
    out.chmod(0o700)
    env = os.environ.copy()
    env['JAVA_HOME'] = env.get('JAVA_HOME') or '/tmp/meetme-jdk17'
    env['GRADLE_USER_HOME'] = env.get('GRADLE_USER_HOME') or '/tmp/meetme-gradle-home'
    env['OPENAI_API_KEY'] = ''
    env['GEMINI_API_KEY'] = ''
    java = str(Path(env['JAVA_HOME']) / 'bin/java')
    javac = str(Path(env['JAVA_HOME']) / 'bin/javac')
    cpfile = out / 'production-test-runtime.classpath'
    classes = out / 'classes'
    classes.mkdir(exist_ok=True)
    started = time.monotonic()
    with open('/tmp/meetme-gradle.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        before = fingerprints()
        (out / 'source-before.json').write_text(json.dumps(before, indent=2) + '\n')
        command = ['./gradlew', '--no-daemon', '--console=plain', '--max-workers=2', '-I',
                   'docs/verification/issue-107-native-browser.init.gradle',
                   f'-Dmeetme.issue107.native.classpath-file={cpfile}', 'issue107NativeRuntimeClasspath']
        gradle = run_logged(command, env, out / 'compile-production.log', 300)
        if gradle:
            print(json.dumps({'status': 'SETUP_FAILURE', 'stage': 'production compile', 'exitCode': gradle, 'evidence': str(out)}))
            return 2
        cp = cpfile.read_text().strip()
        sources = [REPO / 'evaluation/issue130/Issue130RepresentativeDecisionsCli.java']
        sources += sorted((REPO / 'evaluation/issue130/tests').glob('*.java'))
        compile_java = run_logged([javac, '--release', '17', '-encoding', 'UTF-8', '-cp', cp,
                                  '-d', str(classes), *map(str, sources)], env, out / 'compile-evaluation.log', 60)
        if compile_java:
            print(json.dumps({'status': 'SETUP_FAILURE', 'stage': 'evaluation compile', 'exitCode': compile_java, 'evidence': str(out)}))
            return 2
        runtime = os.pathsep.join([str(classes), cp])
        (out / 'evaluation-runtime.classpath').write_text(runtime)
        java_exit = python_exit = 0
        if not args.compile_only:
            java_exit = run_logged([java, f'-Dissue130.sourceRoot={REPO}', '-cp', runtime,
                                    'Issue130SyntheticContractTest', *args.java_test], env,
                                   out / 'java-tests.jsonl', 300)
            if not args.java_only and not args.java_test:
                env['ISSUE130_TEST_CLASSPATH'] = runtime
                env['ISSUE130_TEST_SOURCE_ROOT'] = str(REPO)
                env['ISSUE130_TEST_JAVA'] = java
                python_exit = run_logged([sys.executable, str(REPO / 'evaluation/issue130/tests/test_representative_decisions.py')],
                                         env, out / 'python-tests.log', 120)
        after = fingerprints()
        (out / 'source-after.json').write_text(json.dumps(after, indent=2) + '\n')
        equal = before == after
        rows = []
        if (out / 'java-tests.jsonl').exists():
            for line in (out / 'java-tests.jsonl').read_text().splitlines():
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
        summary = {'status': 'COMPILE_ONLY' if args.compile_only else 'PASS' if not (java_exit or python_exit) else 'FAILED',
                   'productionCompileExitCode': gradle, 'evaluationCompileExitCode': compile_java,
                   'javaExitCode': java_exit, 'pythonExitCode': python_exit,
                   'javaSummary': rows[-1] if rows else None, 'sourceBeforeAfterEqual': equal,
                   'sourceFingerprintCount': len(before), 'durationSeconds': round(time.monotonic() - started, 2),
                   'evidence': str(out), 'actualProviderCalls': 0, 'sealedDataRead': False}
        (out / 'receipt.json').write_text(json.dumps(summary, indent=2) + '\n')
        print(json.dumps(summary))
        return 0 if equal and not (java_exit or python_exit) else 1


if __name__ == '__main__':
    raise SystemExit(main())
