#!/usr/bin/env python3
"""Independent standalone B public synthetic contracts; no live CA/key/custody access."""
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
TEST_ROOT=REPO/'evaluation/issue130/standalone_tests'


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def inventory():
    files=list((REPO/'src/main').rglob('*.kt'))+list((REPO/'evaluation/issue130').rglob('*.java'))+list((REPO/'evaluation/issue130').rglob('*.py'))
    return {str(p.relative_to(REPO)):sha(p) for p in sorted(files) if '__pycache__' not in p.parts}


def logged(command,env,path,timeout):
    with path.open('w') as stream:
        return subprocess.run(command,cwd=REPO,env=env,stdout=stream,stderr=subprocess.STDOUT,timeout=timeout).returncode


def main():
    os.umask(0o022)
    parser=argparse.ArgumentParser();parser.add_argument('--output');parser.add_argument('--compile-only',action='store_true');parser.add_argument('--java-only',action='store_true');parser.add_argument('--test',action='append',default=[]);parser.add_argument('--class-name',default='Issue130StandaloneRuntimeTest');args=parser.parse_args()
    out=Path(args.output) if args.output else Path(tempfile.mkdtemp(prefix='issue130-B-independent-'))
    out.mkdir(parents=True,exist_ok=True);out.chmod(0o700)
    env=dict(os.environ,OPENAI_API_KEY='',GEMINI_API_KEY='',JAVA_HOME=os.environ.get('JAVA_HOME') or '/tmp/meetme-jdk17',GRADLE_USER_HOME=os.environ.get('GRADLE_USER_HOME') or '/tmp/meetme-gradle-home')
    for field in ('NODE_EXTRA_CA_CERTS','https_proxy','HTTPS_PROXY','http_proxy','HTTP_PROXY','ALL_PROXY','all_proxy'):
        env.pop(field,None)
    java=str(Path(env['JAVA_HOME'])/'bin/java');javac=str(Path(env['JAVA_HOME'])/'bin/javac')
    cpfile=out/'production-runtime.classpath';classes=out/'classes';classes.mkdir(exist_ok=True)
    started=time.monotonic()
    with open('/tmp/meetme-gradle.lock','a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        before=inventory();(out/'source-before.json').write_text(json.dumps(before,indent=2)+'\n')
        command=['./gradlew','--no-daemon','--console=plain','--max-workers=2','-I','docs/verification/issue-107-native-browser.init.gradle',f'-Dmeetme.issue107.native.classpath-file={cpfile}','issue107NativeRuntimeClasspath']
        build=logged(command,env,out/'compile-production.log',360)
        if build:
            print(json.dumps({'status':'SETUP_FAILURE','stage':'production compile','exitCode':build}));return 2
        cp=cpfile.read_text().strip();sources=[REPO/'evaluation/issue130/Issue130StandaloneGenerationCli.java',*sorted(TEST_ROOT.glob('*.java'))]
        compile_exit=logged([javac,'--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),*map(str,sources)],env,out/'compile-evaluation.log',90)
        if compile_exit:
            print(json.dumps({'status':'SETUP_FAILURE','stage':'evaluation compile','exitCode':compile_exit}));return 2
        runtime=os.pathsep.join([str(classes),cp]);(out/'evaluation-runtime.classpath').write_text(runtime)
        childenv=dict(env,ISSUE130_TEST_SOURCE_ROOT=str(REPO),ISSUE130_TEST_JAVA=java,ISSUE130_TEST_CLASSPATH=runtime)
        java_codes=[];rows=[];py=0;pycounts={}
        if not args.compile_only:
            selected=[args.class_name] if args.test else ['Issue130StandaloneClientTest','Issue130StandaloneRuntimeTest','Issue130StandalonePrivateCreationTest']
            for name in selected:
                path=out/(name+'.jsonl')
                code=logged([java,f'-Dissue130.sourceRoot={REPO}','-cp',runtime,name,*args.test],childenv,path,360)
                java_codes.append(code)
                for line in path.read_text().splitlines():
                    try:rows.append(json.loads(line))
                    except ValueError:pass
            if not args.java_only and not args.test:
                py=logged([sys.executable,str(TEST_ROOT/'test_standalone_generation.py')],childenv,out/'python.log',180)
                text=(out/'python.log').read_text();match=re.search(r'Ran (\d+) tests?',text)
                failed=set(re.findall(r'^FAIL: (test_\w+)',text,re.M));errors=set(re.findall(r'^ERROR: (test_\w+)',text,re.M))
                pycounts={'tests':int(match.group(1)) if match else 0,'failedMethods':len(failed),'errors':len(errors),'assertionFailures':len(re.findall(r'^FAIL: ',text,re.M))}
                pycounts['passedControls']=pycounts['tests']-len(failed)-len(errors)
        after=inventory();(out/'source-after.json').write_text(json.dumps(after,indent=2)+'\n')
        summaries=[r for r in rows if 'tests' in r]
        result={'status':'COMPILE_ONLY' if args.compile_only else 'PASS' if not(any(java_codes) or py) else 'FAILED','javaExitCodes':java_codes,'pythonExitCode':py,'javaSummaries':summaries,'javaFailures':[r for r in rows if r.get('status') in ('ASSERTION_FAILURE','SETUP_OR_UNEXPECTED_ERROR')],'python':pycounts,'sourceBeforeAfterEqual':before==after,'durationSeconds':round(time.monotonic()-started,3),'actualProviderCalls':0,'sealedDataRead':False}
        (out/'receipt.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
        return 0 if before==after and not(any(java_codes) or py) else 1


if __name__=='__main__':raise SystemExit(main())
