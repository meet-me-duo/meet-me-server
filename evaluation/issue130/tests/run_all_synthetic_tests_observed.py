"""Sanitized observation wrapper; executes the unchanged original launcher once."""
import argparse
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile

REPO = Path(__file__).resolve().parents[3]
CAP = 1048576
# Fixed public registrations in the original Java/Python contract sources.
JAVA_IDS = frozenset(('N01_local_TLS_proxy_actual_production_classifier_control', 'P01_exact_production_serializer_and_whitespace', 'P02_unicode_500_codepoints', 'P03_reject_501', 'P04_reject_NUL', 'P05_reject_extra_gold_field', 'P06_reject_missing_locale', 'P07_reject_wrong_type', 'P08_reject_invalid_context', 'P09_existing_output_unchanged', 'P10_symlink_output_unchanged', 'P11_symlink_ancestor_rejected', 'P12_duplicate_input_key_rejected', 'P13_prepare_index_preserves_16_order', 'P14_prepare_index_duplicate_ID_rejected', 'P17_reverse_search_dates_rejected', 'P18_whole_wire_16KiB_includes_context', 'P15_actual_CLI_prepare_stdout_private', 'P16_actual_CLI_failure_no_raw_path_stack', 'R01_CLEAR16_real_canonical_transport', 'R02_review_routes_B_probe_no_real_B', 'R03_low_confidence_raw_CLEAR_is_normalized_hold', 'R04_model_uncertain_routes_B_probe', 'R05_refusal_safe_STOP', 'R06_missing_usage_preserved_unknown_STOP', 'R07_partial_usage_keeps_known_input', 'R08_over_proxy_stops_next', 'R09_output2049_not_silently_zeroed', 'R10_malformed_JSON_STOP', 'R11_wrong_model_STOP', 'R12_response_cap_16384', 'L01_missing_root_seal_outbound0', 'L02_wrong_root_seal_outbound0', 'L03_missing_key_outbound0', 'L04_mutated_frozen_wire_outbound0', 'L05_rehashed_mutated_prompt_outbound0', 'L06_duplicate_case_identity_outbound0', 'L07_case_count_not16_outbound0', 'L08_source_manifest_tamper_outbound0', 'L09_runtime_hash_mismatch_outbound0', 'L10_missing_cost_outbound0', 'L11_Decimal_ceiling_outbound0', 'L12_unknown_cost_profile_outbound0', 'L13_synthetic_hardcap_claim_rejected', 'L14_repeat_same_suite_zero_extra_calls', 'L15_concurrent_same_suite_atmost16_serial', 'L16_expired_suite_before_emission_outbound0', 'L17_slow_body_uses_remaining_case_budget', 'L18_backwards_wallclock_no_extra_budget', 'L19_response_persistence_failure_stops_before_next', 'L21_alternate_output_directory_rejected', 'L22_output_symlink_ancestor_rejected', 'L23_missing_remaining_budget_rejected', 'L24_zero_remaining_budget_rejected', 'L25_extended_remaining_budget_rejected', 'L26_late_CLEAR_after_case_deadline_rejected', 'L27_fresh_preopen_fence_after_validation', 'L28_index_gold_field_rejected', 'L29_required_production_source_pin_missing', 'L30_wire_byte_count_mismatch_outbound0', 'L20_valid_route_disagreement_continues_all16_no_gold', 'R_HTTP_401_one_attempt_no_followup', 'R_HTTP_402_one_attempt_no_followup', 'R_HTTP_407_one_attempt_no_followup', 'R_HTTP_429_one_attempt_no_followup', 'R_HTTP_503_one_attempt_no_followup', 'R_HTTP_302_one_attempt_no_followup', 'R_HTTP_307_one_attempt_no_followup', 'L_COST_null_inputUsdPerMillion_outbound0', 'L_COST_null_inputTokenProxy_outbound0', 'L_COST_null_margin_outbound0', 'L_COST_null_perCallReservedUsd_outbound0', 'L_COST_null_priorHeldUsd_outbound0', 'L_COST_null_maximumNewReservedUsd_outbound0', 'L_COST_null_maximumCumulativeReservedUsd_outbound0', 'L_COST_null_approvedAggregateUsd_outbound0', 'L_COST_null_review_outbound0'))
PYTHON_IDS = frozenset(('test_CLI01_validate_valid_synthetic_plan', 'test_CLI02_default_dry_run_no_java_key_or_consumption', 'test_CLI03_explicit_execution_without_root_seal_rejected', 'test_CLI04_missing_cost_rejected', 'test_CLI05_null_cost_rejected', 'test_CLI06_inexact_budget_amount_rejected', 'test_CLI07_over_approved_ceiling_rejected', 'test_CLI08_missing_cost_evidence_rejected', 'test_CLI09_wrong_cost_evidence_hash_rejected', 'test_CLI10_changed_source_manifest_rejected', 'test_CLI11_changed_runtime_helper_hash_rejected', 'test_CLI12_changed_index_hash_rejected', 'test_CLI13_wrong_output_binding_rejected', 'test_CLI14_output_symlink_rejected', 'test_CLI15_canonical_endpoint_override_rejected', 'test_CLI16_fixed_threshold_override_rejected', 'test_CLI17_larger_case_limit_rejected', 'test_CLI18_retry_redirect_flags_rejected', 'test_CLI20_freeze_validation_counts_in_whole300', 'test_CLI21_compilation_counts_before_java_spawn', 'test_CLI22_late_child_success_never_accepted_after300', 'test_CLI23_child_timeout_no_second_java_or_retry', 'test_CLI24_startup_crosses_deadline_kills_created_child', 'test_CLI19_malformed_plan_is_bounded_safe_failure'))

