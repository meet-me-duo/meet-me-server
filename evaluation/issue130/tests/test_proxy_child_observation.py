"""Whitelist-only diagnostic metadata. Synthetic canaries never become CI output."""
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('proxy_child_observation_target', Path(__file__).with_name('run_environment_proxy_tests.py'))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
PC01 = 'PC01_actual_Python_CLI_ENV_proxy_TLS16_and_replay0'


def valid():
    return dict(phase='PYTHON_CLI', stage='EXIT_NONZERO', exitCode=2,
                stdoutBytes=52, stderrBytes=0, inheritedHomePresent=True,
                inheritedHomeCanonical=False, selectedHomeDirectory=True,
                selectedHomeCanonical=True, selectedJavaRegular=True, selectedJavacRegular=True)


class ChildObservationContract(unittest.TestCase):
    def observe(self, value):
        self.assertTrue(callable(getattr(MODULE, 'safe_child_observation', None)))
        return MODULE.safe_child_observation(value)

    def test_CO01_exact_fixed_metadata_round_trips(self):
        value = valid()
        self.assertEqual(value, self.observe(value))

    def test_CO02_unknown_keys_never_copy_private_canaries(self):
        value = valid()
        value.update(path='SYNTHETIC_PRIVATE_PATH', environment={'API_KEY': 'SYNTHETIC_PRIVATE_KEY'},
                     body='SYNTHETIC_PRIVATE_BODY', trace='SYNTHETIC_PRIVATE_TRACE')
        result = self.observe(value)
        self.assertEqual(valid(), result)
        self.assertNotIn('SYNTHETIC_PRIVATE', repr(result))

    def test_CO03_fixed_phases_stages_and_numeric_boundaries(self):
        for phase in ('PYTHON_CLI', 'JAVA_CLI', 'REPLAY'):
            for stage in ('CHILD_START_FAILED', 'CHILD_TIMEOUT', 'STDOUT_REJECTED', 'STDERR_REJECTED',
                          'EXIT_NONZERO', 'MISSING_COMPLETED', 'FINISHED'):
                value = dict(valid(), phase=phase, stage=stage, exitCode=None, stdoutBytes=None, stderrBytes=None)
                self.assertEqual(value, self.observe(value))
        for number in (-255, 255):
            value = dict(valid(), exitCode=number, stdoutBytes=1048576, stderrBytes=0)
            self.assertEqual(value, self.observe(value))

    def test_CO04_wrong_enum_missing_field_and_non_object_omit(self):
        for value in (None, [], 'SYNTHETIC_PRIVATE', dict(valid(), phase='SYNTHETIC_PRIVATE'),
                      dict(valid(), stage='SYNTHETIC_PRIVATE')):
            self.assertIsNone(self.observe(value))
        for key in ('phase', 'stage'):
            for bad in (None, [], {}, 7, True):
                self.assertIsNone(self.observe(dict(valid(), **{key: bad})))
        for key in valid():
            value = valid()
            del value[key]
            self.assertIsNone(self.observe(value))

    def test_CO05_numeric_types_and_bounds_are_strict(self):
        for key in ('exitCode', 'stdoutBytes', 'stderrBytes'):
            for bad in (True, False, 1.0, '1', {}, [], 'SYNTHETIC_PRIVATE'):
                self.assertIsNone(self.observe(dict(valid(), **{key: bad})))
        for bad in (-256, 256):
            self.assertIsNone(self.observe(dict(valid(), exitCode=bad)))
        for key in ('stdoutBytes', 'stderrBytes'):
            for bad in (-1, 1048577):
                self.assertIsNone(self.observe(dict(valid(), **{key: bad})))

    def test_CO06_jdk_metadata_is_exact_bool_only(self):
        for key in ('inheritedHomePresent', 'inheritedHomeCanonical', 'selectedHomeDirectory',
                    'selectedHomeCanonical', 'selectedJavaRegular', 'selectedJavacRegular'):
            for bad in (0, 1, None, 'true', {}, 'SYNTHETIC_PRIVATE'):
                self.assertIsNone(self.observe(dict(valid(), **{key: bad})))

    def test_CO07_valid_optional_observation_propagates_without_reason(self):
        row = dict(test=PC01, status='ASSERTION_FAILURE', childObservation=valid(), reason='SYNTHETIC_PRIVATE')
        self.assertEqual([dict(test=PC01, status='ASSERTION_FAILURE', type='AssertionError', childObservation=valid())],
                         MODULE.safe_java_failures([row]))

    def test_CO08_invalid_observation_does_not_hide_failure_or_leak(self):
        row = dict(test=PC01, status='ASSERTION_FAILURE', childObservation={'path': 'SYNTHETIC_PRIVATE'})
        self.assertEqual([dict(test=PC01, status='ASSERTION_FAILURE', type='AssertionError')], MODULE.safe_java_failures([row]))

    def test_CO09_unlisted_parent_and_pass_never_forward_observation(self):
        for name, status in (('SYNTHETIC_PRIVATE', 'ASSERTION_FAILURE'), (PC01, 'PASS')):
            self.assertEqual([], MODULE.safe_java_failures([dict(test=name, status=status, childObservation=valid())]))


if __name__ == '__main__':
    unittest.main(verbosity=2)
