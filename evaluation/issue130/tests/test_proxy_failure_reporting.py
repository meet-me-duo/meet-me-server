"""Whitelist-only CI diagnostics: never return raw errors or environment/body values."""
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('issue130_proxy_launcher', Path(__file__).with_name('run_environment_proxy_tests.py'))
LAUNCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LAUNCHER)


class SafeFailureReportingContract(unittest.TestCase):
    def report(self, rows):
        reporter = getattr(LAUNCHER, 'safe_java_failures', None)
        self.assertTrue(callable(reporter), 'CI must expose whitelist-only safe failed-test diagnostics')
        return reporter(rows)

    def test_RF01_known_assertion_reports_only_fixed_safe_fields(self):
        rows = [{'test': 'PC01_actual_Python_CLI_ENV_proxy_TLS16_and_replay0',
                 'status': 'ASSERTION_FAILURE', 'reason': 'SYNTHETIC_PRIVATE_BODY',
                 'environment': {'API_KEY': 'SYNTHETIC_PRIVATE_KEY'}, 'body': 'SYNTHETIC_PRIVATE_BODY'}]
        self.assertEqual([{'test': rows[0]['test'], 'status': 'ASSERTION_FAILURE', 'type': 'AssertionError'}], self.report(rows))

    def test_RF02_unlisted_id_pass_and_unknown_status_are_omitted(self):
        rows = [{'test': 'PC99_SYNTHETIC_PRIVATE_VALUE', 'status': 'ASSERTION_FAILURE'},
                {'test': 'PX01_lowercase_selected_unresolved', 'status': 'PASS'},
                {'test': 'PX01_lowercase_selected_unresolved', 'status': 'SYNTHETIC_PRIVATE_STATUS'},
                'SYNTHETIC_PRIVATE_VALUE', {'tests': 49, 'errors': 0}]
        self.assertEqual([], self.report(rows))

    def test_RF03_known_exception_class_is_reported_without_raw_reason(self):
        rows = [{'test': 'PN02_parameterized_TLS_trusted_wrong_SAN_control',
                 'status': 'SETUP_OR_UNEXPECTED_ERROR', 'type': 'SSLHandshakeException',
                 'reason': 'http://SYNTHETIC_PRIVATE_PROXY:1234', 'trace': 'SYNTHETIC_PRIVATE_KEY'}]
        self.assertEqual([{'test': rows[0]['test'], 'status': rows[0]['status'], 'type': 'SSLHandshakeException'}], self.report(rows))

    def test_RF04_unlisted_exception_type_is_fixed_generic_label(self):
        rows = [{'test': 'PX01_lowercase_selected_unresolved', 'status': 'SETUP_OR_UNEXPECTED_ERROR',
                 'type': 'SYNTHETIC_PRIVATE_TYPE', 'reason': 'SYNTHETIC_PRIVATE_BODY'}]
        self.assertEqual([{'test': rows[0]['test'], 'status': rows[0]['status'], 'type': 'UNEXPECTED_ERROR'}], self.report(rows))


if __name__ == '__main__':
    unittest.main(verbosity=2)
