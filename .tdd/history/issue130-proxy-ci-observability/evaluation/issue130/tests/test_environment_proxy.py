"""New-mode cost/evidence contracts; original102 test files are untouched."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

REPO = Path(os.environ['ISSUE130_TEST_SOURCE_ROOT'])
SPEC = importlib.util.spec_from_file_location('issue130_proxy_runner', REPO / 'evaluation/issue130/run_representative_decisions.py')
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class EnvironmentProxyCostContract(unittest.TestCase):
    def setUp(self):
        exported = Path(tempfile.mkdtemp(prefix='issue130-proxy-python-synthetic-')) / 'fixture.json'
        command = [os.environ['ISSUE130_TEST_JAVA'], f'-Dissue130.sourceRoot={REPO}', '-cp',
                   os.environ['ISSUE130_TEST_CLASSPATH'], 'Issue130EnvironmentProxyContractTest',
                   '--export-environment-fixture', str(exported)]
        run = subprocess.run(command, capture_output=True, text=True, timeout=15)
        if run.returncode:
            raise RuntimeError('SYNTHETIC_PROXY_FIXTURE_EXPORT_FAILED')
        self.fixture = json.loads(exported.read_text())
        self.plan = Path(self.fixture['plan'])
        self.index = Path(self.fixture['index'])
        self.output = Path(self.fixture['output'])

    def change(self, operation):
        plan = json.loads(self.plan.read_text())
        operation(plan)
        self.plan.write_text(json.dumps(plan))

    def reject(self):
        try:
            RUNNER.validate_plan(self.plan, self.index, self.output)
        except AssertionError:
            raise
        except Exception as error:
            self.assertNotIn(str(self.plan), str(error))
            return
        self.fail('New environment mode must reject invalid hold/evidence/mode')

    def previous(self, operation):
        import hashlib
        plan = json.loads(self.plan.read_text())
        path = Path(plan['priorReservation']['path'])
        value = json.loads(path.read_text())
        operation(value)
        path.write_text(json.dumps(value))
        plan['priorReservation']['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.plan.write_text(json.dumps(plan))

    def test_PE01_valid_new_profile_and_previous_hold(self):
        try:
            value = RUNNER.validate_plan(self.plan, self.index, self.output)
        except Exception:
            self.fail('New reviewed ENV profile must validate')
        self.assertEqual('1.56254208', value['costProfile']['priorHeldUsd'])
        self.assertEqual('1.79322880', value['costProfile']['maximumCumulativeReservedUsd'])

    def test_PE02_old_prior_rollback_rejected(self):
        self.change(lambda p: p['costProfile'].update(priorHeldUsd='1.33185536', maximumCumulativeReservedUsd='1.56254208'))
        self.reject()

    def test_PE03_missing_previous_evidence_rejected(self):
        self.change(lambda p: p.pop('priorReservation'))
        self.reject()

    def test_PE04_wrong_previous_sha_rejected(self):
        self.change(lambda p: p['priorReservation'].update(sha256='0' * 64))
        self.reject()

    def test_PE05_previous_status_released_rejected(self):
        self.previous(lambda p: p.update(status='RELEASED'))
        self.reject()

    def test_PE06_previous_amount_rollback_rejected(self):
        self.previous(lambda p: p.update(cumulativeReservationUsd='1.33185536'))
        self.reject()

    def test_PE07_previous_refund_nonzero_rejected(self):
        self.previous(lambda p: p.update(refunds=1))
        self.reject()

    def test_PE08_refund_string_zero_not_coerced(self):
        self.previous(lambda p: p.update(refunds='0'))
        self.reject()

    def test_PE09_refund_float_zero_not_coerced(self):
        self.previous(lambda p: p.update(refunds=0.0))
        self.reject()

    def test_PE10_refund_boolean_not_coerced(self):
        self.previous(lambda p: p.update(refunds=False))
        self.reject()

    def test_PE11_unknown_mode_not_legacy_profile(self):
        self.change(lambda p: p.update(transportMode='UNKNOWN_MODE'))
        self.change(lambda p: p['costProfile'].update(priorHeldUsd='1.33185536', maximumCumulativeReservedUsd='1.56254208'))
        self.reject()

    def test_PE12_absent_mode_legacy_reproducible(self):
        self.change(lambda p: p.pop('transportMode'))
        self.change(lambda p: p['costProfile'].update(priorHeldUsd='1.33185536', maximumCumulativeReservedUsd='1.56254208'))
        value = RUNNER.validate_plan(self.plan, self.index, self.output)
        self.assertEqual('1.33185536', value['costProfile']['priorHeldUsd'])

    def test_PE13_new_mode_dry_run_does_not_execute(self):
        config = json.loads(self.plan.read_text())
        cp = next(p for p in config['runtimeFiles'] if p.endswith('/runtime.classpath'))
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = RUNNER.main(['--plan', str(self.plan), '--index', str(self.index), '--output', str(self.output), '--classpath-file', cp])
        self.assertEqual(0, code)
        self.assertIn('DRY_RUN', output.getvalue())
        self.assertFalse((self.output / '.suite-consumed.json').exists())

    def test_PE14_current_hold_sum_mismatch_rejected(self):
        self.change(lambda p: p['costProfile'].update(maximumCumulativeReservedUsd='1.79322879'))
        self.reject()


if __name__ == '__main__':
    unittest.main(verbosity=2)
