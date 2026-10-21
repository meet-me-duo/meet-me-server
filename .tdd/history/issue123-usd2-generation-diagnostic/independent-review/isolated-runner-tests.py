import ast,contextlib,datetime,email.utils,hashlib,io,json,os,pathlib,signal,subprocess,sys,tempfile,urllib.error,urllib.request
from unittest.mock import patch
ROOT=pathlib.Path('/workspace/issue123-durable-budget/server')
CHILD=pathlib.Path(sys.argv[1]);INVOKER=pathlib.Path(sys.argv[2]);OUT=pathlib.Path(sys.argv[3])
SHA=lambda p:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
PAYLOAD=(ROOT/'.codex/tdd-evidence/issue123-live-two-stage/generation-request.json').read_bytes()
records=[]

def changed_code(path,root=None,base=None,frozen=None):
 tree=ast.parse(path.read_text())
 for node in tree.body:
  if isinstance(node,ast.Assign):
   name=node.targets[0].id if isinstance(node.targets[0],ast.Name) else None
   if name=='ROOT' and root is not None:node.value=ast.Call(func=ast.Attribute(value=ast.Name(id='pathlib',ctx=ast.Load()),attr='Path',ctx=ast.Load()),args=[ast.Constant(str(root))],keywords=[])
   if name=='BASE' and base is not None:node.value=ast.Call(func=ast.Attribute(value=ast.Name(id='pathlib',ctx=ast.Load()),attr='Path',ctx=ast.Load()),args=[ast.Constant(str(base))],keywords=[])
   if name=='FROZEN' and frozen is not None:node.value=ast.Constant(frozen)
 return compile(ast.fix_missing_locations(tree),str(path),'exec')

class Response:
 def __init__(self,status,wait):self.status=status;self.headers={'Content-Type':'application/json'};self.headers.update({'Retry-After':wait} if wait is not None else {})
 def __enter__(self):return self
 def __exit__(self,*args):return False
 def read(self,*args):raise TimeoutError('SYNTHETIC_BODY_TIMEOUT')

for status in (None,200,403,503):
 for wait in (None,'10','11','-1','1.5','1e0','NaN','bogus','', ' ', '9'*121, '１２',email.utils.formatdate(1700000005,usegmt=True),email.utils.formatdate(1700000011,usegmt=True)):
  if status is None and wait is not None:continue
  with tempfile.TemporaryDirectory(prefix='issue123-child-mock-') as tmp:
   b=pathlib.Path(tmp);(b/'generation-request.json').write_bytes(PAYLOAD)
   plan={'approvedAggregateUsd':'2.00','cumulativeMaximumReservedUsd':'1.01330944','requestSha256':hashlib.sha256(PAYLOAD).hexdigest(),'url':'https://invalid.example/NEVER_NETWORK'}
   (b/'execution-plan.json').write_text(json.dumps(plan));count=[0]
   class Opener:
    def open(self,*args,**kwargs):
     count[0]+=1
     if status is None:raise urllib.error.URLError(TimeoutError('SYNTHETIC_CONNECT_TIMEOUT'))
     return Response(status,wait)
   with patch.dict(os.environ,{'GEMINI_API_KEY':'SYNTHETIC_TEST_ONLY','ISSUE123_DIAGNOSTIC_PLAN_SHA':SHA(b/'execution-plan.json')},clear=True),patch.object(sys,'argv',[str(CHILD),'1']),patch.object(urllib.request,'build_opener',return_value=Opener()),patch('time.time',return_value=1700000000),contextlib.redirect_stdout(io.StringIO()):
    try:exec(changed_code(CHILD,base=b),{'__name__':'__main__'});rc=0
    except SystemExit as e:rc=e.code
   record=json.loads((b/'attempt1-consumed.json').read_text())
   validwait=wait is None or wait=='10' or wait==email.utils.formatdate(1700000005,usegmt=True)
   expected=(status is None or status in (408,429,500,502,503,504)) and validwait
   actual=record['transientRetryEligible']
   records.append({'path':'child-body-or-network-timeout','status':status,'retryAfter':wait,'expectedRetry':expected,'actualRetry':actual,'exitCode':rc,'mockOpens':count[0],'pass':actual==expected and (rc==3)==expected and count[0]==1})

