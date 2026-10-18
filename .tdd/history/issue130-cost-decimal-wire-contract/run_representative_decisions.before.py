"""Bounded evaluation-only root runner; no production activation or model calls by default."""
import argparse
import base64
from decimal import Decimal
import hashlib
import json
import os
import re
from pathlib import Path
import signal
import subprocess
import tempfile
import time

SOURCES = (
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassificationContract.kt',
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/OpenAiAmbiguityClassifier.kt',
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassifierOptions.kt',
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/TwoStagePreSubmissionCheckProvider.kt',
)
HELPERS = ('evaluation/issue130/Issue130RepresentativeDecisionsCli.java', 'evaluation/issue130/run_representative_decisions.py')


class EvaluationFailure(Exception):
    pass


def need(value, code='CONFIGURATION'):
    if not value:
        raise EvaluationFailure(code)


def canonical(path):
    path = Path(path).absolute()
    need(path == Path(os.path.normpath(path)))
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current = current / part
        need(not current.is_symlink())
    return path


def sha(path):
    path = canonical(path)
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(65536), b''):
            digest.update(chunk)
    return digest.hexdigest()


def load(path):
    def pairs(items):
        value = {}
        for key, item in items:
            need(key not in value)
            value[key] = item
        return value
    def invalid(value):
        raise EvaluationFailure('CONFIGURATION')
    with canonical(path).open('rb') as handle:
        raw = handle.read(4194305)
    need(len(raw) <= 4194304)
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=invalid)


def evidence(value):
    need(isinstance(value, dict) and isinstance(value.get('path'), str) and isinstance(value.get('sha256'), str))
    need(sha(value['path']) == value['sha256'])


def amount(node, name):
    value = node.get(name)
    need(isinstance(value, str) and len(value) <= 64)
    result = Decimal(value)
    need(result.is_finite() and result >= 0)
    return result


def validate_environment_ca(plan, environment=None):
    if 'environmentCa' not in plan:
        return
    need(plan.get('transportMode') == 'ENVIRONMENT_HTTPS_PROXY')
    metadata = plan['environmentCa']
    need(isinstance(metadata, dict) and set(metadata) == {'environmentVariable', 'pemFileSha256', 'certificateDerSha256'})
    need(metadata['environmentVariable'] == 'NODE_EXTRA_CA_CERTS')
    for name in ('pemFileSha256', 'certificateDerSha256'):
        need(isinstance(metadata[name], str) and re.fullmatch('[0-9a-f]{64}', metadata[name]) is not None)
    source = os.environ if environment is None else environment
    value = source.get('NODE_EXTRA_CA_CERTS')
    need(isinstance(value, str) and bool(value) and not value.isspace() and Path(value).is_absolute())
    path = canonical(value)
    need(path.is_file())
    with path.open('rb') as handle:
        raw = handle.read(16385)
    need(0 < len(raw) <= 16384 and hashlib.sha256(raw).hexdigest() == metadata['pemFileSha256'])
    pem = raw.decode('ascii')
    block = re.fullmatch(r'\s*-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\s]+?)\s*-----END CERTIFICATE-----\s*', pem, flags=re.ASCII)
    need(block is not None)
    der = base64.b64decode(re.sub(r'\s', '', block.group(1), flags=re.ASCII), validate=True)
    need(bool(der) and hashlib.sha256(der).hexdigest() == metadata['certificateDerSha256'])
    # X.509 lifetime, CA usage and self-signature validation is performed by Java before HTTP.


def java_tools(environment):
    value = environment['JAVA_HOME'] if 'JAVA_HOME' in environment else '/tmp/meetme-jdk17'
    need(isinstance(value, str) and bool(value) and os.path.isabs(value))
    home = canonical(value)
    need(home.is_dir())
    tools = []
    for name in ('javac', 'java'):
        tool = canonical(home / 'bin' / name)
        need(tool.is_file() and os.access(tool, os.X_OK))
        tools.append(str(tool))
    return tuple(tools)


