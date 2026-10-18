#!/usr/bin/env python3
"""A24 synthetic-only runner. Reuses cached production classpath; never calls paid APIs."""
import argparse,fcntl,hashlib,json,os,subprocess,sys,tempfile,time
from pathlib import Path
REPO=Path(__file__).resolve().parents[3]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def inventory():
    return {str(p.relative_to(REPO)):sha(p) for directory in [REPO/'src/main',REPO/'evaluation/issue130'] for p in sorted(directory.rglob('*')) if p.is_file() and p.suffix in ('.kt','.java','.py')}
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--production-classpath-file',required=True);parser.add_argument('--output',required=True);args=parser.parse_args()
    out=Path(args.output).absolute();out.mkdir(parents=True,exist_ok=True);out.chmod(0o700)
    env=dict(os.environ,OPENAI_API_KEY='',GEMINI_API_KEY='',JAVA_HOME=os.environ.get('JAVA_HOME') or '/tmp/meetme-jdk17');env.pop('NODE_EXTRA_CA_CERTS',None)
    java=str(Path(env['JAVA_HOME'])/'bin/java');javac=str(Path(env['JAVA_HOME'])/'bin/javac')
    with open('/tmp/meetme-gradle.lock','a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX);before=inventory();started=time.time()
        (out/'source-before.json').write_text(json.dumps(before,indent=2)+'\n')
        cp=Path(args.production_classpath_file).read_text().strip();classes=out/'classes';classes.mkdir(exist_ok=True)
        sources=[REPO/'evaluation/issue130/Issue130RepresentativeDecisionsCli.java',*sorted((REPO/'evaluation/issue130/tests').glob('*.java'))]
        with (out/'compile.log').open('w') as log:compiled=subprocess.run([javac,'--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),*map(str,sources)],env=env,stdout=log,stderr=subprocess.STDOUT,timeout=90)
        if compiled.returncode:print(json.dumps({'status':'SETUP_COMPILE_FAILURE','validRed':0}));return 2
        runtime=str(classes)+os.pathsep+cp;(out/'evaluation-runtime.classpath').write_text(runtime)
        selected=dict(env,ISSUE130_TEST_JAVA=java,ISSUE130_TEST_SOURCE_ROOT=str(REPO),ISSUE130_TEST_CLASSPATH=runtime)
        with (out/'java.jsonl').open('w') as log:j=subprocess.run([java,f'-Dissue130.sourceRoot={REPO}','-cp',runtime,'Issue130CandidateA24ContractTest'],cwd=REPO,env=selected,stdout=log,stderr=subprocess.STDOUT,timeout=140)
        with (out/'python.log').open('w') as log:p=subprocess.run([sys.executable,str(REPO/'evaluation/issue130/tests/test_candidate_a24.py')],cwd=REPO,env=selected,stdout=log,stderr=subprocess.STDOUT,timeout=90)
        rows=[]
        for line in (out/'java.jsonl').read_text().splitlines():
            try:rows.append(json.loads(line))
            except ValueError:pass
        after=inventory();(out/'source-after.json').write_text(json.dumps(after,indent=2)+'\n')
        receipt={'javaExitCode':j.returncode,'pythonExitCode':p.returncode,'javaSummary':rows[-1] if rows else None,'sourceBeforeAfterEqual':before==after,'startedUtc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime(started)),'finishedUtc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'actualModelCalls':0,'freshHoldoutRead':False,'productionClasspathFileSha256':sha(Path(args.production_classpath_file))}
        (out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt));return 0 if before==after and not(j.returncode or p.returncode) else 1
if __name__=='__main__':raise SystemExit(main())
