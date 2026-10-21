import argparse,datetime,hashlib,json,os,pathlib,signal,subprocess,time
ROOT=pathlib.Path('/workspace/issue123-durable-budget/server');BASE=ROOT/'.codex/tdd-evidence/issue123-live-two-stage'
FROZEN='368bafb75f2c36f0980501ea5b7b8a9669f5b8eb5e6705c49e4b6659b9aeb746'
def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
assert sha(BASE/'execution-plan.json')==FROZEN
plan=json.loads((BASE/'execution-plan.json').read_text())
args=argparse.ArgumentParser();args.add_argument('--execute-frozen',action='store_true');opts=args.parse_args()
if not opts.execute_frozen:
 print(json.dumps({'mode':'DRY_RUN_NO_NETWORK','planSha256':FROZEN,'cumulativeReservedUsd':plan['cumulativeReservedUsd']}));raise SystemExit(0)
gate=json.loads((ROOT/'.tdd/verification/issue123-durable-budget-final-gate.json').read_text());assert gate['guard']['exitCode']==0
assert gate['source']['beforeEqualsAfterEqualsCurrent'] and gate['junit']['allFreshAfterGuardStart']
assert sha(ROOT/'.tdd/verification/issue123-durable-budget-source-manifest.json')==gate['source']['manifestSha256']
source=json.loads((ROOT/'.tdd/verification/issue123-durable-budget-source-manifest.json').read_text())['files']
assert all(sha(ROOT/p)==s for p,s in source.items())
for k in ('harness','callback'):assert sha(plan[k]['path'])==plan[k]['sha256']
assert sha(ROOT/plan['oraclePath'])==plan['oracleSha256']
assert not list(BASE.glob('*-consumed.json')) and not (BASE/'live-invocation.json').exists()
cp=pathlib.Path('/tmp/issue123-decision-smoke-classpath.txt').read_text()
comp=subprocess.run(['/tmp/meetme-jdk17/bin/javac','-cp',cp,'-d','/tmp',plan['harness']['path']],capture_output=True,text=True);assert comp.returncode==0
rec={'state':'STARTED_UNKNOWN_HELD','startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'planSha256':FROZEN,'guardSha256':sha(ROOT/'.tdd/verification/issue123-durable-budget-final-gate.json'),'sourceFilesVerified':len(source),'cumulativeReservedUsd':plan['cumulativeReservedUsd']}
fd=os.open(BASE/'live-invocation.json',os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
with os.fdopen(fd,'w') as f:f.write(json.dumps(rec)+'\n')
env=os.environ.copy();env['ISSUE123_TWO_STAGE_PLAN_SHA']=FROZEN
cmd=['/tmp/meetme-jdk17/bin/java','-cp',cp,'Issue123TwoStageSmokeHarness','run-live',str(ROOT/'.codex/tdd-evidence/issue123-minimal-smoke/LIVE-KO-03-request.json'),str(BASE),plan['callback']['path']]
start=time.monotonic();proc=subprocess.Popen(cmd,cwd=ROOT,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
try:out,err=proc.communicate(timeout=25)
except subprocess.TimeoutExpired:
 os.killpg(proc.pid,signal.SIGKILL);out,err=proc.communicate();rec['outerTimeout']=True
rec.update(exitCode=proc.returncode,wholeJvmInvocationElapsedMs=round((time.monotonic()-start)*1000,1),finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),state='OBSERVED_PENDING_INDEPENDENT_SEMANTIC_REVIEW_INVOICE_UNVERIFIED')
for name,data in [('stdout.json',out),('stderr.txt',err)]:
 assert len(data)<=65536
 fd=os.open(BASE/name,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
 with os.fdopen(fd,'wb') as f:f.write(data)
rec['actualNewHttpAttempts']=len(list(BASE.glob('*-consumed.json')))
(BASE/'live-invocation.json').write_text(json.dumps(rec,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(rec,ensure_ascii=False))
if (BASE/'live-two-stage.json').exists():print((BASE/'live-two-stage.json').read_text())
else:print(json.dumps({'structuredReportMissing':True,'stdoutSafeMetadata':out.decode(errors='replace')}))
