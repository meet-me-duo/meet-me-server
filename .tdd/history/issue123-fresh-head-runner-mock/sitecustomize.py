# Independent mock fails closed even if fixture initialization fails; no helper code is altered.
import os,sys,socket

def _early_network_block(*args,**kwargs):raise RuntimeError('REAL_NETWORK_FORBIDDEN')
socket.create_connection=_early_network_block;socket.getaddrinfo=_early_network_block;socket.socket.connect=_early_network_block;socket.socket.connect_ex=_early_network_block
try:
 import os,sys,json,pathlib,urllib.request,urllib.error,socket,time,io,base64
 control=json.loads(pathlib.Path(os.environ['ISSUE123_MOCK_CONTROL']).read_text());base=pathlib.Path(control['base']);eventpath=pathlib.Path(control['events'])
 def event(value):
  fd=os.open(eventpath,os.O_CREAT|os.O_APPEND|os.O_WRONLY,0o600)
  with os.fdopen(fd,'a') as f:f.write(json.dumps(value)+'\n')
 def deny_network(*a,**kw):
  event({'type':'BLOCKED_REAL_NETWORK'});raise RuntimeError('REAL_NETWORK_FORBIDDEN')
 socket.create_connection=deny_network;socket.getaddrinfo=deny_network;socket.socket.connect=deny_network;socket.socket.connect_ex=deny_network
 runner='/tmp/issue123-run-fresh-head-two-stage.py';callback='/tmp/issue123-fresh-head-http-callback.py'
 def trace(frame,eventname,arg):
  if eventname=='call' and frame.f_code.co_name=='main' and frame.f_code.co_filename in (runner,callback):
   frame.f_globals['BASE']=base
   if frame.f_code.co_filename==runner:
    frame.f_globals['FROZEN_PLAN_SHA256']=control['planSha'];frame.f_globals['CLASS_DIRECTORY']=pathlib.Path(control['classes']+('-'+str(os.getpid()) if control.get('race')=='batch' else ''))
   sys.settrace(None)
  return None
 sys.settrace(trace)
 scenario=control['scenario'];key='SYNTHETIC-MOCK-KEY-DO-NOT-LEAK'
 original_open=os.open
 if control.get('race'):
  def schedule_open(path,flags,*args,**kwargs):
   target='live-invocation.json' if control['race']=='batch' else 'CLASSIFY-consumed.json'
   if pathlib.Path(path).name==target and flags & os.O_EXCL:
    barrier=pathlib.Path(control['barrier']);barrier.mkdir(exist_ok=True)
    ready=barrier/str(os.getpid());fd=original_open(ready,os.O_CREAT|os.O_WRONLY,0o600);os.close(fd)
    until=time.monotonic()+10
    while len(list(barrier.iterdir()))<2:
     if time.monotonic()>=until:raise RuntimeError('MOCK_BARRIER_SETUP_TIMEOUT')
     time.sleep(.005)
   return original_open(path,flags,*args,**kwargs)
  os.open=schedule_open
 if scenario=='actual-head-mismatch':
  import subprocess
  real_check_output=subprocess.check_output
  def altered_head(command,*args,**kwargs):
   if command==['git','rev-parse','HEAD']:return '0'*40+'\n'
   return real_check_output(command,*args,**kwargs)
  subprocess.check_output=altered_head
 original_monotonic=time.monotonic
 if scenario=='expired-before-open' and len(sys.argv)>1 and sys.argv[1]=='CLASSIFY':
  clock_calls=[0]
  def jumped():
   clock_calls[0]+=1
   return original_monotonic()+(16 if clock_calls[0]>=2 else 0)
  time.monotonic=jumped
 payload={'verdict':'NEEDS_CHOICE','prompt':'8시부터 9시까지가 오전 시간대인지 오후(저녁) 시간대인지 알려주세요.','sourceExcerpt':'8시부터 9시까지','choices':[{'id':'A','label':'오전 8시부터 9시까지','proposedText':'10월 24일 토요일 오전 8시부터 9시까지 가능해요.'},{'id':'B','label':'오후 8시부터 9시까지','proposedText':'10월 24일 토요일 오후 8시부터 9시까지 가능해요.'}]}
 text=json.dumps(payload,ensure_ascii=False,indent=2)
 import hashlib
 assert hashlib.sha256(text.encode()).hexdigest()=='bf0f16ba09b40c680f72f0cfe2093318791fd4b744f3b8712394ddbe859e3c7b'
 a={'model':'gpt-6-luna','answers':[{'type':'choice','name':'material_ambiguity','choice':'CLEAR','confidence':.95 if scenario=='clear-A' else .06,'probabilities':[{'value':'CLEAR','probability':.95 if scenario=='clear-A' else .37},{'value':'NEEDS_REVIEW','probability':.03 if scenario=='clear-A' else .31},{'value':'INDETERMINATE','probability':.02 if scenario=='clear-A' else .32}]}],'usage':{'input_tokens':512,'input_tokens_details':{'cached_tokens':0,'cache_write_tokens':0},'output_tokens':0,'output_tokens_details':{'reasoning_tokens':0},'total_tokens':512}}
 b={'candidates':[{'finishReason':'STOP','content':{'role':'model','parts':[{'text':text,'thoughtSignature':base64.b64encode(b'synthetic-fixture-opaque').decode()}]}}],'usageMetadata':{'promptTokenCount':530,'candidatesTokenCount':188,'thoughtsTokenCount':366,'totalTokenCount':1084}}
 class Reply:
  def __init__(self,stage,status,raw,headers):self.stage=stage;self.status=status;self.raw=raw;self.headers=headers;self.pos=0;self.reads=0
  def __enter__(self):return self
  def __exit__(self,*args):self.close()
  def close(self):pass
  def read(self,n=-1):
   self.reads+=1
   record=json.loads((base/(self.stage+'-consumed.json')).read_text())
   if self.reads==1:
    assert record.get('httpStatus')==self.status and record.get('state')=='HEADERS_OBSERVED_BODY_INCOMPLETE','HEADERS_NOT_DURABLE_BEFORE_READ'
    event({'type':'HEADERS_DURABLE','stage':self.stage,'status':self.status})
   else:
    assert record.get('bodyBytesRead',0)==self.pos,'BODY_PROGRESS_NOT_DURABLE'
   if scenario=='body-timeout-'+self.stage and self.reads>=2:raise TimeoutError(key+'-timeout-diagnostic')
   if scenario=='status-timeout-'+self.stage and self.reads>=2:raise TimeoutError(key+'-status-timeout')
   if self.stage=='CLASSIFY' and self.reads==1 and scenario=='tamper-next-request':
    (base/'count-request.json').write_bytes(b'{}')
   if self.pos>=len(self.raw):return b''
   if scenario=='shared-deadline':time.sleep(.05)
   count=min(n if n>=0 else len(self.raw),17 if self.reads==1 else len(self.raw),len(self.raw)-self.pos)
   value=self.raw[self.pos:self.pos+count];self.pos+=count;return value
 class FakeHTTPError(urllib.error.HTTPError):
  def __init__(self,url,reply):
   super().__init__(url,reply.status,'bounded synthetic '+key,reply.headers,io.BytesIO(reply.raw));self.reply=reply
  def read(self,n=-1):return self.reply.read(n)
  def __enter__(self):return self
  def __exit__(self,*a):self.close()
  def close(self):pass
 class Opener:
  def open(self,req,timeout=None):
   url=req.full_url
   stage=next((s for s,u in control['urls'].items() if u==url),None)
   assert stage,'UNAPPROVED_URL'
   assert req.get_method()=='POST'
   assert 0<timeout<=15
   assert key in str(req.header_items()),'FAKE_KEY_NOT_USED'
   event({'type':'OPEN_STUB','stage':stage,'timeout':timeout,'remainingMillis':int(sys.argv[4]),'url':url,'requestSha256':hashlib.sha256(req.data).hexdigest(),'atMonotonic':original_monotonic()})
   status=503 if scenario in ('503-'+stage,'status-timeout-'+stage,'redaction-'+stage) else 302 if scenario=='redirect-'+stage else 200
   headers={'Content-Type':'application/json','Retry-After':'0'}
   if scenario=='redaction-'+stage:
    headers['Content-Type']='application/json '+key
    raw=json.dumps({'error':{'code':503,'status':'UNAVAILABLE','message':key+'\n'+'x'*3000}}).encode()
   elif status!=200:raw=b'{"error":{"code":503,"status":"UNAVAILABLE","message":"synthetic"}}'
   elif stage=='CLASSIFY':raw=json.dumps(a).encode()
   elif stage=='COUNT':raw=json.dumps({'totalTokens':4097 if scenario=='count4097' else 530}).encode()
   else:raw=json.dumps(b,ensure_ascii=False).encode()
   if scenario=='oversize-'+stage:raw=b'x'*16385
   reply=Reply(stage,status,raw,headers)
   if status!=200:raise FakeHTTPError(url,reply)
   return reply
 
 def build(*handlers):
  assert any(isinstance(h if not isinstance(h,type) else h(),urllib.request.HTTPRedirectHandler) and (h if not isinstance(h,type) else h()).redirect_request(None,None,None,None,None,None) is None for h in handlers),'REDIRECT_POLICY_MISSING'
  return Opener()
 urllib.request.build_opener=build
 urllib.request.urlopen=lambda *a,**kw:(_ for _ in ()).throw(RuntimeError('UNEXPECTED_URLOPEN'))
 urllib.request.OpenerDirector.open=lambda *a,**kw:(_ for _ in ()).throw(RuntimeError('UNSTUBBED_OPENER'))
except BaseException:
 sys.stderr.write('MOCK_INITIALIZATION_FAILED\n')
 os._exit(97)
