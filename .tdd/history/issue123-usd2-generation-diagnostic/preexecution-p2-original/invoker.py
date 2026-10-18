import argparse,datetime,hashlib,json,os,pathlib,signal,subprocess,time
ROOT=pathlib.Path('/workspace/issue123-durable-budget/server');BASE=ROOT/'.codex/tdd-evidence/issue123-usd2-generation-diagnostic'
FROZEN='1e21af79257ab3f944886f43cd5d65c927804b47b8e0df9b822d3ef1915827c2'
def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
assert sha(BASE/'execution-plan.json')==FROZEN
plan=json.loads((BASE/'execution-plan.json').read_text())
parser=argparse.ArgumentParser();parser.add_argument('--execute-frozen',action='store_true');args=parser.parse_args()
if not args.execute_frozen:
 print(json.dumps({'mode':'DRY_RUN_NO_NETWORK','planSha256':FROZEN,'maximumGenerateRequests':2,'cumulativeMaximumReservedUsd':plan['cumulativeMaximumReservedUsd']}));raise SystemExit(0)
gatepath=ROOT/'.tdd/verification/issue123-durable-budget-final-gate.json';manifest=ROOT/'.tdd/verification/issue123-durable-budget-source-manifest.json';gate=json.loads(gatepath.read_text())
assert gate['guard']['exitCode']==0 and gate['junit']['allFreshAfterGuardStart']
assert sha(manifest)==gate['source']['manifestSha256']
assert all(sha(ROOT/p)==h for p,h in json.loads(manifest.read_text())['files'].items())
assert sha(plan['childPath'])==plan['childSha256'] and sha(plan['harnessPath'])==plan['harnessSha256']
assert sha(ROOT/plan['oraclePath'])==plan['oracleSha256'] and sha(ROOT/plan['modePath'])==plan['modeSha256']
assert not list(BASE.glob('attempt*-consumed.json'))
cp=pathlib.Path('/tmp/issue123-decision-smoke-classpath.txt').read_text()
comp=subprocess.run(['/tmp/meetme-jdk17/bin/javac','-cp',cp,'-d','/tmp',plan['harnessPath']],capture_output=True,text=True);assert comp.returncode==0
batch={'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'state':'STARTED_UNKNOWN_HELD','planSha256':FROZEN,'guardSha256':sha(gatepath),'approvedAggregateUsd':'2.00','priorReservedUsd':'0.70918144','cumulativeMaximumReservedUsd':'1.01330944'}
fd=os.open(BASE/'diagnostic-batch-invocation.json',os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
with os.fdopen(fd,'w') as f:json.dump(batch,f);f.write('\n')
env=os.environ.copy();env['ISSUE123_DIAGNOSTIC_PLAN_SHA']=FROZEN
results=[];print(json.dumps({'state':'EXPLICIT_FROZEN_DIAGNOSTIC_STARTED','perAttemptObservationCapSeconds':60,'productDeadlineChanged':False}),flush=True)
for number in (1,2):
 start=time.monotonic();proc=subprocess.Popen(['python3',plan['childPath'],str(number)],env=env,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,start_new_session=True)
 observation={'attempt':number,'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'retryAllowed':False}
 try:out,_=proc.communicate(timeout=60)
 except subprocess.TimeoutExpired:
  os.killpg(proc.pid,signal.SIGKILL);out,_=proc.communicate();observation.update(clientFailure='TIMEOUT',childKilledAtObservationCap=True,retryAllowed=number==1)
 observation.update(exitCode=proc.returncode,clientElapsedMs=round((time.monotonic()-start)*1000,1),clientFinishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),clientFinishedEpoch=time.time())
 if len(out)>16384:raise RuntimeError('METADATA_LIMIT')
 consumed=BASE/('attempt'+str(number)+'-consumed.json')
 if consumed.exists():observation['latestRecordedChildState']=json.loads(consumed.read_text())
 if 'clientFailure' not in observation:
  observation['retryAllowed']=number==1 and proc.returncode==3
  observation['clientFailure']=None if proc.returncode==0 else 'CALLBACK_REPORTED_FAILURE'
 wait=observation.get('latestRecordedChildState',{}).get('retryAfterSeconds')
 if wait is not None and (not isinstance(wait,(int,float)) or wait>10):observation['retryAllowed']=False
 if proc.returncode==0:
  response=BASE/('attempt'+str(number)+'-response.raw.json')
  check=subprocess.run(['/tmp/meetme-jdk17/bin/java','-cp',cp,'Issue123TwoStageSmokeHarness','parseB',str(ROOT/'.codex/tdd-evidence/issue123-minimal-smoke/LIVE-KO-03-request.json'),str(response)],env={**env,'OPENAI_API_KEY':'','GEMINI_API_KEY':''},capture_output=True,text=True,timeout=10)
  observation.update(productionParserExitCode=check.returncode,productionParserStdout=check.stdout,productionParserStderr=check.stderr,state='HTTP200_STOP_PENDING_INDEPENDENT_SEMANTIC_REVIEW',retryAllowed=False)
 else:observation['state']='TRANSIENT_RETRY_ELIGIBLE' if observation['retryAllowed'] else 'STOP_FAILURE_UNKNOWN_HELD'
 target=BASE/('attempt'+str(number)+'-client-observation.json');fd=os.open(target,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
 with os.fdopen(fd,'w') as f:json.dump(observation,f,ensure_ascii=False,indent=2);f.write('\n')
 results.append(observation);print(json.dumps(observation,ensure_ascii=False),flush=True)
 if not observation['retryAllowed']:break
 print(json.dumps({'state':'PREDECLARED_TRANSIENT_RETRY_GAP','seconds':10,'nextAttempt':2}),flush=True);time.sleep(10)
batch.update(state='OBSERVED_PENDING_INDEPENDENT_REVIEW_INVOICE_UNVERIFIED',finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),actualGenerateAttempts=len(list(BASE.glob('attempt*-consumed.json'))),actualNewDecisionsRequests=0,actualNewCountRequests=0,results=results)
(BASE/'diagnostic-batch-invocation.json').write_text(json.dumps(batch,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'state':batch['state'],'actualGenerateAttempts':batch['actualGenerateAttempts'],'actualNewDecisionsRequests':0,'actualNewCountRequests':0}),flush=True)