ERROR_TYPES = frozenset({
    'NullPointerException', 'IllegalArgumentException', 'IllegalStateException',
    'IOException', 'SSLException', 'SSLHandshakeException', 'SocketException',
    'SocketTimeoutException', 'TimeoutException', 'SecurityException',
    'RuntimeException', 'EvaluationException', 'InterruptedException',
})


def unknown():
    return dict(stage='UNKNOWN', productionCompileExitCode=None,
                evaluationCompileExitCode=None, javaExitCode=None,
                pythonExitCode=None, javaSummary=None,
                javaFailures=[], pythonFailures=[])


def canonical(path):
    path = Path(path).absolute()
    if path != Path(os.path.normpath(path)):
        raise ValueError()
    for part in (path, *path.parents):
        if part.is_symlink():
            raise ValueError()
    return path


def bounded_read(path):
    path = canonical(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise ValueError()
        raw = stream.read(CAP + 1)
    if len(raw) > CAP:
        raise ValueError()
    return raw.decode('utf-8')


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError()
            result[key] = value
        return result
    def invalid(_):
        raise ValueError()
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=invalid)


def exit_code(value):
    return type(value) is int and -255 <= value <= 255


def java_failures(path):
    try:
        lines = bounded_read(path).splitlines()[:256]
    except FileNotFoundError:
        return []
    failures = []
    for line in lines:
        try:
            row = strict_json(line)
        except ValueError:
            continue
        if not isinstance(row, dict) or row.get('test') not in JAVA_IDS:
            continue
        status = row.get('status')
        if status == 'ASSERTION_FAILURE':
            kind = 'AssertionError'
        elif status == 'SETUP_OR_UNEXPECTED_ERROR':
            kind = row.get('type')
            if not isinstance(kind, str) or kind not in ERROR_TYPES:
                kind = 'UNEXPECTED_ERROR'
        else:
            continue
        safe = dict(test=row['test'], status=status, type=kind)
        if safe not in failures:
            failures.append(safe)
    return failures


def python_failures(path):
    try:
        lines = bounded_read(path).splitlines()[:256]
    except FileNotFoundError:
        return []
    failures = []
    for line in lines:
        match = re.fullmatch(r'(FAIL|ERROR): (test_CLI[A-Za-z0-9_]+) \(__main__\.RunnerContract\.\2\)', line)
        if not match or match.group(2) not in PYTHON_IDS:
            continue
        status, name = match.groups()
        safe = dict(test=name, status=status,
                    type='AssertionError' if status == 'FAIL' else 'UNEXPECTED_ERROR')
        if safe not in failures:
            failures.append(safe)
    return failures


