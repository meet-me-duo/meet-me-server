"""Synthetic-only exact A24 wrapper/main transport proof. Never reads custody or real keys."""
from pathlib import Path
from collections import Counter
import datetime,fcntl,hashlib,json,os,subprocess,sys,time
ROOT=Path('/workspace/issue123-representative-evaluation/server')
BASE=Path('/tmp/issue130-context-live/offline-corrected-fullpath')
JAVA='/tmp/meetme-jdk17/bin/java'
sha=lambda path:hashlib.sha256(Path(path).read_bytes()).hexdigest()
def private(path,value):
 descriptor=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
 with os.fdopen(descriptor,'w') as stream:
  if isinstance(value,str):stream.write(value)
  else:json.dump(value,stream,indent=2);stream.write('\n')
def inventory():
 files=list((ROOT/'src/main').rglob('*.kt'))+list((ROOT/'evaluation/issue130').rglob('*.java'))+list((ROOT/'evaluation/issue130').rglob('*.py'))
 return {str(p.relative_to(ROOT)):sha(p) for p in sorted(files) if '__pycache__' not in p.parts}
def main():
 with open('/tmp/meetme-gradle.lock','a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX)
  before=inventory();private(BASE/'source-before.json',before)
  cp=(BASE/'cached-a24.classpath').read_text().strip()
  clean={k:os.environ[k] for k in ('PATH','HOME','LANG') if k in os.environ};clean.update(OPENAI_API_KEY='',GEMINI_API_KEY='',JAVA_HOME='/tmp/meetme-jdk17')
  boot='-Xbootclasspath/a:'+str(BASE/'guard-classes')+' -Djava.security.manager=Issue130SyntheticNetworkGuard'
  guarded=dict(clean,JAVA_TOOL_OPTIONS=boot)
  check=subprocess.run([JAVA,'-cp',str(BASE/'guard-classes'),'StartupGuardCheck'],env=guarded,capture_output=True,text=True,timeout=15)
  assert check.returncode==0,'STARTUP_GUARD_CONTROL_FAILED'
  private(BASE/'startup-guard-control.json',json.loads(check.stdout))
  ready=BASE/'synthetic-fixture.json'
  with open(BASE/'server.stdout.log','x') as out,open(BASE/'server.stderr.log','x') as err:
   os.chmod(BASE/'server.stdout.log',0o600);os.chmod(BASE/'server.stderr.log',0o600)
   server=subprocess.Popen([JAVA,'-Dissue130.sourceRoot='+str(ROOT),'-cp',str(BASE)+os.pathsep+cp,'CorrectedA24FixtureServer',str(ready)],env=clean,stdout=out,stderr=err)
   try:
    deadline=time.monotonic()+25
    while not ready.exists() and server.poll() is None and time.monotonic()<deadline:time.sleep(.025)
    assert ready.exists(),'SYNTHETIC_SERVER_NOT_READY'
    fixture=json.loads(ready.read_text());plan=json.loads(Path(fixture['plan']).read_text())
    env=dict(guarded,**fixture['environment']);env['OPENAI_API_KEY']='DUMMY_OFFLINE_CONTEXT_A24';env['GEMINI_API_KEY']=''
    env['ISSUE130_FROZEN_PLAN_SHA256']=sha(fixture['plan']);env['ISSUE130_EXECUTE_FROZEN']=sha(fixture['plan'])
    command=[sys.executable,str(ROOT/'evaluation/issue130/run_representative_decisions.py'),'--plan',fixture['plan'],'--index',fixture['index'],'--output',fixture['output'],'--classpath-file',fixture['classpathFile'],'--execute-frozen']
    started=time.monotonic();first=subprocess.run(command,cwd=ROOT,env=env,capture_output=True,text=True,timeout=60)
    private(BASE/'first.stdout.jsonl',first.stdout);private(BASE/'first.stderr.log',first.stderr)
    assert first.returncode==0,'EXACT_CANONICAL_WRAPPER_FAILED'
    assert json.loads(first.stdout)['status']=='COMPLETED','WRAPPER_NOT_COMPLETED'
    suite=json.loads((Path(fixture['output'])/'suite-result.json').read_text());rows=suite['results']
    assert len(rows)==24 and suite['status']=='COMPLETED' and suite['physicalDecisionsCalls']==24 and suite['physicalGenerationCalls']==0 and suite['countCalls']==0
    assert Counter(r['outcome'] for r in rows)=={'CLEAR_STOP':12,'B_REQUIRED_NOT_RUN':12}
    assert all(r['usageComplete'] is True for r in rows)
    assert 'synthetic-opaque-' not in first.stdout and 'SYNTHETIC_PRIVATE_RAW' not in first.stdout and 'DUMMY_OFFLINE_CONTEXT_A24' not in first.stdout
    replay=subprocess.run(command,cwd=ROOT,env=env,capture_output=True,text=True,timeout=30)
    private(BASE/'replay.stdout.jsonl',replay.stdout);private(BASE/'replay.stderr.log',replay.stderr)
    assert replay.returncode!=0,'REPLAY_MUST_REJECT'
    elapsed=time.monotonic()-started
   finally:
    Path(str(ready)+'.stop').touch()
    try:server.wait(timeout=10)
    except subprocess.TimeoutExpired:server.kill();server.wait()
  stats=json.loads(Path(str(ready)+'.stats').read_text());assert stats['physicalSyntheticPosts']==24 and stats['exactFrozenWires']==24 and stats['maximumConcurrency']==1 and not stats['fixtureFailures']
  after=inventory();private(BASE/'source-after.json',after);assert before==after,'SOURCE_DRIFT'
  result={'status':'EXACT_A24_WRAPPER_COMPILE_MAIN_CLIENT_VALIDATE_CANONICAL_TLS_PARSE_ROUTE_PASS','firstExitCode':first.returncode,'replayExitCode':replay.returncode,'syntheticCases':24,'usageCompleteCases':24,'syntheticPhysicalPosts':24,'exactFrozenWires':24,'maximumConcurrency':1,'normalizedRoutingCounts':{'CLEAR_STOP':12,'B_REQUIRED_NOT_RUN':12},'physicalB':0,'countCalls':0,'replayAdditionalRequests':0,'normalTlsAndHostnameVerification':True,'startupExternalDnsConnectDenied':True,'sourceBeforeAfterEqual':before==after,'sourceFiles':len(before),'sourcePythonSha256':sha(ROOT/'evaluation/issue130/run_representative_decisions.py'),'actualKeysRead':False,'actualProviderOrPaidCalls':0,'realCustodyOrPrivateInputRead':False,'syntheticOnly':True,'semanticAccuracyClaimed':False,'elapsedSeconds':round(elapsed,3),'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
  private(BASE/'receipt.json',result);print(json.dumps(result))
if __name__=='__main__':main()
