"""Independent public preflight/wrapper decimal wire contract; synthetic fixtures only, child/model zero."""
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

REPO=Path(os.environ.get('ISSUE130_TEST_SOURCE_ROOT',Path(__file__).resolve().parents[3]))
PROFILES={
    'A24':('evaluation/issue130/run_representative_decisions.py','Issue130CandidateA24ContractTest','--export-a24-fixture','0.10'),
    'B2':('evaluation/issue130/run_standalone_generation.py','Issue130StandaloneRuntimeTest','--export-fixture','0.75'),
}


def imported(name,path):
    spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module


def non_ascii(value):return value.translate(str.maketrans('0123456789','０１２３４５６７８９'))


class CostDecimalWireContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixtures={};cls.runners={}
        for profile,(path,klass,export,_) in PROFILES.items():
            cp=os.environ.get('ISSUE130_DECIMAL_'+profile+'_CLASSPATH',os.environ.get('ISSUE130_TEST_CLASSPATH'))
            if not cp:raise RuntimeError('SYNTHETIC_CACHED_CLASSPATH_REQUIRED')
            target=Path(tempfile.mkdtemp(prefix='issue130-decimal-public-'+profile+'-'))/'fixture.json'
            env=dict(os.environ,OPENAI_API_KEY='',GEMINI_API_KEY='');env.pop('NODE_EXTRA_CA_CERTS',None)
            child=subprocess.run([os.environ['ISSUE130_TEST_JAVA'],f'-Dissue130.sourceRoot={REPO}','-cp',cp,klass,export,str(target)],capture_output=True,text=True,timeout=35,env=env)
            if child.returncode:raise RuntimeError('SYNTHETIC_'+profile+'_FIXTURE_SETUP_FAILURE')
            fixture=json.loads(target.read_text());fixture['originalPlan']=Path(fixture['plan']).read_bytes()
            plan=json.loads(fixture['originalPlan']);fixture['priorPath']=Path(plan['priorReservation']['path']);fixture['originalPrior']=fixture['priorPath'].read_bytes()
            fixture['classpathFile']=fixture.get('classpathFile') or next(p for p in plan['runtimeFiles'] if p.endswith('/runtime.classpath'))
            cls.fixtures[profile]=fixture;cls.runners[profile]=imported('issue130_decimal_'+profile,REPO/path)

    def fixture(self,profile):
        f=self.fixtures[profile];Path(f['plan']).write_bytes(f['originalPlan']);f['priorPath'].write_bytes(f['originalPrior']);return f

    def change(self,f,location,value):
        path=Path(f['plan']);plan=json.loads(path.read_text())
        if location=='cost':plan['costProfile']['inputUsdPerMillion']=value
        else:
            prior=json.loads(f['priorPath'].read_text());prior['newReservationUsd']=value;f['priorPath'].write_text(json.dumps(prior))
            plan['priorReservation']['sha256']=hashlib.sha256(f['priorPath'].read_bytes()).hexdigest()
        path.write_text(json.dumps(plan))

    def public_preflight(self,profile,location,value,accepted):
        f=self.fixture(profile);self.change(f,location,value);runner=self.runners[profile]
        try:result=runner.validate_plan(Path(f['plan']),Path(f['index']),Path(f['output']),environment={'NODE_EXTRA_CA_CERTS':f['pem']})
        except runner.EvaluationFailure as failure:
            self.assertFalse(accepted,'CANONICAL_LEADING_ZERO_AMOUNT_MUST_VALIDATE')
            self.assertEqual('CONFIGURATION',str(failure))
        else:
            self.assertTrue(accepted,'NON_JAVA_DECIMAL_WIRE_SYNTAX_MUST_REJECT_IN_PUBLIC_PREFLIGHT')
            self.assertEqual(24 if profile=='A24' else 2,result['caseCount'])
        self.assertFalse((Path(f['output'])/'.suite-consumed.json').exists(),'no marker in preflight')

    def public_main(self,profile,location,value,accepted):
        f=self.fixture(profile);self.change(f,location,value);runner=self.runners[profile];children=[]
        real_run=subprocess.run
        def only_git(command,*args,**kwargs):
            if command[:1]==['git']:return real_run(command,*args,**kwargs)
            children.append('compile')
            return subprocess.CompletedProcess(command,1,stdout='',stderr='')
        def no_jvm(*args,**kwargs):
            children.append('JVM');raise AssertionError('NO_CHILD_BEFORE_DECIMAL_VALIDATION')
        # Module-local namespace: actual Git subprocess keeps its real Popen. No global interception.
        transport=types.SimpleNamespace(run=only_git,Popen=no_jvm,TimeoutExpired=subprocess.TimeoutExpired)
        digest=hashlib.sha256(Path(f['plan']).read_bytes()).hexdigest()
        env={'NODE_EXTRA_CA_CERTS':f['pem'],'JAVA_HOME':str(Path(os.environ['ISSUE130_TEST_JAVA']).parent.parent),'PATH':os.defpath}
        if profile=='A24':env.update(OPENAI_API_KEY='synthetic-no-dispatch-key',ISSUE130_FROZEN_PLAN_SHA256=digest,ISSUE130_EXECUTE_FROZEN=digest)
        else:env.update(GEMINI_API_KEY='synthetic-no-dispatch-key',ISSUE130_B_FROZEN_PLAN_SHA256=digest,ISSUE130_B_EXECUTE_FROZEN=digest)
        args=['--plan',f['plan'],'--index',f['index'],'--output',f['output'],'--classpath-file',f['classpathFile']]
        if not accepted:args.append('--execute-frozen')
        stdout=io.StringIO()
        with mock.patch.dict(os.environ,env,clear=True),mock.patch.object(runner,'subprocess',transport),contextlib.redirect_stdout(stdout):code=runner.main(args)
        report=json.loads(stdout.getvalue())
        self.assertEqual([],children,'LEXICAL_REJECTION_MUST_PRECEDE_COMPILE_OR_JVM')
        self.assertEqual(0 if accepted else 2,code)
        if accepted:self.assertIs(False,report['executionPermitted'])
        else:self.assertEqual('CONFIGURATION',report['failureCode'])
        self.assertNotIn('synthetic-no-dispatch-key',stdout.getvalue())
        self.assertFalse((Path(f['output'])/'.suite-consumed.json').exists(),'no marker before dispatch')


