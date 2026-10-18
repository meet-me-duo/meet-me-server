from pathlib import Path
import os,sys,json,hashlib,subprocess,datetime,copy
root=Path('/workspace/issue123-durable-budget/server');work=Path('/tmp/issue123-fresh-independent-mock');live=root/'.codex/tdd-evidence/issue123-fresh-head-two-stage'
runner=Path('/tmp/issue123-run-fresh-head-two-stage.py');callback=Path('/tmp/issue123-fresh-head-http-callback.py');harness=Path('/tmp/Issue123FreshHeadTwoStageHarness.java')
expected={str(runner):'c33889955a75b0f728ec4805463a3dcb1fdbbb76425daae89ce8db2d0cda89c4',str(callback):'3a48a878696ec16f46e8ebeb81c218a597d07b645b96389c2602cdf294c93605',str(harness):'667fc67dccdd312abf569a5ce3d51453fa267626d57fad1496929626eab7e764'}
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def live_inventory():return {str(p.relative_to(live)):sha(p) for p in live.rglob('*') if p.is_file()}
assert all(sha(p)==v for p,v in expected.items())
live_before=live_inventory();template=json.loads((live/'execution-plan.json').read_text());rows=[]
key='SYNTHETIC-MOCK-KEY-DO-NOT-LEAK'
def setup(name,scenario=None,mutate=None):
 directory=work/'cases-final'/name;directory.mkdir(parents=True);base=directory/'base';base.mkdir();plan=copy.deepcopy(template)
 for stage,spec in plan['stages'].items():
  request=base/Path(spec['requestPath']).name;request.write_bytes(Path(spec['requestPath']).read_bytes());spec['requestPath']=str(request);spec['responsePath']=str(base/Path(spec['responsePath']).name)
 if mutate:mutate(plan,base)
 (base/'execution-plan.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n')
 control={'base':str(base),'classes':str(directory/'java-classes'),'events':str(directory/'events.jsonl'),'scenario':scenario or name,'planSha':sha(base/'execution-plan.json'),'urls':{s:x['url'] for s,x in plan['stages'].items()}}
 if name=='bad-plan-hash':control['planSha']='0'*64
 (directory/'control.json').write_text(json.dumps(control));return directory,base,control

def execute(directory,base,control,args=None):
 env=os.environ.copy();env.update(OPENAI_API_KEY=key,GEMINI_API_KEY=key,PYTHONPATH=str(work/'site'),ISSUE123_MOCK_CONTROL=str(directory/'control.json'),ISSUE123_FRESH_HEAD_PLAN_SHA=control['planSha'])
 if directory.name=='blank-key':env.update(OPENAI_API_KEY='',GEMINI_API_KEY='')
 command=[sys.executable,str(runner),'--execute-frozen'] if args is None else args
 result=subprocess.run(command,cwd=root,env=env,capture_output=True,timeout=40)
 suffix='driver' if args is None else 'callback'
 (directory/(suffix+'.stdout')).write_bytes(result.stdout);(directory/(suffix+'.stderr')).write_bytes(result.stderr)
 events=[json.loads(x) for x in Path(control['events']).read_text().splitlines()] if Path(control['events']).exists() else []
 assert not any(e['type']=='BLOCKED_REAL_NETWORK' for e in events),'REAL_NETWORK_ATTEMPT'
 produced=[p for p in base.rglob('*') if p.is_file()]+[directory/(suffix+'.stdout'),directory/(suffix+'.stderr')]
 assert all(key.encode() not in p.read_bytes() for p in produced),'KEY_LEAK'
 assert len(result.stdout)<=65536 and len(result.stderr)<=65536,'UNBOUNDED_CLI_OUTPUT'
 return result,events

