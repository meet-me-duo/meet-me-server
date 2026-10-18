"""Independent fixed A24 plan/wrapper oracle. Synthetic inputs only; no gold or paid network."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest import mock

REPO=Path(os.environ['ISSUE130_TEST_SOURCE_ROOT'])
SPEC=importlib.util.spec_from_file_location('candidate_a24_runner',REPO/'evaluation/issue130/run_representative_decisions.py')
RUNNER=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)

class CandidateA24Contract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        target=Path(tempfile.mkdtemp(prefix='issue130-a24-python-'))/'synthetic.json'
        child=subprocess.run([os.environ['ISSUE130_TEST_JAVA'],f'-Dissue130.sourceRoot={REPO}','-cp',os.environ['ISSUE130_TEST_CLASSPATH'],'Issue130CandidateA24ContractTest','--export-a24-fixture',str(target)],capture_output=True,text=True,timeout=40,env=dict(os.environ,OPENAI_API_KEY='',GEMINI_API_KEY=''))
        if child.returncode:raise RuntimeError('SYNTHETIC_A24_FIXTURE_SETUP_FAILED')
        cls.fixture=json.loads(target.read_text());cls.original=Path(cls.fixture['plan']).read_bytes()
    def setUp(self):
        self.plan=Path(self.fixture['plan']);self.plan.write_bytes(self.original)
        self.index=Path(self.fixture['index']);self.output=Path(self.fixture['output'])
        self.env={'NODE_EXTRA_CA_CERTS':self.fixture['pem']}
    def change(self,fn):
        value=json.loads(self.plan.read_text());fn(value);self.plan.write_text(json.dumps(value))
    def validate(self):return RUNNER.validate_plan(self.plan,self.index,self.output,environment=self.env)
    def accept(self):
        try:return self.validate()
        except RUNNER.EvaluationFailure:self.fail('APPROVED_FIXED_A24_MUST_VALIDATE')
    def reject(self):
        with self.assertRaises(RUNNER.EvaluationFailure) as e:self.validate()
        self.assertEqual('CONFIGURATION',str(e.exception))
        self.assertFalse((self.output/'.suite-consumed.json').exists())
    def main(self,execute=False):
        args=['--plan',str(self.plan),'--index',str(self.index),'--output',str(self.output),'--classpath-file',self.fixture['classpathFile']]
        if execute:args+=['--execute-frozen']
        stdout=io.StringIO()
        with contextlib.redirect_stdout(stdout):code=RUNNER.main(args)
        return code,json.loads(stdout.getvalue())
    def test_A24PY01_valid24_amounts_and_historical2(self):
        p=self.accept();self.assertEqual(24,p['caseCount']);self.assertEqual('3.00',p['costProfile']['approvedAggregateUsd'])
        previous=json.loads(Path(p['priorReservation']['path']).read_text());self.assertEqual('2.00',previous['approvedAggregateUsd'])
    def test_A24PY02_default_dryrun_reports24_and_never_child(self):
        original=subprocess.run
        def only_git(command,*args,**kw):
            if command[0]=='git':return original(command,*args,**kw)
            raise AssertionError('DRYRUN_MUST_NOT_COMPILE_OR_EXECUTE')
        with mock.patch.dict(os.environ,self.env),mock.patch.object(RUNNER.subprocess,'run',side_effect=only_git):code,result=self.main()
        self.assertEqual(0,code);self.assertEqual(24,result['caseCount']);self.assertFalse(result['executionPermitted'])
    def test_A24PY03_exact_profile_unknown_type_or_value(self):
        for value in [None,24,'','CANDIDATE_A24_USD3_V2']:
            with self.subTest(kind=type(value).__name__):
                self.plan.write_bytes(self.original);self.change(lambda p:p.update(evaluationProfile=value));self.reject()
    def test_A24PY04_old16_cannot_borrow_new_profile(self):
        self.change(lambda p:p.update(caseCount=16,maximumHttpAttempts=16,suiteSeconds=300));self.reject()
    def test_A24PY05_new24_requires_discriminator(self):self.change(lambda p:p.pop('evaluationProfile'));self.reject()
    def test_A24PY06_old_hold_and_cap_rejected(self):
        for field,value in [('priorHeldUsd','1.56254208'),('maximumNewReservedUsd','0.23068672'),('maximumCumulativeReservedUsd','1.79322880'),('approvedAggregateUsd','2.00')]:
            with self.subTest(field=field):
                self.plan.write_bytes(self.original);self.change(lambda p:p['costProfile'].update({field:value}));self.reject()
    def test_A24PY07_CA_required(self):self.change(lambda p:p.pop('environmentCa'));self.reject()
    def test_A24PY08_ENV_proxy_required(self):self.change(lambda p:p.pop('transportMode'));self.reject()
    def test_A24PY09_prior_evidence_hash_pinned(self):
        self.change(lambda p:p['priorReservation'].update(sha256='0'*64));self.reject()
    def test_A24PY10_wrong24_timeout_fixed(self):self.change(lambda p:p.update(suiteSeconds=421));self.reject()
    def test_A24PY11_untrusted_threshold_and_retry_unchanged(self):
        for field,value in [('minimumConfidence',0.7),('automaticRetries',1),('generationCalls',1),('countCalls',1)]:
            with self.subTest(field=field):
                self.plan.write_bytes(self.original);self.change(lambda p:p.update({field:value}));self.reject()
    def wrapper(self,validation=0,compile_time=0,java_time=0,startup=0,timeout=False):
        state={'now':100.0,'commands':[],'timeouts':[],'kills':0,'remainingAtSpawn':None}
        original_validate=RUNNER.validate_plan;original_run=subprocess.run
        def validate(*args,**kw):
            result=original_validate(*args,**kw);state['now']+=validation;return result
        def run(command,*args,**kw):
            if Path(str(command[0])).name=='git':return original_run(command,*args,**kw)
            if Path(str(command[0])).name!='javac':raise AssertionError('UNEXPECTED_CHILD')
            state['commands'].append('javac');state['timeouts'].append(kw['timeout']);state['now']+=compile_time
            return subprocess.CompletedProcess(command,0,stdout='',stderr='')
        class Process:
            def __init__(self,command,*args,**kw):
                state['commands'].append('java');state['remainingAtSpawn']=int(kw['env']['ISSUE130_SUITE_REMAINING_MILLIS']);state['now']+=startup
                self.pid=987654321;self.returncode=None;self.args=command;self.communicated=False
            def communicate(self,*args,**kw):
                if not self.communicated:
                    self.communicated=True;state['timeouts'].append(kw.get('timeout'));state['now']+=java_time
                    if timeout:raise subprocess.TimeoutExpired(self.args,kw.get('timeout'))
                self.returncode=0
                return json.dumps({'status':'COMPLETED','physicalDecisionsCalls':24}),''
            def poll(self):return self.returncode
        fake=types.SimpleNamespace(run=run,Popen=Process,PIPE=subprocess.PIPE,TimeoutExpired=subprocess.TimeoutExpired)
        digest=hashlib.sha256(self.plan.read_bytes()).hexdigest()
        env=dict(self.env,JAVA_HOME=str(Path(os.environ['ISSUE130_TEST_JAVA']).resolve(strict=True).parent.parent),OPENAI_API_KEY='synthetic-issue130-key-MUST-NOT-LEAK',GEMINI_API_KEY='',ISSUE130_FROZEN_PLAN_SHA256=digest,ISSUE130_EXECUTE_FROZEN=digest)
        with mock.patch.object(RUNNER,'validate_plan',validate),mock.patch.object(RUNNER,'subprocess',fake),mock.patch('time.monotonic',lambda:state['now']),mock.patch.dict(os.environ,env),mock.patch('os.killpg',lambda *a:state.update(kills=state['kills']+1)):
            code,result=self.main(True)
        return code,result,state
    def test_A24PY12_350second_validation_has70left_no_reset(self):
        code,result,s=self.wrapper(validation=350)
        self.assertEqual(0,code);self.assertEqual(['javac','java'],s['commands']);self.assertEqual(70000,s['remainingAtSpawn']);self.assertLessEqual(max(s['timeouts']),70)
    def test_A24PY13_421_validation_stops_before_compile(self):
        code,result,s=self.wrapper(validation=421)
        self.assertEqual(2,code);self.assertEqual('TIMEOUT',result['failureCode']);self.assertEqual([],s['commands'])
    def test_A24PY14_compile_counts_and_late_success_rejected(self):
        code,result,s=self.wrapper(validation=410,compile_time=5,java_time=6)
        self.assertEqual(2,code);self.assertEqual('TIMEOUT',result['failureCode']);self.assertEqual(['javac','java'],s['commands']);self.assertEqual(5000,s['remainingAtSpawn'])
    def test_A24PY15_child_deadline_kills_process_group(self):
        code,result,s=self.wrapper(validation=400,compile_time=5,java_time=16,timeout=True)
        self.assertEqual(2,code);self.assertEqual('TIMEOUT',result['failureCode']);self.assertEqual(1,s['kills']);self.assertLessEqual(s['timeouts'][-1],15)

if __name__=='__main__':unittest.main(verbosity=2)
