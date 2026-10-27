"""Frozen evaluation callback. No retries. Only the parent may invoke through its frozen runner."""
import datetime
import decimal
import email.utils
import hashlib
import json
import math
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path('/workspace/issue123-durable-budget/server')
BASE = ROOT / '.codex/tdd-evidence/issue123-fresh-head-two-stage'
GUARD = ROOT / '.tdd/verification/issue123-thought-signature-final-gate.json'
GUARD_SHA = 'f004c436455124ce3bdd31716be16a6b3105b6ba5787e886fa51a96b49330afd'
URLS = {
    'CLASSIFY': ('https://api.openai.com/v1/decisions', 'OPENAI_API_KEY', 'a-request.json', 'classify-response.raw.json'),
    'COUNT': ('https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:countTokens', 'GEMINI_API_KEY', 'count-request.json', 'count-response.raw.json'),
    'GENERATE': ('https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent', 'GEMINI_API_KEY', 'generation-request.json', 'generation-response.raw.json'),
}
LIMIT = 16384


def sha(data):
    return hashlib.sha256(data).hexdigest()


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('DUPLICATE_JSON_KEY')
            result[key] = value
        return result
    def invalid_constant(value):
        raise ValueError('NONFINITE_JSON')
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=invalid_constant)


def bounded_read(path):
    with pathlib.Path(path).open('rb') as handle:
        raw = handle.read(LIMIT + 1)
    if len(raw) > LIMIT:
        raise ValueError('BODY_LIMIT')
    return raw


def atomic_record(path, value):
    temp = path.with_name(path.name + '.atomic')
    fd = os.open(temp, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'w') as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2, allow_nan=False)
        handle.write('\n')
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temp, path)


def redacted(raw):
    for name in ('GEMINI_API_KEY', 'OPENAI_API_KEY'):
        key = os.environ.get(name)
        if key:
            raw = raw.replace(key.encode(), b'[REDACTED]')
    return raw


def safe_text(value, maximum):
    value = redacted(str(value).encode()).decode('utf-8', 'replace')
    return ''.join(char if char.isprintable() else '?' for char in value)[:maximum]


def retry_after(headers):
    raw = headers.get('Retry-After')
    if raw is None:
        return None
    if len(raw) > 120:
        return 'UNPARSEABLE_OR_OVER_60'
    try:
        if raw.isascii() and raw.isdecimal():
            seconds = int(raw)
        else:
            parsed = email.utils.parsedate_to_datetime(raw)
            if parsed.tzinfo is None:
                return 'UNPARSEABLE_OR_OVER_60'
            seconds = max(0, parsed.timestamp() - time.time())
        return seconds if math.isfinite(seconds) and 0 <= seconds <= 60 else 'UNPARSEABLE_OR_OVER_60'
    except Exception:
        return 'UNPARSEABLE_OR_OVER_60'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def validate(stage, request_arg, response_arg, remaining_arg):
    assert stage in URLS
    raw_plan = bounded_read(BASE / 'execution-plan.json')
    frozen = os.environ['ISSUE123_FRESH_HEAD_PLAN_SHA']
    assert len(frozen) == 64 and sha(raw_plan) == frozen
    plan = strict_json(raw_plan)
    assert plan['executionPermitted'] is True
    assert plan['sourceRevision'] == '4101038fd421351affa2a1fcdd5741083cb1a6b6'
    assert plan['approvedAggregateUsd'] == '2.00'
    assert decimal.Decimal(plan['priorReservedUsd']) == decimal.Decimal('1.01330944')
    assert decimal.Decimal(plan['maximumNewReservedUsd']) == decimal.Decimal('0.31854592')
    assert decimal.Decimal(plan['cumulativeMaximumReservedUsd']) == decimal.Decimal('1.33185536') <= decimal.Decimal('2')
    assert plan['maximumNewHttpRequests'] == 3 and plan['automaticRetries'] == 0
    assert plan['productSharedTimeoutSeconds'] == 15
    assert sha(GUARD.read_bytes()) == GUARD_SHA
    gate = strict_json(GUARD.read_bytes())
    assert gate['guard']['exitCode'] == 0 and gate['source']['beforeEqualsAfterEqualsCurrent'] is True
    invocation = strict_json(bounded_read(BASE / 'live-invocation.json'))
    assert invocation['planSha256'] == frozen and invocation['state'] == 'STARTED_UNKNOWN_HELD'
    assert plan['callback']['path'] == str(pathlib.Path(__file__).resolve())
    assert sha(pathlib.Path(__file__).read_bytes()) == plan['callback']['sha256']
    spec = plan['stages'][stage]
    url, key_env, request_name, response_name = URLS[stage]
    assert spec['url'] == url and spec['keyEnv'] == key_env
    request = pathlib.Path(request_arg).resolve()
    response = pathlib.Path(response_arg).resolve()
    assert request == (BASE / request_name).resolve() == pathlib.Path(spec['requestPath']).resolve()
    assert response == (BASE / response_name).resolve() == pathlib.Path(spec['responsePath']).resolve()
    assert request.is_relative_to(BASE.resolve()) and response.is_relative_to(BASE.resolve())
    assert not response.exists()
    body = bounded_read(request)
    assert sha(body) == spec['requestSha256']
    assert not (BASE / (stage + '-consumed.json')).exists()
    if stage in ('COUNT', 'GENERATE'):
        upstream_stage, upstream_file = ('CLASSIFY', 'classify-response.raw.json') if stage == 'COUNT' else ('COUNT', 'count-response.raw.json')
        upstream = strict_json(bounded_read(BASE / (upstream_stage + '-consumed.json')))
        assert upstream['httpStatus'] == 200 and upstream['state'] == 'HTTP_OBSERVED_INVOICE_UNVERIFIED'
        assert sha(bounded_read(BASE / upstream_file)) == upstream['storedResponseSha256']
    if stage == 'GENERATE':
        count = strict_json(bounded_read(BASE / 'count-response.raw.json'))['totalTokens']
        assert type(count) is int and 1 <= count <= 4096
    if stage in ('COUNT', 'GENERATE'):
        payload = strict_json(body)
        generation = payload['generateContentRequest'] if stage == 'COUNT' else payload
        config = generation['generationConfig']
        assert config['candidateCount'] == 1 and config['maxOutputTokens'] == 2048
        assert config['thinkingConfig'] == {'thinkingLevel': 'LOW'}
        if stage == 'COUNT':
            assert generation['model'] == 'models/gemini-3.8-flash'
            generation = {key: value for key, value in generation.items() if key != 'model'}
            assert generation == strict_json(bounded_read(BASE / 'generation-request.json'))
    millis = int(remaining_arg)
    assert 1 <= millis <= 15000
    key = os.environ.get(key_env)
    assert key
    return plan, spec, body, response, millis / 1000.0, key