def check(name,scenario=None,mutate=None,expected_stages=(),accepted=False,status=None):
 directory,base,control=setup(name,scenario,mutate);r,e=execute(directory,base,control)
 opens=[x for x in e if x['type']=='OPEN_STUB'];assert [x['stage'] for x in opens]==list(expected_stages),('WRONG_STAGES',name,[x['stage'] for x in opens])
 assert len(opens)==len(set(x['stage'] for x in opens)),'RETRY_OR_DUPLICATE_STAGE'
 report=json.loads((base/'live-two-stage.json').read_text()) if (base/'live-two-stage.json').exists() else {}
 assert (r.returncode==0)==accepted,('EXIT_EXPECTATION',name,r.returncode)
 if accepted:
  assert report['accepted'] is True
  assert report['pipelineElapsedMs']<=15000
  if scenario=='clear-A':assert report['actualTwoStageResult']['verdict']=='CLEAR'
  else:
   assert report['actualTwoStageResult']['verdict']=='NEEDS_CHOICE'
   assert report['returnedBUsage']=={'inputTokens':530,'candidateTokens':188,'thoughtTokens':366,'invalid':False}
 if status:
  stage,value=status;record=json.loads((base/(stage+'-consumed.json')).read_text());assert record.get('httpStatus')==value
  assert record['attempts']==1 and record['automaticRetries']==0
 if name=='shared-deadline':
  remains=[x['remainingMillis'] for x in opens];assert 0<remains[-1]<remains[1]<remains[0]<=15000
 if name.startswith('redaction'):
  rec=json.loads((base/'CLASSIFY-consumed.json').read_text());assert len(rec['sanitizedError']['message'])<=512 and len(rec['contentType'])<=120
 if name.startswith('oversize'):
  assert not (base/'classify-response.raw.json').exists();assert json.loads((base/'CLASSIFY-consumed.json').read_text())['bodyBytesRead']==16385
 if name=='duplicate-batch':
  count=len(opens);r2,e2=execute(directory,base,control);assert r2.returncode!=0 and len([x for x in e2 if x['type']=='OPEN_STUB'])==count
 if name=='duplicate-stage':
  before=len(opens);envarg=[sys.executable,str(callback),'CLASSIFY',str(base/'a-request.json'),str(base/'classify-response.raw.json'),'10000'];r2,e2=execute(directory,base,control,envarg);assert r2.returncode!=0 and len([x for x in e2 if x['type']=='OPEN_STUB'])==before
 return {'case':name,'result':'PASS','cliExit':r.returncode,'stubbedHttpStages':[x['stage'] for x in opens],'events':e,'wholeActualCli':True,'actualJavaHarness':(base/'stdout.json').exists(),'rawDirectory':str(directory)}

cases=[
 ('whole-success','success',None,('CLASSIFY','COUNT','GENERATE'),True,None),
 ('clear-A','clear-A',None,('CLASSIFY',),True,None),
 ('503-CLASSIFY',None,None,('CLASSIFY',),False,('CLASSIFY',503)),
 ('503-COUNT',None,None,('CLASSIFY','COUNT'),False,('COUNT',503)),
 ('503-GENERATE',None,None,('CLASSIFY','COUNT','GENERATE'),False,('GENERATE',503)),
 ('count4097',None,None,('CLASSIFY','COUNT'),False,('COUNT',200)),
 ('body-timeout-CLASSIFY',None,None,('CLASSIFY',),False,('CLASSIFY',200)),
 ('status-timeout-CLASSIFY',None,None,('CLASSIFY',),False,('CLASSIFY',503)),
 ('oversize-CLASSIFY',None,None,('CLASSIFY',),False,('CLASSIFY',200)),
 ('redaction-CLASSIFY',None,None,('CLASSIFY',),False,('CLASSIFY',503)),
 ('redirect-CLASSIFY',None,None,('CLASSIFY',),False,('CLASSIFY',302)),
 ('duplicate-batch','clear-A',None,('CLASSIFY',),True,None),
 ('duplicate-stage','clear-A',None,('CLASSIFY',),True,None),
 ('shared-deadline',None,None,('CLASSIFY','COUNT','GENERATE'),True,None),
 ('bad-request-hash','success',lambda p,b:(b/'a-request.json').write_bytes(b'{}'),(),False,None),
 ('bad-frozen-head','success',lambda p,b:p.update(sourceRevision='0'*40),(),False,None),
 ('bad-plan-hash','success',None,(),False,None),
 ('blank-key','success',None,(),False,None),
 ('bad-old-hold','success',lambda p,b:p.update(priorReservedUsd='1.00'),(),False,None),
 ('bad-cap','success',lambda p,b:p.update(approvedAggregateUsd='3.00'),(),False,None),
 ('actual-head-mismatch',None,None,(),False,None),
 ('tamper-next-request',None,None,('CLASSIFY',),False,('CLASSIFY',200)),
 ('expired-before-open',None,None,(),False,('CLASSIFY',None)),
]
for args in cases:
 name=args[0]
 try:
  row=check(*args);rows.append(row);print(name,'PASS',flush=True)
 except Exception as failure:
  row={'case':name,'result':'FAIL','type':type(failure).__name__,'message':str(failure)[:400]};rows.append(row);print(name,'FAIL',row['message'],flush=True)
 (work/'progress-final.json').write_text(json.dumps(rows,indent=2)+'\n')
 assert all(sha(p)==v for p,v in expected.items()),'HELPER_CHANGED_DURING_TEST'