def setup_failure(base_directory):
    result = unknown()
    raw = bounded_read(base_directory / 'base.log')
    rows = []
    for line in raw.splitlines()[:256]:
        try:
            row = strict_json(line)
        except ValueError:
            continue
        if isinstance(row, dict) and row.get('status') == 'SETUP_FAILURE':
            rows.append(row)
    if len(rows) != 1:
        return result
    row = rows[0]
    code = row.get('exitCode')
    if not exit_code(code) or code == 0:
        return result
    if row.get('stage') == 'production compile':
        result.update(stage='PRODUCTION_COMPILE', productionCompileExitCode=code)
    elif row.get('stage') == 'evaluation compile':
        result.update(stage='EVALUATION_COMPILE', evaluationCompileExitCode=code)
    return result


def safe_base_failure(base_directory):
    """Publish fixed stage/IDs only; never copy an exception or diagnostic body."""
    try:
        root = canonical(base_directory)
        receipt = root / 'base/receipt.json'
        try:
            row = strict_json(bounded_read(receipt))
        except FileNotFoundError:
            return setup_failure(root)
        if not isinstance(row, dict):
            return unknown()
        names = ('productionCompileExitCode', 'evaluationCompileExitCode', 'javaExitCode', 'pythonExitCode')
        if not all(exit_code(row.get(name)) for name in names):
            return unknown()
        prod, compile_code, java, python = (row[name] for name in names)
        if prod != 0 or compile_code != 0 or type(row.get('sourceBeforeAfterEqual')) is not bool:
            return unknown()
        if row.get('status') != ('FAILED' if java or python else 'PASS'):
            return unknown()
        summary = row.get('javaSummary')
        fields = ('tests', 'passed', 'assertionFailures', 'errors')
        if not isinstance(summary, dict) or not all(type(summary.get(n)) is int and 0 <= summary[n] <= 77 for n in fields):
            return unknown()
        safe_summary = {n: summary[n] for n in fields}
        if safe_summary['tests'] != 77 or sum(safe_summary[n] for n in fields[1:]) != 77:
            return unknown()
        result = unknown()
        result.update(dict(zip(names, (prod, compile_code, java, python))))
        result['javaSummary'] = safe_summary
        if not row['sourceBeforeAfterEqual']:
            result['stage'] = 'SOURCE_DRIFT'
        elif java and python:
            result['stage'] = 'JAVA_AND_PYTHON_TESTS'
        elif java:
            result['stage'] = 'JAVA_TESTS'
        elif python:
            result['stage'] = 'PYTHON_TESTS'
        else:
            return unknown()
        result['javaFailures'] = java_failures(root / 'base/java-tests.jsonl')
        result['pythonFailures'] = python_failures(root / 'base/python-tests.log')
        return result
    except (OSError, ValueError, TypeError, UnicodeError):
        return unknown()


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        raise ValueError()


def main(argv=None):
    parser = SafeParser()
    parser.add_argument('--private-only', action='store_true')
    parser.add_argument('--output')
    try:
        args = parser.parse_args(argv)
        output = canonical(args.output) if args.output else Path(tempfile.mkdtemp(prefix='issue130-observed-'))
        output.mkdir(mode=0o700, parents=True, exist_ok=True)
        output.chmod(0o700)
        child_output = output / 'original'
        command = [sys.executable, 'evaluation/issue130/tests/run_all_synthetic_tests.py', '--output', str(child_output)]
        if args.private_only:
            command.append('--private-only')
        environment = dict(os.environ, OPENAI_API_KEY='', GEMINI_API_KEY='')
        fd = os.open(output / 'child.raw.log', os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'w') as stream:
            child = subprocess.run(command, cwd=REPO, env=environment,
                                   stdout=stream, stderr=subprocess.STDOUT, timeout=465)
        code = child.returncode
        if not exit_code(code):
            raise ValueError()
        result = dict(status='PASS' if code == 0 else 'BASE_FAILED', exitCode=code)
        if code:
            result['baseFailure'] = safe_base_failure(child_output)
        print(json.dumps(result, separators=(',', ':')))
        return code
    except Exception:
        print(json.dumps(dict(status='BASE_FAILED', exitCode=2, baseFailure=unknown()), separators=(',', ':')))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
