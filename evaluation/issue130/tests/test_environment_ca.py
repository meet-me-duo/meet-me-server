"""Independent CA plan/metadata tests. Synthetic files only; no sealed corpus or real keys."""
import contextlib
import hashlib
import importlib.util
import inspect
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

REPO=Path(os.environ['ISSUE130_TEST_SOURCE_ROOT'])
SPEC=importlib.util.spec_from_file_location('issue130_ca_runner',REPO/'evaluation/issue130/run_representative_decisions.py')
RUNNER=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)

class EnvironmentCaPlanContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        target=Path(tempfile.mkdtemp(prefix='issue130-ca-python-'))/'synthetic-fixture.json'
        command=[os.environ['ISSUE130_TEST_JAVA'],f'-Dissue130.sourceRoot={REPO}','-cp',os.environ['ISSUE130_TEST_CLASSPATH'],'Issue130EnvironmentCaContractTest','--export-ca-fixture',str(target)]
        result=subprocess.run(command,capture_output=True,text=True,timeout=30,env=dict(os.environ,OPENAI_API_KEY='',GEMINI_API_KEY=''))
        if result.returncode:raise RuntimeError('SYNTHETIC_CA_FIXTURE_EXPORT_FAILED')
        cls.fixture=json.loads(target.read_text())
        cls.original=Path(cls.fixture['plan']).read_bytes()
    def setUp(self):
        self.plan=Path(self.fixture['plan']);self.plan.write_bytes(self.original)
        self.env={'NODE_EXTRA_CA_CERTS':self.fixture['pem']}
    def change(self,operation):
        value=json.loads(self.plan.read_text());operation(value);self.plan.write_text(json.dumps(value))
    def validate(self,environment=None):
        selected=self.env if environment is None else environment
        if 'environment' in inspect.signature(RUNNER.validate_plan).parameters:
            return RUNNER.validate_plan(self.plan,Path(self.fixture['index']),Path(self.fixture['output']),environment=selected)
        # Compatibility harness exposes the same missing behavior without counting TypeError as RED.
        with mock.patch.dict(os.environ,selected,clear=True):
            return RUNNER.validate_plan(self.plan,Path(self.fixture['index']),Path(self.fixture['output']))
    def reject(self,environment=None):
        try:self.validate(environment)
        except RUNNER.EvaluationFailure as error:
            self.assertEqual('CONFIGURATION',str(error));return
        self.fail('INVALID_CA_PLAN_MUST_REJECT_BEFORE_EXECUTION')
    def use_file(self,raw):
        path=Path(tempfile.mkdtemp(prefix='issue130-ca-bytes-'))/'ca.pem';path.write_bytes(raw)
        self.env['NODE_EXTRA_CA_CERTS']=str(path)
        self.change(lambda p:p['environmentCa'].update(pemFileSha256=hashlib.sha256(raw).hexdigest()))
    def test_CE01_valid_explicit_environment_parameter(self):
        self.assertIn('environment',inspect.signature(RUNNER.validate_plan).parameters,'Additive explicit environment seam is required')
        self.validate()
    def test_CE02_missing_CA_environment_rejects(self):self.reject({})
    def test_CE03_exact_metadata_fields(self):
        for field in ['environmentVariable','pemFileSha256','certificateDerSha256']:
            with self.subTest(field=field):
                self.plan.write_bytes(self.original);self.change(lambda p:p['environmentCa'].pop(field));self.reject()
    def test_CE04_unknown_metadata_field(self):
        self.change(lambda p:p['environmentCa'].update(skipVerification=True));self.reject()
    def test_CE05_null_optin(self):self.change(lambda p:p.update(environmentCa=None));self.reject()
    def test_CE06_fixed_environment_name(self):
        self.change(lambda p:p['environmentCa'].update(environmentVariable='SYNTHETIC_OTHER'));self.reject()
    def test_CE07_metadata_types_and_lowercase_digest(self):
        for field,bad in [('environmentVariable',7),('pemFileSha256',None),('pemFileSha256','ABCDEF'*10+'ABCD'),('certificateDerSha256','not-a-digest')]:
            with self.subTest(field=field,bad=type(bad).__name__):
                self.plan.write_bytes(self.original);self.change(lambda p:p['environmentCa'].update({field:bad}));self.reject()
    def test_CE08_actual_PEM_byte_pin(self):
        self.change(lambda p:p['environmentCa'].update(pemFileSha256='0'*64));self.reject()
    def test_CE09_leaf_symlink(self):
        alias=Path(tempfile.mkdtemp(prefix='issue130-ca-link-'))/'ca.pem';alias.symlink_to(self.fixture['pem']);self.env['NODE_EXTRA_CA_CERTS']=str(alias);self.reject()
    def test_CE10_parent_symlink(self):
        alias=Path(tempfile.mkdtemp(prefix='issue130-ca-alias-'))/'alias';pem=Path(self.fixture['pem']);alias.symlink_to(pem.parent,target_is_directory=True);self.env['NODE_EXTRA_CA_CERTS']=str(alias/pem.name);self.reject()
    def test_CE11_directory(self):
        self.env['NODE_EXTRA_CA_CERTS']=tempfile.mkdtemp(prefix='issue130-ca-dir-');self.reject()
    def test_CE12_empty_or_relative_path(self):
        for value in ['', ' ', 'relative.pem']:
            with self.subTest(kind=repr(value)):self.reject({'NODE_EXTRA_CA_CERTS':value})
    def test_CE13_size_and_ASCII_PEM_structure(self):
        original=Path(self.fixture['pem']).read_bytes()
        for raw in [b'',b'x'*16385,b'not a PEM',original+original,original+b'nonwhitespace',b'\xff'+original]:
            with self.subTest(length=len(raw)):
                self.plan.write_bytes(self.original);self.use_file(raw);self.reject()
    def test_CE14_absence_does_not_infer_environment_CA(self):
        self.change(lambda p:p.pop('environmentCa'))
        self.validate({'NODE_EXTRA_CA_CERTS':'not-a-real-file'})
    def test_CE15_CA_forbidden_without_environment_proxy_mode(self):
        def change(p):
            p.pop('transportMode');p['costProfile'].update(priorHeldUsd='1.33185536',maximumCumulativeReservedUsd='1.56254208')
        self.change(change);self.reject()
    def test_CE16_outside_whitespace_preserved_byte_pin(self):
        self.use_file(b' \t\r\n'+Path(self.fixture['pem']).read_bytes()+b' \r\n');self.validate()
    def test_CE17_no_openssl_or_network_dependency(self):
        original=subprocess.run
        def only_existing_git(command,*a,**kw):
            if isinstance(command,list) and command[:1]==['git']:return original(command,*a,**kw)
            raise AssertionError('PYTHON_CA_VALIDATION_MUST_NOT_SPAWN_CRYPTO_OR_NETWORK')
        with mock.patch.object(RUNNER.subprocess,'run',side_effect=only_existing_git):self.validate()
    def test_CE18_no_consumption_on_invalid_CA(self):
        self.change(lambda p:p['environmentCa'].update(pemFileSha256='0'*64));self.reject()
        self.assertFalse((Path(self.fixture['output'])/'.suite-consumed.json').exists())

if __name__=='__main__':unittest.main(verbosity=2)
