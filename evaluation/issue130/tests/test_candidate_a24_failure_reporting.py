"""Pure CI failure projection: fixed old15 identifiers, never private log contents."""
import contextlib
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
from unittest import mock
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('a24_safe_report_target', Path(__file__).with_name('run_candidate_a24_tests.py'))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
METHOD = 'test_A24PY12_350second_validation_has70left_no_reset'
OTHER = 'test_A24PY15_child_deadline_kills_process_group'


def header(kind='FAIL', method=METHOD, repeated=None):
    return f'{kind}: {method} (__main__.CandidateA24Contract.{repeated or method})\n'


def row(kind='FAIL', method=METHOD):
    return dict(test=method, status='ASSERTION_FAILURE' if kind == 'FAIL' else 'SETUP_OR_UNEXPECTED_ERROR',
                type='AssertionError' if kind == 'FAIL' else 'UNEXPECTED_ERROR')


class CandidateFailureReportingContract(unittest.TestCase):
    def report(self, text):
        self.assertTrue(callable(getattr(MODULE, 'safe_python_failures', None)))
        return MODULE.safe_python_failures(text)

    def test_APR01_fixed_failure_and_error_headers_only(self):
        self.assertEqual([row(), row('ERROR', OTHER)], self.report(header() + header('ERROR', OTHER)))

    def test_APR02_raw_paths_env_body_trace_never_emitted(self):
        text = header() + 'Traceback SYNTHETIC_PRIVATE_PATH\nAPI_KEY=SYNTHETIC_PRIVATE_KEY\nSYNTHETIC_PRIVATE_BODY\n'
        result = self.report(text)
        self.assertEqual([row()], result)
        self.assertNotIn('SYNTHETIC_PRIVATE', repr(result))

    def test_APR03_unknown_ids_malformed_headers_and_prefix_ignored(self):
        for text in (header(method='SYNTHETIC_PRIVATE'), header(repeated=OTHER),
                     'SYNTHETIC_PRIVATE ' + header(), 'FAIL: ' + METHOD + '\n',
                     header().replace('CandidateA24Contract', 'SYNTHETIC_PRIVATE'),
                     'test_A24PY12_350second_validation_has70left_no_reset ... FAIL\n'):
            self.assertEqual([], self.report(text))

    def test_APR04_duplicate_ids_do_not_invent_multiple_failures(self):
        self.assertEqual([row()], self.report(header() + header()))

    def test_APR05_unknown_setupclass_and_counts_not_invented(self):
        text = 'ERROR: setUpClass (__main__.CandidateA24Contract)\nRan 999999999999999999999999 tests\nFAILED (failures=-2, errors=true)\nSYNTHETIC_PRIVATE\n'
        self.assertEqual([], self.report(text))

    def test_APR06_nontext_and_bounded_utf8_input(self):
        for value in (None, {}, [], True, 123):
            self.assertEqual([], self.report(value))
        self.assertEqual([], self.report('x' * 1048577 + '\n' + header()))
        self.assertEqual([], self.report('가' * 349526 + '\n' + header()))

    def test_APR07_valid_old15_ids_all_supported_without_body_type_guess(self):
        methods = ('test_A24PY01_valid24_amounts_and_historical2',
                   'test_A24PY02_default_dryrun_reports24_and_never_child',
                   'test_A24PY03_exact_profile_unknown_type_or_value',
                   'test_A24PY04_old16_cannot_borrow_new_profile',
                   'test_A24PY05_new24_requires_discriminator',
                   'test_A24PY06_old_hold_and_cap_rejected',
                   'test_A24PY07_CA_required', 'test_A24PY08_ENV_proxy_required',
                   'test_A24PY09_prior_evidence_hash_pinned', 'test_A24PY10_wrong24_timeout_fixed',
                   'test_A24PY11_untrusted_threshold_and_retry_unchanged', METHOD,
                   'test_A24PY13_421_validation_stops_before_compile',
                   'test_A24PY14_compile_counts_and_late_success_rejected', OTHER)
        self.assertEqual([row('ERROR', name) for name in methods],
                         self.report(''.join(header('ERROR', name) + 'ValueError: SYNTHETIC_PRIVATE\n' for name in methods)))

    def test_APR08_actual_runner_preserves_unrecognized_setup_exit(self):
        text = 'ERROR: setUpClass (__main__.CandidateA24Contract)\nRuntimeError: SYNTHETIC_PRIVATE\n'
        calls = []
        def fake_run(command, *args, **kwargs):
            calls.append(command)
            code = 1 if len(calls) == 3 else 0
            if len(calls) == 2:
                kwargs['stdout'].write('{"tests":26,"passed":26,"assertionFailures":0,"errors":0}\n')
            elif len(calls) == 3:
                kwargs['stdout'].write(text)
            return subprocess.CompletedProcess(command, code)
        with tempfile.TemporaryDirectory(prefix='issue130-a24-observation-') as directory:
            cp = Path(directory) / 'classpath'
            cp.write_text('synthetic-classpath-not-executed')
            output = Path(directory) / 'result'
            argv = ['runner', '--production-classpath-file', str(cp), '--output', str(output)]
            stdout = io.StringIO()
            with mock.patch.object(sys, 'argv', argv), mock.patch.object(MODULE.subprocess, 'run', side_effect=fake_run), \
                    mock.patch.object(MODULE, 'inventory', return_value={}), \
                    mock.patch.object(MODULE.fcntl, 'flock'), contextlib.redirect_stdout(stdout):
                code = MODULE.main()
            result = json.loads(stdout.getvalue())
            self.assertEqual(1, code)
            self.assertEqual(1, result['pythonExitCode'])
            self.assertEqual([], result.get('pythonFailures'))
            self.assertNotIn('SYNTHETIC_PRIVATE', stdout.getvalue())


if __name__ == '__main__':
    unittest.main(verbosity=2)
