"""Independent B-only plan/wrapper oracle; synthetic public fixture, no custody reads or network."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest import mock

REPO = Path(os.environ['ISSUE130_TEST_SOURCE_ROOT'])
SPEC = importlib.util.spec_from_file_location('standalone_b_runner', REPO/'evaluation/issue130/run_standalone_generation.py')
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class StandaloneGenerationPlanTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        target = Path(tempfile.mkdtemp(prefix='issue130-b-python-fixture-'))/'fixture.json'
        command = [os.environ['ISSUE130_TEST_JAVA'], f'-Dissue130.sourceRoot={REPO}', '-cp', os.environ['ISSUE130_TEST_CLASSPATH'], 'Issue130StandaloneRuntimeTest', '--export-fixture', str(target)]
        child = subprocess.run(command, capture_output=True, text=True, timeout=30,
                               env=dict(os.environ, OPENAI_API_KEY='', GEMINI_API_KEY=''))
        if child.returncode:
            raise RuntimeError('SYNTHETIC_B_FIXTURE_EXPORT_SETUP_FAILURE')
        cls.fixture = json.loads(target.read_text())
        cls.original = Path(cls.fixture['plan']).read_bytes()
        cls.index_original = Path(cls.fixture['index']).read_bytes()

    def setUp(self):
        self.plan = Path(self.fixture['plan'])
        self.index = Path(self.fixture['index'])
        self.output = Path(self.fixture['output'])
        self.plan.write_bytes(self.original)
        self.index.write_bytes(self.index_original)
        self.environment = {'NODE_EXTRA_CA_CERTS': self.fixture['pem']}

    def change(self, operation):
        value = json.loads(self.plan.read_text()); operation(value)
        self.plan.write_text(json.dumps(value))

    def validate(self):
        return RUNNER.validate_plan(self.plan, self.index, self.output, environment=self.environment)

    def reject(self):
        try:
            self.validate()
        except RUNNER.EvaluationFailure as failure:
            self.assertRegex(str(failure), r'^[A-Z][A-Z0-9_]{0,63}$')
            return
        self.fail('INVALID_B_PLAN_MUST_REJECT')

    def test_BW01_valid_fixed_B2_profile(self):
        plan = self.validate()
        self.assertEqual('STANDALONE_B_QUALITY_ONLY', plan['evaluationProfile'])
        self.assertEqual(2, plan['caseCount'])

    def test_BW02_exact_fixed_limits(self):
        for key, bad in [('caseCount', 1), ('caseCount', 3), ('suiteSeconds', 121), ('perCallSeconds', 16), ('classifierCalls', 1), ('countCalls', 3), ('generationCalls', 3), ('maximumHttpAttempts', 5), ('concurrency', 2), ('automaticRetries', 1), ('redirects', 1), ('fallbacks', 1), ('requestByteCap', 16385), ('responseByteCap', 16385), ('maximumInputPreflightTokens', 4097), ('maximumOutputTokens', 2049), ('candidateCount', 2)]:
            with self.subTest(field=key, bad=bad):
                self.plan.write_bytes(self.original)
                self.change(lambda p: p.update({key: bad}))
                self.reject()

    def test_BW03_bool_not_integer(self):
        self.change(lambda p: p.update(caseCount=True)); self.reject()

    def test_BW04_fixed_model_origin_and_profile(self):
        for key, value in [('model', 'other'), ('endpoint', 'https://api.openai.com'), ('evaluationProfile', 'CANDIDATE_A24_USD3_V1'), ('thinkingLevel', 'HIGH')]:
            with self.subTest(key=key):
                self.plan.write_bytes(self.original); self.change(lambda p: p.update({key: value})); self.reject()

    def test_BW05_missing_source_runtime_cost_prior(self):
        for field in ['sourceManifest', 'runtimeFiles', 'costProfile', 'priorReservation']:
            with self.subTest(field=field):
                self.plan.write_bytes(self.original); self.change(lambda p: p.pop(field)); self.reject()

    def test_BW06_exact_decimal_holds_and_cap(self):
        for field in ['priorHeldUsd', 'maximumNewReservedUsd', 'maximumCumulativeReservedUsd', 'approvedAggregateUsd', 'perOperationReservedUsd', 'perCaseReservedUsd']:
            with self.subTest(field=field):
                self.plan.write_bytes(self.original); self.change(lambda p: p['costProfile'].update({field:'0'})); self.reject()

    def test_BW07_no_false_invoice_cap(self):
        self.change(lambda p: p['costProfile'].update(officialInvoiceHardCapProven=True)); self.reject()

    def test_BW08_old_prior_reservation_not_accepted(self):
        config = json.loads(self.plan.read_text()); path = Path(config['priorReservation']['path'])
        original = path.read_bytes()
        try:
            value=json.loads(original); value['cumulativeReservationUsd']='1.79322880'; path.write_text(json.dumps(value))
            self.change(lambda p:p['priorReservation'].update(sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
            self.reject()
        finally:
            path.write_bytes(original)

    def test_BW09_corrupt_prepared_index(self):
        self.index.write_text('{}'); self.reject()

    def test_BW10_wire_byte_pin(self):
        index=json.loads(self.index.read_text()); path=Path(index['records'][0]['generation_request_path']); old=path.read_bytes()
        try:
            path.write_bytes(b'{}'); self.reject()
        finally:
            path.write_bytes(old)

    def test_BW11_exact_output_identity(self):
        self.change(lambda p:p.update(outputDirectory=str(self.output.parent/'other'))); self.reject()

    def test_BW12_invalid_environment_CA(self):
        self.change(lambda p:p['environmentCa'].update(pemFileSha256='0'*64)); self.reject()

    def test_BW13_already_consumed(self):
        marker=self.output/'.suite-consumed.json'; marker.write_text('{}')
        try:self.reject()
        finally:marker.unlink()

    def test_BW14_dry_run_without_paths_no_key_or_child(self):
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch.object(RUNNER.subprocess, 'run', side_effect=AssertionError('DRY_RUN_CHILD_FORBIDDEN')), mock.patch.object(RUNNER.subprocess, 'Popen', side_effect=AssertionError('DRY_RUN_CHILD_FORBIDDEN')):
            out=io.StringIO()
            with contextlib.redirect_stdout(out):result=RUNNER.main([])
        self.assertEqual(0,result)
        self.assertEqual(False,json.loads(out.getvalue())['executionPermitted'])

    def args(self):
        value=json.loads(self.plan.read_text()); cp=next(path for path in value['runtimeFiles'] if path.endswith('runtime.classpath'))
        return ['--plan',str(self.plan),'--index',str(self.index),'--output',str(self.output),'--classpath-file',cp,'--execute-frozen']

    def execution_env(self):
        digest=hashlib.sha256(self.plan.read_bytes()).hexdigest()
        return {**self.environment,'JAVA_HOME':str(Path(os.environ['ISSUE130_TEST_JAVA']).resolve(strict=True).parent.parent),'GEMINI_API_KEY':'synthetic-b-wrapper-key-MUST-NOT-LEAK','OPENAI_API_KEY':'synthetic-unused-A-key-MUST-NOT-READ','ISSUE130_B_FROZEN_PLAN_SHA256':digest,'ISSUE130_B_EXECUTE_FROZEN':digest}

    def exercise(self, *, clock=None, compile_failure=False, child_timeout=False, child_exit=0, child_stdout=None, seal=True, key=True):
        env=self.execution_env(); calls=[]; killed=[]; original_run=subprocess.run; original_popen=subprocess.Popen
        if not seal:env.pop('ISSUE130_B_EXECUTE_FROZEN')
        if not key:env.pop('GEMINI_API_KEY')
        if child_stdout is None:child_stdout=json.dumps({'status':'COMPLETED','classifierCalls':0,'countCalls':2,'generationCalls':2})
        class Child:
            pid=987654321
            returncode=child_exit
            def communicate(inner, timeout):
                calls.append(('communicate',timeout))
                if child_timeout:raise subprocess.TimeoutExpired(['synthetic'],timeout)
                return child_stdout,''
            def poll(inner):return None if child_timeout else child_exit
        def run(command,*args,**kwargs):
            if command[:1]==['git']:return original_run(command,*args,**kwargs)
            self.assertTrue(str(command[0]).endswith('/bin/javac'),'only bounded javac child')
            self.assertEqual('',kwargs['env']['GEMINI_API_KEY'],'compile keys blank')
            self.assertEqual('',kwargs['env']['OPENAI_API_KEY'],'compile unused A key blank')
            calls.append(('compile',command,kwargs))
            return subprocess.CompletedProcess(command,1 if compile_failure else 0,'','')
        def popen(command,*args,**kwargs):
            if command[:1]==['git']:return original_popen(command,*args,**kwargs)
            self.assertIn('Issue130StandaloneGenerationCli',command)
            self.assertNotIn('Issue130RepresentativeDecisionsCli',command)
            self.assertTrue(kwargs['start_new_session'],'separate killable process group')
            remaining=int(kwargs['env']['ISSUE130_B_SUITE_REMAINING_MILLIS'])
            self.assertGreater(remaining,0);self.assertLessEqual(remaining,120000)
            calls.append(('spawn',command,kwargs));return Child()
        out=io.StringIO()
        with mock.patch.dict(os.environ,env,clear=True), mock.patch.object(RUNNER.subprocess,'run',side_effect=run), mock.patch.object(RUNNER.subprocess,'Popen',side_effect=popen), mock.patch.object(RUNNER.os,'killpg',side_effect=lambda pid,sig:killed.append((pid,sig))):
            with contextlib.redirect_stdout(out):
                if clock is None:code=RUNNER.main(self.args())
                else:
                    with mock.patch.object(RUNNER.time,'monotonic',side_effect=clock):code=RUNNER.main(self.args())
        return code,out.getvalue(),calls,killed

    def test_BW15_wrapper_targets_B_only_and_preserves_120(self):
        code,out,calls,_=self.exercise()
        self.assertEqual(0,code)
        self.assertEqual(1,sum(c[0]=='spawn' for c in calls))
        self.assertNotIn('MUST-NOT-LEAK',out)

    def test_BW16_missing_execution_seal_no_compile_or_spawn(self):
        code,_,calls,_=self.exercise(seal=False)
        self.assertNotEqual(0,code);self.assertFalse(calls)

    def test_BW17_missing_Gemini_key_no_compile_or_spawn(self):
        code,_,calls,_=self.exercise(key=False)
        self.assertNotEqual(0,code);self.assertFalse(calls)

    def test_BW18_startup_expiry_no_spawn(self):
        count=iter([0.0]+[121.0]*40)
        code,_,calls,_=self.exercise(clock=lambda:next(count))
        self.assertNotEqual(0,code);self.assertFalse(any(c[0]=='spawn' for c in calls))

    def test_BW19_compile_time_not_reset_before_JVM(self):
        count=iter([0.0,0.0]+[121.0]*40)
        code,_,calls,_=self.exercise(clock=lambda:next(count))
        self.assertNotEqual(0,code);self.assertFalse(any(c[0]=='spawn' for c in calls))

    def test_BW20_timeout_kills_process_group_no_retry(self):
        code,out,calls,killed=self.exercise(child_timeout=True)
        self.assertNotEqual(0,code);self.assertEqual(1,sum(c[0]=='spawn' for c in calls));self.assertTrue(killed)
        self.assertIn('TIMEOUT',out)

    def test_BW21_failed_compile_no_JVM_or_retry(self):
        code,_,calls,_=self.exercise(compile_failure=True)
        self.assertNotEqual(0,code);self.assertEqual(1,sum(c[0]=='compile' for c in calls));self.assertFalse(any(c[0]=='spawn' for c in calls))

    def test_BW22_failed_child_not_success(self):
        code,_,calls,_=self.exercise(child_exit=2)
        self.assertNotEqual(0,code);self.assertEqual(1,sum(c[0]=='spawn' for c in calls))

    def test_BW23_raw_or_key_child_output_not_forwarded(self):
        private='synthetic-b-wrapper-key-MUST-NOT-LEAK SYNTHETIC_B_PRIVATE_ synthetic question private'
        code,out,_,_=self.exercise(child_stdout=private)
        self.assertNotEqual(0,code);self.assertNotIn(private,out);self.assertNotIn('MUST-NOT-LEAK',out)

    def test_BW24_stopped_child_not_accepted_completed(self):
        code,_,_,_=self.exercise(child_stdout=json.dumps({'status':'STOPPED','classifierCalls':0,'countCalls':1,'generationCalls':0}))
        self.assertNotEqual(0,code)

    def test_BW25_actual_wrapper_compile_JVM_normal_TLS_and_replay_HTTP0(self):
        target=Path(tempfile.mkdtemp(prefix='issue130-B-whole-cli-'))/'fixture.json'
        server=subprocess.Popen([os.environ['ISSUE130_TEST_JAVA'],f'-Dissue130.sourceRoot={REPO}','-cp',os.environ['ISSUE130_TEST_CLASSPATH'],'Issue130StandaloneRuntimeTest','--serve-cli-fixture',str(target)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=dict(os.environ,OPENAI_API_KEY='',GEMINI_API_KEY=''))
        try:
            deadline=time.monotonic()+25
            while not target.exists() and time.monotonic()<deadline and server.poll() is None:time.sleep(.025)
            self.assertTrue(target.exists(),'SYNTHETIC_WHOLE_CLI_FIXTURE_READY')
            fixture=json.loads(target.read_text()); config=fixture['config']; cp=next(path for path in config['runtimeFiles'] if path.endswith('runtime.classpath'))
            args=['--plan',fixture['plan'],'--index',fixture['index'],'--output',fixture['output'],'--classpath-file',cp,'--execute-frozen']
            environment=dict(os.environ,**fixture['environment'],JAVA_HOME=str(Path(os.environ['ISSUE130_TEST_JAVA']).resolve(strict=True).parent.parent),OPENAI_API_KEY='')
            original_popen=subprocess.Popen
            def guarded(command,*a,**kw):
                if isinstance(command,list) and 'Issue130StandaloneGenerationCli' in command:
                    command=[command[0],'-Djava.security.manager=Issue130StandaloneNetworkGuard',*command[1:]]
                return original_popen(command,*a,**kw)
            first=io.StringIO(); second=io.StringIO()
            with mock.patch.dict(os.environ,environment,clear=True), mock.patch.object(RUNNER.subprocess,'Popen',side_effect=guarded):
                with contextlib.redirect_stdout(first):code=RUNNER.main(args)
                with contextlib.redirect_stdout(second):replay=RUNNER.main(args)
            self.assertEqual(0,code,'actual wrapper→compile→actual runtime→normal Google TLS must complete')
            self.assertNotEqual(0,replay,'same output cannot redispatch')
            self.assertNotIn('SYNTHETIC_B_PRIVATE_',first.getvalue()+second.getvalue())
            self.assertNotIn('MUST-NOT-LEAK',first.getvalue()+second.getvalue())
        finally:
            Path(str(target)+'.stop').touch()
            try:server.communicate(timeout=10)
            except subprocess.TimeoutExpired:server.kill();server.communicate()
        stats=json.loads(Path(str(target)+'.stats').read_text())
        self.assertEqual(4,stats['httpCount'],'exact two count/two generation and replay0')
        self.assertEqual(1,stats['maximumActive'])
        self.assertEqual([],stats['fixtureFailures'])


if __name__=='__main__':unittest.main(verbosity=2)
