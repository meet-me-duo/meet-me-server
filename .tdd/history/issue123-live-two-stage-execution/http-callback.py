import datetime, hashlib, json, os, pathlib, sys, time, urllib.error, urllib.request
BASE=pathlib.Path('/workspace/issue123-durable-budget/server/.codex/tdd-evidence/issue123-live-two-stage')
stage,request_arg,response_arg,remaining_arg=sys.argv[1:]
assert stage in ('CLASSIFY','COUNT','GENERATE')
manifest=BASE/'execution-plan.json'
assert hashlib.sha256(manifest.read_bytes()).hexdigest()==os.environ['ISSUE123_TWO_STAGE_PLAN_SHA']
plan=json.loads(manifest.read_text());assert plan['executionPermitted'] is True
gate=json.loads((BASE.parents[2]/'.tdd/verification/issue123-durable-budget-final-gate.json').read_text())
assert gate['guard']['exitCode']==0 and gate['source']['beforeEqualsAfterEqualsCurrent'] is True
assert plan['cumulativeReservedUsd']=='0.39063552' and plan['maximumNewHttpRequests']==3
spec=plan['stages'][stage]
request=pathlib.Path(request_arg).resolve();response=pathlib.Path(response_arg).resolve()
assert request==pathlib.Path(spec['requestPath']).resolve() and response==pathlib.Path(spec['responsePath']).resolve()
assert request.is_relative_to(BASE.resolve()) and response.is_relative_to(BASE.resolve())
body=request.read_bytes();assert len(body)<16384 and hashlib.sha256(body).hexdigest()==spec['requestSha256']
assert not response.exists()
if stage=='COUNT':
 upstream=json.loads((BASE/'CLASSIFY-consumed.json').read_text());assert upstream.get('httpStatus')==200 and upstream.get('state')=='HTTP_OBSERVED_INVOICE_UNVERIFIED'
 assert (BASE/'classify-response.raw.json').is_file()
if stage=='GENERATE':
 upstream=json.loads((BASE/'COUNT-consumed.json').read_text());assert upstream.get('httpStatus')==200 and upstream.get('state')=='HTTP_OBSERVED_INVOICE_UNVERIFIED'
 count=json.loads((BASE/'count-response.raw.json').read_text())['totalTokens'];assert type(count) is int and 1<=count<=4096
remaining=min(float(remaining_arg)/1000.0,15.0);assert 0<remaining<=15
key=os.environ.get(spec['keyEnv']);assert key
record={'stage':stage,'attempts':1,'state':'UNKNOWN_HELD','startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'requestSha256':spec['requestSha256']}
consumed=BASE/(stage+'-consumed.json')
fd=os.open(consumed,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
with os.fdopen(fd,'w') as f:f.write(json.dumps(record)+'\n')
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
headers={'Content-Type':'application/json','Accept':'application/json'}
if stage=='CLASSIFY':headers['Authorization']='Bearer '+key
else:headers['x-goog-api-key']=key
req=urllib.request.Request(spec['url'],data=body,headers=headers,method='POST')
started=time.monotonic();deadline=started+remaining;rc=1
try:
 with urllib.request.build_opener(NoRedirect).open(req,timeout=remaining) as reply:
  record['httpStatus']=reply.status
  chunks=[];size=0
  while True:
   if time.monotonic()>=deadline:raise TimeoutError()
   chunk=reply.read(min(2048,16385-size))
   if not chunk:break
   chunks.append(chunk);size+=len(chunk)
   if size>16384:raise ValueError('response limit')
  raw=b''.join(chunks)
  if time.monotonic()>=deadline:raise TimeoutError()
  json.loads(raw)
  fd=os.open(response,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
  with os.fdopen(fd,'wb') as f:f.write(raw)
  record.update(state='HTTP_OBSERVED_INVOICE_UNVERIFIED',responseSha256=hashlib.sha256(raw).hexdigest(),responseBytes=len(raw));rc=0
except urllib.error.HTTPError as e:
 record.update(state='STOP_HTTP_FAILURE_UNKNOWN_HELD',httpStatus=e.code);e.close()
except Exception as e:record.update(state='STOP_UNKNOWN_HELD',exceptionType=type(e).__name__)
record['elapsedHttpMs']=round((time.monotonic()-started)*1000,1)
record['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
consumed.write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(record),flush=True)
raise SystemExit(rc)
