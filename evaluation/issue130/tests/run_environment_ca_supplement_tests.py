#!/usr/bin/env python3
"""Supplement tests against current implementation; original CA63 files remain frozen."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

REPO=Path(__file__).resolve().parents[3]
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output');args=parser.parse_args()
    out=Path(args.output) if args.output else Path(tempfile.mkdtemp(prefix='issue130-ca-supplement-'))
    out.mkdir(parents=True,exist_ok=True)
    env=dict(os.environ,OPENAI_API_KEY='',GEMINI_API_KEY='',JAVA_HOME=os.environ.get('JAVA_HOME') or '/tmp/meetme-jdk17',GRADLE_USER_HOME=os.environ.get('GRADLE_USER_HOME') or '/tmp/meetme-gradle-home')
    env.pop('NODE_EXTRA_CA_CERTS',None)
    with (out/'compile.log').open('w') as stream:
        build=subprocess.run([sys.executable,'evaluation/issue130/tests/run_synthetic_tests.py','--compile-only','--output',str(out/'compile')],cwd=REPO,env=env,stdout=stream,stderr=subprocess.STDOUT,timeout=360)
    if build.returncode:
        print(json.dumps({'status':'SETUP_FAILURE','stage':'compile','exitCode':build.returncode}));return 2
    with open('/tmp/meetme-gradle.lock','a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        before={str(p.relative_to(REPO)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((REPO/'evaluation/issue130').rglob('*')) if p.is_file() and '__pycache__' not in p.parts}
        cp=(out/'compile/evaluation-runtime.classpath').read_text().strip()
        with (out/'java.jsonl').open('w') as stream:
            run=subprocess.run([str(Path(env['JAVA_HOME'])/'bin/java'),f'-Dissue130.sourceRoot={REPO}','-cp',cp,'Issue130EnvironmentCaRunPlanSupplementTest'],cwd=REPO,env=env,stdout=stream,stderr=subprocess.STDOUT,timeout=90)
        rows=[]
        for line in (out/'java.jsonl').read_text().splitlines():
            try:rows.append(json.loads(line))
            except ValueError:pass
        after={p:hashlib.sha256((REPO/p).read_bytes()).hexdigest() for p in before}
        result={'status':'PASS' if run.returncode==0 else 'FAILED','exitCode':run.returncode,'counts':rows[-1] if rows else None,'sourceBeforeAfterEqual':before==after,'actualProviderCalls':0,'sealedDataRead':False}
        for name,value in [('source-before.json',before),('source-after.json',after),('receipt.json',result)]:
            (out/name).write_text(json.dumps(value,indent=2)+'\n')
        print(json.dumps(result));return 0 if run.returncode==0 and before==after else 1
if __name__=='__main__':raise SystemExit(main())
