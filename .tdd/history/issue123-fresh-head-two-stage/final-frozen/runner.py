"""Default dry-run. Parent alone freezes the plan and requests the one real invocation."""
import argparse
import datetime
from decimal import Decimal
import hashlib
import json
import os
import pathlib
import signal
import subprocess
import time

ROOT = pathlib.Path('/workspace/issue123-durable-budget/server')
BASE = ROOT / '.codex/tdd-evidence/issue123-fresh-head-two-stage'
FROZEN_PLAN_SHA256 = '3a9fd47f2e5da20ed69f895abcd0c045aceeaefc1e12d98d24121fd6e8028330'
HEAD = '4101038fd421351affa2a1fcdd5741083cb1a6b6'
GUARD = ROOT / '.tdd/verification/issue123-thought-signature-final-gate.json'
MANIFEST = ROOT / '.tdd/verification/issue123-thought-signature-source-manifest.json'
GUARD_SHA = 'f004c436455124ce3bdd31716be16a6b3105b6ba5787e886fa51a96b49330afd'
MANIFEST_SHA = '9db1ce591e8d60e77277688bc1b19bf5611e0ad8d81fa106c636b85ef40220fe'
ORACLE = ('docs/evaluation/issue123-live-two-stage-oracle.json', 'c69edcc525bde189765788edfd96fb05756c0e3a21d23cfcda357a7d52be1d64')
MODE = ('docs/evaluation/issue123-fresh-head-two-stage-oracle-mode.json', 'b40a1d20eec4464ca41cbaefb91341aadf0e01e68f1f6ed1b06f4cf08f79adb2')
COST = ('docs/verification/issue123-fresh-head-two-stage-cost-review.md', '51b1179fb74b6e995ad683941498cca8064e95440fc1bb5b7b835cb1171350a1')
SOURCE_SHAS = {
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderContract.kt': '7d1131259a0813c6622fe290712a4c2dfa32514c9f2e2adcf266eedfe79e4880',
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/TwoStagePreSubmissionCheckProvider.kt': 'd5d97bd7c6f8100736ace583347c5fe5db386b3a371b59a982f4afb8c2bbeef0',
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassificationContract.kt': 'c45fa35de6c7079b13d81f5a1c4d5193b4939a08dd2f3d3b511fc8b390b25334',
}
JAVA = '/tmp/meetme-jdk17/bin/java'
JAVAC = '/tmp/meetme-jdk17/bin/javac'
CLASS_DIRECTORY = pathlib.Path('/tmp/issue123-fresh-head-classes')
CLASSPATH_FILE = pathlib.Path('/tmp/issue123-decision-smoke-classpath.txt')
ORIGINAL_A = ROOT / '.codex/tdd-evidence/issue123-minimal-smoke/LIVE-KO-03-request.json'
A_SHA = 'fbc526b0d3fe7e014cd3fd379dd8a9d969ec8218c659ff2e60df0ba8e1d81a52'
EXPECTED_STAGES = {
    'CLASSIFY': ('https://api.openai.com/v1/decisions', 'OPENAI_API_KEY', 'a-request.json', 'classify-response.raw.json'),
    'COUNT': ('https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:countTokens', 'GEMINI_API_KEY', 'count-request.json', 'count-response.raw.json'),
    'GENERATE': ('https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent', 'GEMINI_API_KEY', 'generation-request.json', 'generation-response.raw.json'),
}


def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def load(path):
    def pairs(items):
        value = {}
        for key, item in items:
            if key in value:
                raise ValueError('DUPLICATE_JSON_KEY')
            value[key] = item
        return value
    return json.loads(pathlib.Path(path).read_bytes(), object_pairs_hook=pairs)


def write_new(path, raw):
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'wb') as handle:
        handle.write(raw)


def persist(path, record):
    temp = path.with_name(path.name + '.atomic')
    fd = os.open(temp, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'w') as handle:
        json.dump(record, handle, ensure_ascii=False, indent=2, allow_nan=False)
        handle.write('\n')
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temp, path)


def validate():
    assert len(FROZEN_PLAN_SHA256) == 64 and sha(BASE / 'execution-plan.json') == FROZEN_PLAN_SHA256
    plan = load(BASE / 'execution-plan.json')
    assert plan['executionPermitted'] is True and plan['sourceRevision'] == HEAD
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip() == HEAD
    assert plan['approvedAggregateUsd'] == '2.00'
    prior, new, total = (Decimal(plan[key]) for key in ('priorReservedUsd', 'maximumNewReservedUsd', 'cumulativeMaximumReservedUsd'))
    assert prior == Decimal('1.01330944') and new == Decimal('0.31854592')
    assert prior + new == total == Decimal('1.33185536') and total <= Decimal('2.00')
    assert plan['maximumNewHttpRequests'] == 3 and plan['automaticRetries'] == 0 and plan['productSharedTimeoutSeconds'] == 15
    assert sha(GUARD) == GUARD_SHA and sha(MANIFEST) == MANIFEST_SHA
    gate = load(GUARD)
    assert gate['guard']['exitCode'] == 0 and gate['source']['beforeEqualsAfterEqualsCurrent'] is True
    assert gate['source']['manifestSha256'] == MANIFEST_SHA and gate['source']['files'] == 557
    assert gate['junit']['allFreshAfterGuardStart'] is True and gate['junit']['failures'] == 0 and gate['junit']['errors'] == 0
    source = load(MANIFEST)['files']
    assert len(source) == 557 and all(sha(ROOT / path) == digest for path, digest in source.items())
    assert plan['sourceShas'] == SOURCE_SHAS and all(sha(ROOT / path) == digest for path, digest in SOURCE_SHAS.items())
    for key, expected in (('oracle', ORACLE), ('mode', MODE), ('costReview', COST)):
        assert plan[key] == {'path': expected[0], 'sha256': expected[1]}
        assert sha(ROOT / expected[0]) == expected[1]
    assert plan['harness']['path'] == '/tmp/Issue123FreshHeadTwoStageHarness.java'
    assert plan['callback']['path'] == '/tmp/issue123-fresh-head-http-callback.py'
    for key in ('harness', 'callback'):
        assert sha(plan[key]['path']) == plan[key]['sha256']
    assert plan['classpathSha256'] == sha(CLASSPATH_FILE)
    assert sha(ORIGINAL_A) == A_SHA and (BASE / 'a-request.json').read_bytes() == ORIGINAL_A.read_bytes()
    assert set(plan['stages']) == set(EXPECTED_STAGES)
    for stage, (url, key_env, request_name, response_name) in EXPECTED_STAGES.items():
        spec = plan['stages'][stage]
        assert spec['url'] == url and spec['keyEnv'] == key_env
        assert pathlib.Path(spec['requestPath']) == BASE / request_name
        assert pathlib.Path(spec['responsePath']) == BASE / response_name
        assert (BASE / request_name).stat().st_size <= 16384 and sha(BASE / request_name) == spec['requestSha256']
        assert not (BASE / response_name).exists() and not (BASE / (response_name + '.callback-metadata.json')).exists()
        assert not (BASE / (stage + '-consumed.json')).exists()
    assert not (BASE / 'live-invocation.json').exists() and not (BASE / 'live-two-stage.json').exists()
    assert not (BASE / 'stdout.json').exists() and not (BASE / 'stderr.txt').exists()
    return plan, source