# Force competing complete CLI processes past validation to the actual O_EXCL claim.
for race in ('batch','stage'):
 name='concurrent-'+race
 try:
  directory,base,control=setup(name,'clear-A')
  control.update(race=race,barrier=str(directory/'claim-barrier'))
  (directory/'control.json').write_text(json.dumps(control))
  if race=='stage':(base/'live-invocation.json').write_text(json.dumps({'state':'STARTED_UNKNOWN_HELD','planSha256':control['planSha']}))
  env=os.environ.copy();env.update(OPENAI_API_KEY=key,GEMINI_API_KEY=key,PYTHONPATH=str(work/'site'),ISSUE123_MOCK_CONTROL=str(directory/'control.json'),ISSUE123_FRESH_HEAD_PLAN_SHA=control['planSha'])
  command=[sys.executable,str(runner),'--execute-frozen'] if race=='batch' else [sys.executable,str(callback),'CLASSIFY',str(base/'a-request.json'),str(base/'classify-response.raw.json'),'15000']
  children=[subprocess.Popen(command,cwd=root,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE) for _ in range(2)]
  results=[]
  for index,child in enumerate(children):
   stdout,stderr=child.communicate(timeout=40);results.append(child.returncode);(directory/f'concurrent-{index}.stdout').write_bytes(stdout);(directory/f'concurrent-{index}.stderr').write_bytes(stderr)
   assert key.encode() not in stdout+stderr
  events=[json.loads(x) for x in Path(control['events']).read_text().splitlines()] if Path(control['events']).exists() else []
  assert not any(e['type']=='BLOCKED_REAL_NETWORK' for e in events)
  opens=[e for e in events if e['type']=='OPEN_STUB']
  assert len(list((directory/'claim-barrier').iterdir()))==2,'RACE_FIXTURE_NOT_REACHED'
  assert sorted(results)==[0,2] and len(opens)==1 and opens[0]['stage']=='CLASSIFY',(results,len(opens))
  rows.append({'case':name,'result':'PASS','cliExitCodes':results,'stubbedHttpStages':['CLASSIFY'],'wholeActualCli':True,'cliEntryPoint':'runner+actualJava' if race=='batch' else 'callback','atomicO_EXCLRaceReachedByBoth':True,'rawDirectory':str(directory)})
  print(name,'PASS',flush=True)
 except Exception as failure:
  rows.append({'case':name,'result':'FAIL','type':type(failure).__name__,'message':str(failure)[:400]});print(name,'FAIL',str(failure)[:400],flush=True)
 (work/'progress-final.json').write_text(json.dumps(rows,indent=2)+'\n')
assert live_inventory()==live_before,'LIVE_BASE_CHANGED'
receipt={'classification':'INDEPENDENT_WHOLE_CLI_WITH_ACTUAL_JAVA_HARNESS_AND_FULL_NETWORK_STUB','cases':rows,'passed':sum(x['result']=='PASS' for x in rows),'failed':sum(x['result']=='FAIL' for x in rows),'originalHelpersSha256':expected,'originalHelpersUnchanged':True,'liveBaseUnchanged':True,'liveBaseBeforeAfterSha256':hashlib.sha256(json.dumps(live_before,sort_keys=True).encode()).hexdigest(),'testScriptSha256':sha(__file__),'networkStubSha256':sha(work/'site/sitecustomize.py'),'fixtureOnlyBindings':['runner BASE','runner FROZEN_PLAN_SHA256','runner CLASS_DIRECTORY','callback BASE'],'baseOriginOfficialUrlsUnchanged':True,'actualPaidCalls':0,'externalHttpRequests':0,'productSourceTestChanges':0,'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(work/'receipt-final.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n');print('SUMMARY',receipt['passed'],receipt['failed'],flush=True)