def main(argv):
    stage, request_arg, response_arg, remaining_arg = argv
    plan, spec, body, target, remaining, key = validate(stage, request_arg, response_arg, remaining_arg)
    started = time.monotonic()
    deadline = started + remaining
    path = BASE / (stage + '-consumed.json')
    record = {'stage': stage, 'attempts': 1, 'state': 'UNKNOWN_HELD', 'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'requestSha256': sha(body), 'remainingMillisAtCallback': int(remaining * 1000), 'automaticRetries': 0}
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'w') as handle:
        json.dump(record, handle)
        handle.write('\n')
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
    headers['Authorization' if stage == 'CLASSIFY' else 'x-goog-api-key'] = 'Bearer ' + key if stage == 'CLASSIFY' else key
    request = urllib.request.Request(spec['url'], data=body, headers=headers, method='POST')
    rc = 1
    try:
        try:
            reply = urllib.request.build_opener(NoRedirect).open(request, timeout=max(.001, deadline - time.monotonic()))
        except urllib.error.HTTPError as failure:
            reply = failure
        with reply:
            status = reply.status
            record.update(state='HEADERS_OBSERVED_BODY_INCOMPLETE', httpStatus=status, headersObservedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), headersElapsedMs=round((time.monotonic() - started) * 1000, 3), contentType=safe_text(reply.headers.get('Content-Type', ''), 120), retryAfterSeconds=retry_after(reply.headers))
            atomic_record(path, record)
            chunks, size, first = [], 0, True
            while True:
                if time.monotonic() >= deadline:
                    raise TimeoutError()
                part = reply.read(min(2048, LIMIT + 1 - size))
                if not part:
                    break
                if first:
                    record['firstBodyChunkElapsedMs'] = round((time.monotonic() - started) * 1000, 3)
                    first = False
                chunks.append(part)
                size += len(part)
                record.update(bodyBytesRead=size, lastProgressAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), lastProgressElapsedMs=round((time.monotonic() - started) * 1000, 3))
                atomic_record(path, record)
                if size > LIMIT:
                    raise ValueError('BODY_LIMIT')
            if time.monotonic() >= deadline:
                raise TimeoutError()
            raw = b''.join(chunks)
            safe = redacted(raw)
            record.update(responseSha256=sha(raw), storedResponseSha256=sha(safe), storedBodySanitized=raw != safe, responseBytes=len(raw))
            if status == 200:
                strict_json(safe)
                fd = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                with os.fdopen(fd, 'wb') as handle:
                    handle.write(safe)
                record.update(state='HTTP_OBSERVED_INVOICE_UNVERIFIED')
                rc = 0
            else:
                try:
                    error = strict_json(safe).get('error', {})
                    if isinstance(error, dict):
                        record['sanitizedError'] = {name: safe_text(error[name], 512 if name == 'message' else 80) for name in ('code', 'status', 'message') if name in error and type(error[name]) in (str, int)}
                except Exception:
                    record['errorBodyParsed'] = False
                record.update(state='STOP_HTTP_FAILURE_UNKNOWN_HELD')
    except Exception as failure:
        record.update(state='STOP_TIMEOUT_OR_LOCAL_FAILURE_UNKNOWN_HELD', exceptionType=type(failure).__name__)
    record.update(elapsedHttpMs=round((time.monotonic() - started) * 1000, 3), finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
    atomic_record(path, record)
    print(json.dumps(record, ensure_ascii=False, allow_nan=False), flush=True)
    return rc


if __name__ == '__main__':
    try:
        raise SystemExit(main(sys.argv[1:]))
    except Exception as failure:
        print(json.dumps({'accepted': False, 'failureCode': 'CONFIGURATION', 'exceptionType': type(failure).__name__, 'automaticRetries': 0}), flush=True)
        raise SystemExit(2)