for status in (None,200,403,503):
 for wait in (None,10,11,'UNPARSEABLE'):
  with tempfile.TemporaryDirectory(prefix='issue123-parent-mock-') as tmp:
   r=pathlib.Path(tmp);b=r/'isolated';b.mkdir();(r/'.tdd/verification').mkdir(parents=True)
   (r/'oracle.json').write_text('{}');(r/'mode.json').write_text('{}');(r/'child.py').write_text('# SYNTHETIC NEVER EXECUTE\n');(r/'Harness.java').write_text('// SYNTHETIC NEVER COMPILE\n')
   manifest=r/'.tdd/verification/issue123-durable-budget-source-manifest.json';manifest.write_text(json.dumps({'files':{}}))
   gate=r/'.tdd/verification/issue123-durable-budget-final-gate.json';gate.write_text(json.dumps({'guard':{'exitCode':0},'junit':{'allFreshAfterGuardStart':True},'source':{'manifestSha256':SHA(manifest)}}))
   plan={'cumulativeMaximumReservedUsd':'1.01330944','childPath':str(r/'child.py'),'childSha256':SHA(r/'child.py'),'harnessPath':str(r/'Harness.java'),'harnessSha256':SHA(r/'Harness.java'),'oraclePath':'oracle.json','oracleSha256':SHA(r/'oracle.json'),'modePath':'mode.json','modeSha256':SHA(r/'mode.json')}
   (b/'execution-plan.json').write_text(json.dumps(plan));created=[];gaps=[];kills=[]
   class FakeProcess:
    def __init__(self,cmd,**kwargs):
     n=int(cmd[-1]);created.append(n);self.n=n;self.pid=999999;self.returncode=-signal.SIGKILL;self.polls=0
     d={'state':'HEADERS_OBSERVED_BODY_INCOMPLETE' if status is not None else 'UNKNOWN_HELD','retryAfterSeconds':wait}
     if status is not None:d['httpStatus']=status
     (b/f'attempt{n}-consumed.json').write_text(json.dumps(d))
    def communicate(self,timeout=None):
     self.polls+=1
     if self.polls==1:raise subprocess.TimeoutExpired('SYNTHETIC_CHILD',60)
     return b'',None
   def fake_run(cmd,**kwargs):
    assert str(cmd[0]).endswith('/javac'),'No real parser/other subprocess expected in killed-child fixture'
    return subprocess.CompletedProcess(cmd,0,'','')
   with patch.dict(os.environ,{},clear=True),patch.object(sys,'argv',[str(INVOKER),'--execute-frozen']),patch.object(subprocess,'Popen',side_effect=FakeProcess),patch.object(subprocess,'run',side_effect=fake_run),patch.object(os,'killpg',side_effect=lambda *a:kills.append(a)),patch('time.sleep',side_effect=lambda s:gaps.append(s)),patch.object(urllib.request,'build_opener',side_effect=AssertionError('NETWORK_FORBIDDEN')),contextlib.redirect_stdout(io.StringIO()):
    exec(changed_code(INVOKER,root=r,base=b,frozen=SHA(b/'execution-plan.json')),{'__name__':'__main__'})
   record=json.loads((b/'attempt1-client-observation.json').read_text());expected=(status is None or status in (408,429,500,502,503,504)) and (wait is None or wait==10)
   actual=record['retryAllowed'];expectedn=2 if expected else 1
   records.append({'path':'parent-hard-kill','status':status,'retryAfterSeconds':wait,'expectedRetry':expected,'actualRetry':actual,'mockProcesses':created,'fakeGapSeconds':gaps,'pass':actual==expected and len(created)==expectedn and gaps==([10] if expected else []) and len(kills)==expectedn})

out={'method':'Full child/invoker bodies executed only in AST-relocated temporary fixtures, synthetic env, mock urllib/subprocess, fake kill/sleep. Real keys/network/subprocess/model calls ZERO. No production source/test modifications. Financial/hash gate fixtures are controls, not live gate verification.','childSha256':SHA(CHILD),'invokerSha256':SHA(INVOKER),'cases':len(records),'passed':sum(x['pass'] for x in records),'failed':sum(not x['pass'] for x in records),'results':records}
OUT.write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n');print(json.dumps({k:out[k] for k in ('childSha256','invokerSha256','cases','passed','failed')}));print('failedCases',json.dumps([x for x in records if not x['pass']],ensure_ascii=False));raise SystemExit(1 if out['failed'] else 0)