def validate_plan(plan_path, index_path, output_path, environment=None):
    try:
        plan = load(plan_path)
        need(isinstance(plan, dict) and plan['workItem'] == 'issue130-representative-decisions')
        candidate = 'evaluationProfile' in plan
        if candidate:
            need(plan['evaluationProfile'] == 'CANDIDATE_A24_USD3_V1' and plan.get('transportMode') == 'ENVIRONMENT_HTTPS_PROXY' and isinstance(plan.get('environmentCa'), dict))
        count = 24 if candidate else 16
        suite_seconds = 420 if candidate else 300
        root = canonical(plan['sourceRoot'])
        revision = subprocess.run(['git', '-C', str(root), 'rev-parse', 'HEAD'], capture_output=True, text=True)
        need(revision.returncode == 0 and revision.stdout.strip() == plan['sourceRevision'])
        need(canonical(output_path) == canonical(plan['outputDirectory']))
        evidence(plan['sourceManifest'])
        files = load(plan['sourceManifest']['path'])['files']
        need(isinstance(files, dict) and bool(files))
        for relative, digest in files.items():
            file = canonical(root / relative)
            need(file.is_relative_to(root) and sha(file) == digest)
        runtime = plan['runtimeFiles']
        need(isinstance(runtime, dict) and bool(runtime))
        for file, digest in runtime.items():
            need(sha(file) == digest)
        need(all(str(root / file) in runtime for file in (*SOURCES, *HELPERS)))
        need(plan['preparedIndexSha256'] == sha(index_path))
        index = load(index_path)
        need(isinstance(index, dict) and set(index) == {'status', 'records'} and index['status'] == 'WIRES_PREPARED_NOT_EXECUTION')
        records = index['records']
        need(isinstance(records, list) and len(records) == count)
        ids = set()
        for record in records:
            need(isinstance(record, dict) and set(record) == {'opaque_call_id', 'request_path', 'request_sha256', 'request_bytes'})
            need(record['opaque_call_id'] not in ids)
            ids.add(record['opaque_call_id'])
            need(type(record['request_bytes']) is int and 0 < record['request_bytes'] <= 16384)
        need(plan['endpoint'] == 'https://api.openai.com/v1/decisions' and plan['model'] == 'gpt-6-luna')
        need(type(plan['minimumConfidence']) in (int, float) and plan['minimumConfidence'] == .8)
        need(plan['operatingConfigurationChanged'] is False)
        fixed = {'caseCount': count, 'perCallSeconds': 15, 'suiteSeconds': suite_seconds, 'concurrency': 1, 'maximumHttpAttempts': count, 'countCalls': 0, 'generationCalls': 0, 'automaticRetries': 0, 'redirects': 0, 'fallbacks': 0, 'requestByteCap': 16384, 'responseByteCap': 16384}
        need(all(type(plan.get(key)) is int and plan[key] == value for key, value in fixed.items()))
        validate_environment_ca(plan, environment)
        cost = plan['costProfile']
        need(isinstance(cost, dict))
        proxy = 'transportMode' in plan
        if proxy:
            need(plan['transportMode'] == 'ENVIRONMENT_HTTPS_PROXY')
        expected = {'inputUsdPerMillion': '.10', 'margin': '2.2', 'perCallReservedUsd': '.01441792', 'priorHeldUsd': '1.79322880' if candidate else '1.56254208' if proxy else '1.33185536', 'maximumNewReservedUsd': '.34603008' if candidate else '.23068672', 'maximumCumulativeReservedUsd': '2.13925888' if candidate else '1.79322880' if proxy else '1.56254208', 'approvedAggregateUsd': '3.00' if candidate else '2.00'}
        need(all(amount(cost, key) == Decimal(value) for key, value in expected.items()))
        need(type(cost['inputTokenProxy']) is int and cost['inputTokenProxy'] == 65536 and cost['officialInvoiceHardCapProven'] is False)
        need(amount(cost, 'perCallReservedUsd') * count == amount(cost, 'maximumNewReservedUsd'))
        need(amount(cost, 'priorHeldUsd') + amount(cost, 'maximumNewReservedUsd') == amount(cost, 'maximumCumulativeReservedUsd') <= amount(cost, 'approvedAggregateUsd'))
        evidence(cost['review'])
        if proxy:
            previous_evidence = plan['priorReservation']
            need(Path(previous_evidence['path']).is_absolute())
            evidence(previous_evidence)
            previous = load(previous_evidence['path'])
            need(isinstance(previous, dict) and previous.get('status') == 'FULL_SUITE_RESERVED_BEFORE_EXECUTION')
            retained = {'previousRetainedReservationUsd': '1.56254208' if candidate else '1.33185536', 'newReservationUsd': '.23068672', 'cumulativeReservationUsd': '1.79322880' if candidate else '1.56254208', 'approvedAggregateUsd': '2.00'}
            need(all(amount(previous, key) == Decimal(value) for key, value in retained.items()))
            need(type(previous.get('refunds')) is int and previous['refunds'] == 0)
            if candidate:
                need(previous.get('retainedUnknownsRefunded') is False)
        need(not (canonical(output_path) / '.suite-consumed.json').exists(), 'ALREADY_CONSUMED')
        return plan
    except EvaluationFailure:
        raise
    except Exception:
        raise EvaluationFailure('CONFIGURATION') from None


