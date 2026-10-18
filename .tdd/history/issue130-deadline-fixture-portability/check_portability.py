"""Synthetic deadline fixture replay; no real provider, private corpus or credentials."""
import argparse,contextlib,hashlib,importlib.util,io,json,os,tempfile,unittest
from pathlib import Path
from unittest import mock

def main():
 parser=argparse.ArgumentParser();parser.add_argument('--classpath-file',required=True);parser.add_argument('--java',required=True);parser.add_argument('--source-root',required=True);parser.add_argument('--output',required=True);parser.add_argument('--mutant',choices=['fixture_unpin','extended_deadline','skip_startup_kill']);parser.add_argument('--full',action='store_true');a=parser.parse_args()
 root=Path(a.source_root);out=Path(a.output);out.mkdir(parents=True,exist_ok=False);out.chmod(0o700)
 test=root/'evaluation/issue130/tests/test_representative_decisions.py';product=root/'evaluation/issue130/run_representative_decisions.py'
 sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
 before={str(p):sha(p) for p in [test,product]}
 os.environ.update(ISSUE130_TEST_SOURCE_ROOT=str(root),ISSUE130_TEST_JAVA=a.java,ISSUE130_TEST_CLASSPATH=Path(a.classpath_file).read_text().strip(),OPENAI_API_KEY='',GEMINI_API_KEY='')
 spec=importlib.util.spec_from_file_location('portable_deadline_tests',test);T=importlib.util.module_from_spec(spec);spec.loader.exec_module(T)
 source=test.read_text()
 if a.mutant=='fixture_unpin':
  source=source.replace("'JAVA_HOME': str(deadline_home),",'')
  assert source!=test.read_text();exec(compile(source,str(test),'exec'),T.__dict__)
 if a.mutant in ['extended_deadline','skip_startup_kill']:
  source=product.read_text()
  if a.mutant=='extended_deadline':source=source.replace("deadline = started + plan['suiteSeconds']", "deadline = started + plan['suiteSeconds'] + 300")
  else:source=source.replace('os.killpg(process.pid, signal.SIGKILL)','None  # fault: suppress created-child cleanup')
  assert source!=product.read_text();exec(compile(source,str(product),'exec'),T.RUNNER.__dict__)
 names=[n for n in dir(T.RunnerContract) if n.startswith('test_CLI') and (a.full or n.startswith(tuple('test_CLI'+str(i)+'_' for i in [20,21,22,23,24])))]
 with tempfile.TemporaryDirectory(prefix='issue130-deadline-host-') as td:
  disposable=Path(td);valid=disposable/'jdk';(valid/'bin').mkdir(parents=True)
  for name in ['java','javac']:
   p=valid/'bin'/name;p.write_text('#!/bin/sh\nexit 99\n');p.chmod(0o700)
  link=disposable/'linked';link.symlink_to(valid,target_is_directory=True)
  contexts=[('valid',valid)] if a.full or a.mutant in ['extended_deadline','skip_startup_kill'] else [('valid',valid),('invalid',disposable/'missing'),('symlink',link)]
  results=[]
  for label,home in contexts:
   stream=io.StringIO();suite=unittest.TestSuite(T.RunnerContract(n) for n in names)
   with mock.patch.dict(os.environ,{'JAVA_HOME':str(home)}):result=unittest.TextTestRunner(stream=stream,verbosity=2).run(suite)
   log=out/(label+'.log');log.write_text(stream.getvalue());log.chmod(0o600)
   results.append({'context':label,'tests':result.testsRun,'failures':[x._testMethodName for x,_ in result.failures],'errors':[x._testMethodName for x,_ in result.errors],'logSha256':sha(log)})
 after={str(p):sha(p) for p in [test,product]}
 receipt={'status':'PASS' if all(not x['failures'] and not x['errors'] for x in results) else 'ASSERTION_DETECTION' if not any(x['errors'] for x in results) else 'SETUP_ERROR','mutant':a.mutant,'full':a.full,'results':results,'sourceBeforeAfterEqual':before==after,'files':after,'actualProviderCalls':0,'privateInputsOrGoldRead':False,'remoteHostFilesystemCauseEstablished':False}
 p=out/'receipt.json';p.write_text(json.dumps(receipt,indent=2)+'\n');p.chmod(0o600);print(json.dumps({'status':receipt['status'],'results':results,'receiptSha256':sha(p)}))
 return 0 if before==after and not any(x['errors'] for x in results) else 2
if __name__=='__main__':raise SystemExit(main())