def execute(plan, source):
    cp = str(CLASS_DIRECTORY) + ':' + CLASSPATH_FILE.read_text().strip()
    CLASS_DIRECTORY.mkdir(exist_ok=True)
    clean_env = {**os.environ, 'OPENAI_API_KEY': '', 'GEMINI_API_KEY': ''}
    compiled = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-cp', cp, '-d', str(CLASS_DIRECTORY), plan['harness']['path']], env=clean_env, capture_output=True, timeout=20)
    assert compiled.returncode == 0
    # Recheck authoritative files after compilation, before reserving/starting the sole invocation.
    validate()
    record = {'state': 'STARTED_UNKNOWN_HELD', 'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'planSha256': FROZEN_PLAN_SHA256, 'guardSha256': GUARD_SHA, 'sourceFilesVerified': len(source), 'priorReservedUsd': plan['priorReservedUsd'], 'maximumNewReservedUsd': plan['maximumNewReservedUsd'], 'cumulativeMaximumReservedUsd': plan['cumulativeMaximumReservedUsd'], 'approvedAggregateUsd': '2.00', 'automaticRetries': 0}
    path = BASE / 'live-invocation.json'
    write_new(path, (json.dumps(record) + '\n').encode())
    env = {**os.environ, 'ISSUE123_FRESH_HEAD_PLAN_SHA': FROZEN_PLAN_SHA256}
    command = [JAVA, '-cp', cp, 'Issue123FreshHeadTwoStageHarness', 'run-live', str(ORIGINAL_A), str(BASE), plan['callback']['path']]
    started = time.monotonic()
    try:
        with (BASE / 'stdout.json').open('xb') as out, (BASE / 'stderr.txt').open('xb') as err:
            os.chmod(BASE / 'stdout.json', 0o600)
            os.chmod(BASE / 'stderr.txt', 0o600)
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=out, stderr=err, start_new_session=True)
            try:
                process.wait(timeout=25)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                record['outerStartupGuardTimeout'] = True
            record['exitCode'] = process.returncode
        assert (BASE / 'stdout.json').stat().st_size <= 65536 and (BASE / 'stderr.txt').stat().st_size <= 65536
    except Exception as failure:
        record.update(state='STOP_LOCAL_FAILURE_UNKNOWN_HELD', exceptionType=type(failure).__name__)
    record.update(wholeJvmInvocationElapsedMs=round((time.monotonic() - started) * 1000, 3), finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), actualNewHttpAttempts=len(list(BASE.glob('*-consumed.json'))))
    unchanged = all(sha(ROOT / item) == digest for item, digest in source.items())
    record['sourceUnchangedAfterInvocation'] = unchanged
    if record['state'] == 'STARTED_UNKNOWN_HELD':
        record['state'] = 'OBSERVED_PENDING_INDEPENDENT_SEMANTIC_REVIEW_INVOICE_UNVERIFIED'
    persist(path, record)
    print(json.dumps(record, ensure_ascii=False, allow_nan=False), flush=True)
    # Safe reports contain parsed question/usage metadata, never the opaque Part signature.
    if (BASE / 'live-two-stage.json').exists():
        report = load(BASE / 'live-two-stage.json')
        print(json.dumps(report, ensure_ascii=False, allow_nan=False), flush=True)
    return 0 if record.get('exitCode') == 0 and unchanged else 2


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--execute-frozen', action='store_true')
    args = parser.parse_args()
    if not args.execute_frozen:
        print(json.dumps({'mode': 'DRY_RUN_NO_NETWORK', 'freezeReady': len(FROZEN_PLAN_SHA256) == 64, 'requiredPlanPath': str(BASE / 'execution-plan.json'), 'planSha256': FROZEN_PLAN_SHA256, 'sourceRevision': HEAD, 'automaticRetries': 0}))
        return 0
    plan, source = validate()
    return execute(plan, source)


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as failure:
        print(json.dumps({'accepted': False, 'failureCode': 'CONFIGURATION', 'exceptionType': type(failure).__name__, 'actualHttpAttemptsByRunnerDirectly': 0}), flush=True)
        raise SystemExit(2)