def main(argv=None):
    started = time.monotonic()
    deadline = started + 300
    def remaining():
        value = deadline - time.monotonic()
        need(value > 0, 'TIMEOUT')
        return value
    try:
        parser = argparse.ArgumentParser(add_help=False)
        parser.add_argument('--plan')
        parser.add_argument('--index')
        parser.add_argument('--output')
        parser.add_argument('--classpath-file')
        parser.add_argument('--execute-frozen', action='store_true')
        args = parser.parse_args(argv)
        if not any((args.plan, args.index, args.output, args.classpath_file)) and not args.execute_frozen:
            print(json.dumps({'mode': 'DRY_RUN_NO_NETWORK', 'executionPermitted': False}))
            return 0
        need(all((args.plan, args.index, args.output, args.classpath_file)))
        plan = validate_plan(Path(args.plan), Path(args.index), Path(args.output))
        deadline = started + plan['suiteSeconds']
        if not args.execute_frozen:
            print(json.dumps({'mode': 'DRY_RUN_NO_NETWORK', 'executionPermitted': False, 'caseCount': plan['caseCount'], 'generationCalls': 0, 'countCalls': 0}))
            return 0
        remaining()
        digest = sha(args.plan)
        need(os.environ.get('ISSUE130_FROZEN_PLAN_SHA256') == digest and os.environ.get('ISSUE130_EXECUTE_FROZEN') == digest)
        need(bool(os.environ.get('OPENAI_API_KEY', '').strip()))
        cp_path = canonical(args.classpath_file)
        need(plan['runtimeFiles'].get(str(cp_path)) == sha(cp_path))
        cp = cp_path.read_text().strip()
        need(bool(cp))
        root = canonical(plan['sourceRoot'])
        javac, java = java_tools(os.environ)
        with tempfile.TemporaryDirectory(prefix='issue130-frozen-classes-') as directory:
            clean_env = {**os.environ, 'OPENAI_API_KEY': '', 'GEMINI_API_KEY': ''}
            compiled = subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-cp', cp, '-d', directory, str(root / HELPERS[0])], capture_output=True, text=True, env=clean_env, timeout=remaining())
            remaining()
            need(compiled.returncode == 0)
            # A final positive budget, never a minimum floor that permits expired work.
            remaining_millis = int(remaining() * 1000)
            need(remaining_millis > 0, 'TIMEOUT')
            env = {**os.environ, 'ISSUE130_SUITE_REMAINING_MILLIS': str(remaining_millis)}
            command = [java, '-cp', directory + os.pathsep + cp, 'Issue130RepresentativeDecisionsCli', 'run-suite', args.plan, args.index, args.output]
            process = None
            try:
                process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env, start_new_session=True)
                stdout, stderr = process.communicate(timeout=remaining())
                remaining()
            except (subprocess.TimeoutExpired, EvaluationFailure):
                if process is not None and process.poll() is None:
                    os.killpg(process.pid, signal.SIGKILL)
                    try:
                        process.communicate(timeout=20)
                    except subprocess.TimeoutExpired:
                        pass
                raise EvaluationFailure('TIMEOUT') from None
            need(len(stdout) <= 65536 and len(stderr) <= 65536)
            need(process.returncode == 0)
            # Java persists private per-case observations; wrapper stdout has only suite status.
            report = json.loads(stdout)
            need(isinstance(report, dict) and report.get('status') == 'COMPLETED')
            print(json.dumps({'mode': 'FROZEN_A_ONLY_OBSERVED', 'status': report['status'], 'physicalDecisionsCalls': report.get('physicalDecisionsCalls'), 'physicalGenerationCalls': 0, 'countCalls': 0, 'wholeWrapperElapsedMs': (time.monotonic() - started) * 1000, 'invoiceHardCapClaimed': False}))
            return 0
    except subprocess.TimeoutExpired:
        print(json.dumps({'accepted': False, 'failureCode': 'TIMEOUT'}))
        return 2
    except Exception as failure:
        code = str(failure) if isinstance(failure, EvaluationFailure) else 'CONFIGURATION'
        print(json.dumps({'accepted': False, 'failureCode': code}))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