def make_test(profile,api,location,transform,accepted):
    def test(self):
        if location=='cost':canonical=PROFILES[profile][3]
        else:canonical=json.loads(self.fixtures[profile]['originalPrior'])['newReservationUsd']
        getattr(self,'public_'+api)(profile,location,transform(canonical),accepted)
    return test


vectors={
    'leading_dot':lambda value:value[1:],
    'exponent':lambda value:value+'e0',
    'plus_sign':lambda value:'+'+value,
    'leading_whitespace':lambda value:' '+value,
    'trailing_whitespace':lambda value:value+' ',
    'fraction_over8':lambda value:value.partition('.')[0]+'.'+value.partition('.')[2].ljust(9,'0'),
    'non_ascii_digits':non_ascii,
    'negative_sign':lambda value:'-'+value,
    'nonfinite':lambda value:'NaN',
}
for profile in PROFILES:
    for api in ('preflight','main'):
        setattr(CostDecimalWireContract,'test_'+profile+'_'+api+'_canonical_leading_zero',make_test(profile,api,'cost',lambda value:value,True))
        for name,transform in vectors.items():setattr(CostDecimalWireContract,'test_'+profile+'_'+api+'_reject_'+name,make_test(profile,api,'cost',transform,False))
        for name in ('leading_dot','exponent','plus_sign','fraction_over8'):
            setattr(CostDecimalWireContract,'test_'+profile+'_'+api+'_prior_reject_'+name,make_test(profile,api,'prior',vectors[name],False))


if __name__=='__main__':unittest.main(verbosity=2)
