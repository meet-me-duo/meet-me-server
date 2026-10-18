#!/usr/bin/env python3
"""Synthetic CA contracts; no real environment certificate, private corpus or credentials."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

REPO=Path(__file__).resolve().parents[3]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def inventory():
    return {str(p.relative_to(REPO)):sha(p) for p in sorted((REPO/'evaluation/issue130').rglob('*')) if p.is_file() and '__pycache__' not in p.parts}
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output');args=parser.parse_args()
    out=Path(args.output) if args.output else Path(tempfile.mkdtemp(prefix='issue130-ca-synthetic-'))
    out.mkdir(parents=True,exist_ok=True);out.chmod(0o700)
    env=dict(os.environ,OPENAI_API_KEY='',GEMINI_API_KEY='',JAVA_HOME=os.environ.get('JAVA_HOME') or '/tmp/meetme-jdk17',GRADLE_USER_HOME=os.environ.get('GRADLE_USER_HOME') or '/tmp/meetme-gradle-home')
    # Never inherit a real environment CA path into synthetic child processes.
    env.pop('NODE_EXTRA_CA_CERTS',None)
    start=time.monotonic()
    with (out/'compile.log').open('w') as stream:
        build=subprocess.run([sys.executable,'evaluation/issue130/tests/run_synthetic_tests.py','--compile-only','--output',str(out/'compile')],cwd=REPO,env=env,stdout=stream,stderr=subprocess.STDOUT,timeout=360)
    if build.returncode:
        print(json.dumps({'status':'SETUP_FAILURE','stage':'compile','exitCode':build.returncode}));return 2
    with open('/tmp/meetme-gradle.lock','a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        before=inventory();(out/'source-before.json').write_text(json.dumps(before,indent=2)+'\n')
        cp=(out/'compile/evaluation-runtime.classpath').read_text().strip();java=str(Path(env['JAVA_HOME'])/'bin/java')
        childenv=dict(env,ISSUE130_TEST_SOURCE_ROOT=str(REPO),ISSUE130_TEST_JAVA=java,ISSUE130_TEST_CLASSPATH=cp)
        with (out/'java.jsonl').open('w') as stream:
            jav=subprocess.run([java,f'-Dissue130.sourceRoot={REPO}','-cp',cp,'Issue130EnvironmentCaContractTest'],cwd=REPO,env=childenv,stdout=stream,stderr=subprocess.STDOUT,timeout=150)
        with (out/'python.log').open('w') as stream:
            py=subprocess.run([sys.executable,'evaluation/issue130/tests/test_environment_ca.py'],cwd=REPO,env=childenv,stdout=stream,stderr=subprocess.STDOUT,timeout=90)
        rows=[]
        for line in (out/'java.jsonl').read_text().splitlines():
            try:rows.append(json.loads(line))
            except ValueError:pass
        text=(out/'python.log').read_text();match=re.search(r'Ran (\d+) tests?',text);fail=re.search(r'FAILED \(([^)]+)\)',text)
        pycounts={'tests':int(match.group(1)) if match else 0,'assertionFailures':0,'errors':0}
        if fail:
            for k,v in re.findall(r'(failures|errors)=(\d+)',fail.group(1)):pycounts['assertionFailures' if k=='failures' else k]=int(v)
        pycounts['failedMethods']=len(set(re.findall(r'^FAIL: (test_\w+)',text,re.M)))
        pycounts['passedControls']=pycounts['tests']-pycounts['failedMethods']-pycounts['errors']
        after=inventory();(out/'source-after.json').write_text(json.dumps(after,indent=2)+'\n')
        result={'status':'PASS' if not(jav.returncode or py.returncode) else 'FAILED','javaExitCode':jav.returncode,'pythonExitCode':py.returncode,'java':rows[-1] if rows else None,'javaFailures':[r for r in rows if r.get('status') in ['ASSERTION_FAILURE','SETUP_OR_UNEXPECTED_ERROR']],'python':pycounts,'sourceBeforeAfterEqual':before==after,'durationSeconds':round(time.monotonic()-start,3),'actualProviderCalls':0,'sealedDataRead':False,'evidence':str(out)}
        (out/'receipt.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
        return 0 if not(jav.returncode or py.returncode) and before==after else 1
if __name__=='__main__':raise SystemExit(main())
