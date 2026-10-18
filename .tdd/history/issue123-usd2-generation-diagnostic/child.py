import datetime,hashlib,json,os,pathlib,sys,time,urllib.error,urllib.request,email.utils
BASE=pathlib.Path('/workspace/issue123-durable-budget/server/.codex/tdd-evidence/issue123-usd2-generation-diagnostic')
number=int(sys.argv[1]);assert number in (1,2)
planfile=BASE/'execution-plan.json';assert hashlib.sha256(planfile.read_bytes()).hexdigest()==os.environ['ISSUE123_DIAGNOSTIC_PLAN_SHA']
plan=json.loads(planfile.read_text());assert plan['approvedAggregateUsd']=='2.00' and plan['cumulativeMaximumReservedUsd']=='1.01330944'
body=(BASE/'generation-request.json').read_bytes();assert hashlib.sha256(body).hexdigest()==plan['requestSha256'] and len(body)<16384
config=json.loads(body)['generationConfig'];assert config['candidateCount']==1 and config['maxOutputTokens']==2048 and config['thinkingConfig']=={'thinkingLevel':'LOW'}
if number==2:
 prior=json.loads((BASE/'attempt1-client-observation.json').read_text());assert prior['retryAllowed'] is True and time.time()-prior['clientFinishedEpoch']>=10
key=os.environ.get('GEMINI_API_KEY');assert key
record={'attempt':number,'attempts':1,'state':'UNKNOWN_HELD','startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'requestSha256':plan['requestSha256'],'timeoutSeconds':60}
path=BASE/('attempt'+str(number)+'-consumed.json');fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
def save():
 temp=path.with_suffix('.atomic');fd=os.open(temp,os.O_CREAT|os.O_TRUNC|os.O_WRONLY,0o600)
 with os.fdopen(fd,'w') as f:json.dump(record,f,ensure_ascii=False,indent=2);f.write('\n')
 os.replace(temp,path)
with os.fdopen(fd,'w') as f:json.dump(record,f);f.write('\n')
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
start=time.monotonic();deadline=start+60;rc=4
request=urllib.request.Request(plan['url'],data=body,method='POST',headers={'x-goog-api-key':key,'Content-Type':'application/json','Accept':'application/json'})
def retry_after(headers):
 raw=headers.get('Retry-After');record['retryAfter']=sanitize(raw.encode()).decode(errors='replace')[:120] if raw is not None else None
 if raw is None:return None
 if len(raw)>120:return 'UNPARSEABLE'
 normalized=raw.strip()
 if normalized.isascii() and normalized.isdecimal():return int(normalized)
 try:
  parsed=email.utils.parsedate_to_datetime(normalized)
  if parsed.tzinfo is None:return 'UNPARSEABLE'
  return max(0,parsed.timestamp()-time.time())
 except Exception:return 'UNPARSEABLE'
def sanitize(raw):
 safe=raw
 for name in ('GEMINI_API_KEY','OPENAI_API_KEY'):
  value=os.environ.get(name)
  if value:safe=safe.replace(value.encode(),b'[REDACTED]')
 return safe
try:
 try:response=urllib.request.build_opener(NoRedirect).open(request,timeout=max(.001,deadline-time.monotonic()))
 except urllib.error.HTTPError as error:response=error
 with response:
  status=response.status;record.update(state='HEADERS_OBSERVED_BODY_INCOMPLETE',httpStatus=status,headersObservedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),headersElapsedMs=round((time.monotonic()-start)*1000,1),contentType=sanitize(response.headers.get('Content-Type','').encode()).decode(errors='replace')[:120],retryAfterSeconds=retry_after(response.headers));save()
  chunks=[];size=0;first=True
  while True:
   if time.monotonic()>=deadline:raise TimeoutError()
   part=response.read(min(2048,16385-size))
   if not part:break
   if first:record['firstBodyChunkElapsedMs']=round((time.monotonic()-start)*1000,1);first=False
   chunks.append(part);size+=len(part);record.update(bodyBytesRead=size,lastProgressAt=datetime.datetime.now(datetime.timezone.utc).isoformat());save()
   if size>16384:raise ValueError('BODY_LIMIT')
  if time.monotonic()>=deadline:raise TimeoutError()
  raw=b''.join(chunks);safe=sanitize(raw);record.update(responseSha256=hashlib.sha256(raw).hexdigest(),storedBodySanitized=safe!=raw)
  if status==200:
   json.loads(safe)
   target=BASE/('attempt'+str(number)+'-response.raw.json');fd=os.open(target,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
   with os.fdopen(fd,'wb') as f:f.write(safe)
   record.update(state='HTTP200_OBSERVED_PENDING_PRODUCTION_PARSER',storedResponseSha256=hashlib.sha256(safe).hexdigest(),successfulBodyPath=str(target));rc=0
  else:
   try:
    error=json.loads(safe).get('error',{});record['sanitizedError']={k:(str(error[k])[:512] if k=='message' else error[k]) for k in ('code','status','message') if k in error and isinstance(error[k],(str,int))}
   except Exception:record['errorBodyParsed']=False
   transient=status in (408,429,500,502,503,504)
   allowed_retry_after=record.get('retryAfterSeconds') is None or (isinstance(record.get('retryAfterSeconds'),(int,float)) and record['retryAfterSeconds']<=10)
   record.update(state='HTTP_FAILURE_UNKNOWN_HELD',transientRetryEligible=transient and allowed_retry_after);rc=3 if transient and allowed_retry_after else 4
except (TimeoutError,urllib.error.URLError) as error:
 wait=record.get('retryAfterSeconds');status=record.get('httpStatus')
 eligible=(status is None or status in (408,429,500,502,503,504)) and (wait is None or (isinstance(wait,(int,float)) and wait<=10))
 record.update(state='NETWORK_OR_TIMEOUT_UNKNOWN_HELD',exceptionType=type(error).__name__,transientRetryEligible=eligible);rc=3 if eligible else 4
except Exception as error:
 record.update(state='STOP_CONTRACT_OR_LOCAL_FAILURE_UNKNOWN_HELD',exceptionType=type(error).__name__,transientRetryEligible=False);rc=4
record.update(clientFinishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),elapsedHttpObservationMs=round((time.monotonic()-start)*1000,1));save()
print(json.dumps(record,ensure_ascii=False),flush=True);raise SystemExit(rc)
