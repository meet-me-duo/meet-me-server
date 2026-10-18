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


SAFE_JAVA_TEST_IDS = frozenset({
    'PX01_lowercase_selected_unresolved',
    'PX02_uppercase_only_selected',
    'PX03_absent_port_defaults80',
    'PX04_root_path_allowed',
    'PX05_NO_PROXY_never_selects_DIRECT',
    'PX06_legacy_does_not_infer_env_mode',
    'PX07_factory_never_reads_API_keys',
    'PX08_missing_proxy_rejects_no_DIRECT',
    'PX09_invalid_lower_never_falls_back_upper',
    'PX_BAD_URI_9',
    'PX_BAD_URI_10',
    'PX_BAD_URI_11',
    'PX_BAD_URI_12',
    'PX_BAD_URI_13',
    'PX_BAD_URI_14',
    'PX_BAD_URI_15',
    'PX_BAD_URI_16',
    'PX_BAD_URI_17',
    'PX_BAD_URI_18',
    'PX_BAD_URI_19',
    'PX_BAD_URI_20',
    'PX_BAD_URI_21',
    'PX_BAD_URI_22',
    'PX_BAD_URI_23',
    'PX_BAD_MODE_24',
    'PX_BAD_MODE_25',
    'PX_BAD_MODE_26',
    'PX_BAD_MODE_27',
    'PX_BAD_MODE_28',
    'PB01_new_mode_valid_profile_and_hold_persisted',
    'PB02_new_mode_rejects_old_hold_rollback',
    'PB03_new_mode_requires_previous_evidence',
    'PB04_previous_evidence_hash_mismatch',
    'PB05_previous_evidence_amount_mismatch',
    'PB06_previous_status_mismatch',
    'PB_REFUNDS_invalid_35',
    'PB_REFUNDS_invalid_36',
    'PB_REFUNDS_invalid_37',
    'PB_REFUNDS_invalid_38',
    'PB_REFUNDS_invalid_39',
    'PB07_unknown_mode_not_legacy_profile',
    'PB08_new_profile_connection_failure_holds_total',
    'PN01_parameterized_TLS_valid_SAN_production_classifier_control',
    'PN02_parameterized_TLS_trusted_wrong_SAN_control',
    'PC01_actual_Python_CLI_ENV_proxy_TLS16_and_replay0',
    'PC02_untrusted_CA_never_emits_POST_or_DIRECT',
    'PC03_wrong_hostname_even_trusted_CA_rejected',
    'PC04_503_retryafter0_one_POST',
    'PC05_redirect_one_POST_no_follow',
})
SAFE_JAVA_ERROR_TYPES = frozenset({
    'NullPointerException', 'IllegalArgumentException', 'IllegalStateException',
    'IOException', 'SSLException', 'SSLHandshakeException', 'SocketException',
    'SocketTimeoutException', 'TimeoutException', 'SecurityException',
    'RuntimeException', 'EvaluationException', 'InterruptedException',
})


def safe_child_observation(value):
    """Reconstruct a fixed projection; never return arbitrary child diagnostics."""
    if type(value) is not dict:
        return None
    if type(value.get('phase')) is not str or value['phase'] not in {'PYTHON_CLI', 'JAVA_CLI', 'REPLAY'}:
        return None
    if type(value.get('stage')) is not str or value['stage'] not in {'CHILD_START_FAILED', 'CHILD_TIMEOUT', 'STDOUT_REJECTED',
                                'STDERR_REJECTED', 'EXIT_NONZERO', 'MISSING_COMPLETED', 'FINISHED'}:
        return None
    numbers = {'exitCode': (-255, 255), 'stdoutBytes': (0, 1048576),
               'stderrBytes': (0, 1048576)}
    booleans = ('inheritedHomePresent', 'inheritedHomeCanonical', 'selectedHomeDirectory',
                'selectedHomeCanonical', 'selectedJavaRegular', 'selectedJavacRegular')
    for name, (minimum, maximum) in numbers.items():
        if name not in value:
            return None
        item = value[name]
        if item is not None and (type(item) is not int or not minimum <= item <= maximum):
            return None
    if any(type(value.get(name)) is not bool for name in booleans):
        return None
    return {name: value[name] for name in ('phase', 'stage', *numbers, *booleans)}


def safe_java_failures(rows):
    """Only frozen public case identifiers and fixed labels may reach CI stdout."""
    failures = []
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get('test'), str) or row['test'] not in SAFE_JAVA_TEST_IDS:
            continue
        status = row.get('status')
        if status == 'ASSERTION_FAILURE':
            kind = 'AssertionError'
        elif status == 'SETUP_OR_UNEXPECTED_ERROR':
            kind = row.get('type')
            if not isinstance(kind, str) or kind not in SAFE_JAVA_ERROR_TYPES:
                kind = 'UNEXPECTED_ERROR'
        else:
            continue
        failure = {'test': row['test'], 'status': status, 'type': kind}
        child = safe_child_observation(row.get('childObservation'))
        if child is not None:
            failure['childObservation'] = child
        failures.append(failure)
    return failures


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
                  'javaFailures': safe_java_failures(rows),
                  'sourceBeforeAfterEqual': before == after, 'durationSeconds': round(time.monotonic() - started, 3),
                  'evidence': str(out), 'paidCalls': 0, 'sealedDataRead': False}
        (out / 'receipt.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result))
        return 0 if not (java_exit or python_exit) and before == after else 1


if __name__ == '__main__':
    raise SystemExit(main())
