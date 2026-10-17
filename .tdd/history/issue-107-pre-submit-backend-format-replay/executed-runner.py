from pathlib import Path
import os,shutil,json,hashlib,datetime,subprocess,tempfile,difflib,xml.etree.ElementTree as E
ROOT=Path('/workspace/issue107-baseline-integration/server');SCRATCH=Path(tempfile.mkdtemp(prefix='meetme107-backend-format-replay-'));EVID=ROOT/'.codex/tdd-evidence/backend-format-replay';EVID.mkdir(parents=True,exist_ok=True);HIST=ROOT/'.tdd/history/issue-107-pre-submit-backend-format-replay';HIST.mkdir(exist_ok=True)
BASE='src/test/kotlin/com/meetme/server/submission/adapter/input/web/';FILES=['PreSubmissionCheckWorkerPostgresTest.kt','PreSubmissionPrivacyQueuePostgresTest.kt','PrecheckDefensiveBoundaryPostgresTest.kt','PrecheckRateLimitRoutingContractTest.kt'];EXPECT=['366dd031be2f2717234025717975451aa7ab9b6715b64a85b4443cf53a271aeb','0ded3b08ddfc9cab3835e5780a23f40a206614f68354a3a02cda36ca438be548','89275de222afa014b08bb7a5ce6c89ef677fa91ed506637ab177d239c9e674cd','b056297553ad9677baf3380c6ad64d6dabc728ed10c3b7cb163cd726a30d07bc'];TESTS=[BASE+n for n in FILES];digest=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest();stamp=lambda:datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z')
BEFORE={str(p.relative_to(ROOT)):digest(p) for p in (ROOT/'src/main').rglob('*') if p.is_file()};BEFORE.update({p:digest(ROOT/p) for p in TESTS});assert [BEFORE[p] for p in TESTS]==EXPECT
for n in ['gradle','gradlew','gradlew.bat','build.gradle.kts','settings.gradle.kts','gradle.properties']:
 p=ROOT/n
 if not p.exists():continue
 if p.is_dir():shutil.copytree(p,SCRATCH/n)
 else:shutil.copy2(p,SCRATCH/n)
shutil.copytree(ROOT/'src/main',SCRATCH/'src/main')
if (ROOT/'src/test/resources').exists():shutil.copytree(ROOT/'src/test/resources',SCRATCH/'src/test/resources')
(SCRATCH/'replay-test').mkdir()
for p in TESTS:shutil.copy2(ROOT/p,SCRATCH/'replay-test'/Path(p).name)
INIT=SCRATCH/'replay.init.gradle';INIT.write_text("allprojects { plugins.withId('org.jetbrains.kotlin.jvm') { kotlin.sourceSets.getByName('test').kotlin.setSrcDirs(['replay-test']) } }\n")
WORK='src/main/kotlin/com/meetme/server/precheck/application/service/PreSubmissionCheckWorker.kt';RATE='src/main/kotlin/com/meetme/server/shared/adapter/input/web/RateLimitFilter.kt';ORIG={p:(SCRATCH/p).read_bytes() for p in [WORK,RATE]}
record={'startedAt':stamp(),'classification':'POST_IMPLEMENTATION_RECONSTRUCTED_RED_SUPPLEMENT','exactOriginalSkeletonAvailable':False,'limitation':'Exact chronological production skeleton bytes were not preserved. Current tests are byte exact; no-op worker is a reconstructed behavior-absent skeleton, same-source and route omissions replay previously observed missing behaviors. This is not the original chronological RED.','sourceRevision':'bd8818625aa51480e3a06c1fa17d3e223fa1cfbe','scratch':str(SCRATCH),'originalSourceAndTests':BEFORE,'externalModelCalls':0,'realOAuthCalls':0,'cycles':[],'status':'RUNNING'}
def save(): (HIST/'manifest.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
def restore():
 for p,b in ORIG.items():(SCRATCH/p).write_bytes(b)
def run(label,selectors):
 resultdir=SCRATCH/'build/test-results/test'
 if resultdir.exists():shutil.rmtree(resultdir)
 cmd=['flock','/tmp/meetme-gradle.lock','bash','gradlew','--offline','--no-daemon','--max-workers=2','-I',str(INIT),'test']
 for t in selectors:cmd+=['--tests',t]
 log=ROOT/'.codex/tdd-evidence'/f'{label}.log';env={**os.environ,'JAVA_HOME':'/tmp/meetme-jdk17','GRADLE_USER_HOME':'/tmp/meetme-gradle-home','MEETME_CANDIDATE_LIVE_PROBE':'false','JAVA_TOOL_OPTIONS':'-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts'}
 print('START',label,flush=True)
 with log.open('w') as stream:r=subprocess.run(cmd,cwd=SCRATCH,env=env,stdout=stream,stderr=subprocess.STDOUT)
 sums=dict(tests=0,failures=0,errors=0,skipped=0);fail=[]
 for p in resultdir.glob('TEST-*.xml'):
  tree=E.parse(p).getroot();shutil.copy2(p,EVID/(label+'-'+p.name))
  for k in sums:sums[k]+=int(tree.attrib[k])
  for t in tree.findall('testcase'):
   for tag in ['failure','error']:
    for f in t.findall(tag):fail.append({'name':tree.attrib['name']+'.'+t.attrib['name'],'kind':tag,'type':f.attrib.get('type'),'message':f.attrib.get('message')})
 print('END',label,'exit',r.returncode,sums,flush=True)
 return {'recordedAt':stamp(),'exitCode':r.returncode,'totals':sums,'failures':fail,'command':cmd,'rawLog':str(log.relative_to(ROOT)),'rawLogSha256':digest(log)}
def mutate(cycle):
 if cycle=='worker':
  p=SCRATCH/WORK;s=p.read_text();start=s.index('    fun runOne(): Boolean {');body=s.index('{',start);depth=1;i=body+1
  while depth:
   if s[i]=='{':depth+=1
   elif s[i]=='}':depth-=1
   i+=1
  p.write_text(s[:body]+'{\n        return false // reconstructed behavior-absent worker dispatch\n    }'+s[i:])
 elif cycle=='defense':
  p=SCRATCH/WORK;s=p.read_text();old='                it.proposedText == raw ||\n';assert s.count(old)==1;p.write_text(s.replace(old,''))
 else:
  p=SCRATCH/RATE;s=p.read_text();old='(CLARIFICATION_ANSWER.matches(path) || PRECHECK_COMMAND.matches(path))';assert s.count(old)==1;p.write_text(s.replace(old,'CLARIFICATION_ANSWER.matches(path)'))
cycles=[('worker',['*PreSubmissionCheckWorkerPostgresTest.PS03 질문 제안은 source 원문을 자동 치환하지 않으며 최종 분석 작업을 만들지 않는다','*PreSubmissionPrivacyQueuePostgresTest.PS22 전역 대기100 포화에도 101번째 원문은 FAILED_QUEUE로 보존하고 한자리 뒤 명시retry만 접수한다'],TESTS[:2],2),('defense',['*PrecheckDefensiveBoundaryPostgresTest.PS08 source 원문과 같은 제안은 opaque 선택ID가 유효해도 INVALID_RESPONSE다'],[TESTS[2]],1),('rate',['*PrecheckRateLimitRoutingContractTest.PS09 신규 검사 write 네 경로도 기존 제출 세션 30회 제한을 우회하지 못한다'],[TESTS[3]],4)]
save()
for cycle,selectors,paths,count in cycles:
 restore();mutate(cycle);changes=[]
 for p,b in ORIG.items():
  new=(SCRATCH/p).read_bytes()
  if b!=new:
   diff=''.join(difflib.unified_diff(b.decode().splitlines(True),new.decode().splitlines(True),fromfile='a/'+p,tofile='b/'+p));d=HIST/f'{cycle}-{Path(p).name}.diff';d.write_text(diff);changes.append({'path':p,'restoredSHA256':hashlib.sha256(b).hexdigest(),'reconstructedSHA256':hashlib.sha256(new).hexdigest(),'exactDiff':str(d.relative_to(ROOT))})
 name=f'issue-107-pre-submit-{cycle}-format-replay';red=run(name,selectors);r={'cycle':cycle,'changedScratchProduction':changes,'red':red};record['cycles'].append(r);save()
 assert red['exitCode']!=0 and red['totals']=={'tests':count,'failures':count,'errors':0,'skipped':0},'Expected exact genuine assertion RED count'
 assert all(f['kind']=='failure' and ('Assertion' in (f['type'] or '') or 'Comparison' in (f['type'] or '')) for f in red['failures']),'Compiler/environment/fixture errors are not RED'
 restore();assert all((SCRATCH/p).read_bytes()==b for p,b in ORIG.items());green=run(name+'-restored-green',selectors);r['restoredGreen']=green;r['scratchProductRestoredBytesExact']=True;save();assert green['exitCode']==0 and green['totals']=={'tests':count,'failures':0,'errors':0,'skipped':0}
 canonical={'workItem':name,'sourceRevision':record['sourceRevision'],'testFiles':[{'path':p,'sha256':BEFORE[p]} for p in paths],'command':red['command'],'failedTests':[f['name'] for f in red['failures']],'expectedFailureCategory':'MISSING_BEHAVIOR','recordedAt':red['recordedAt']};assert len(canonical)==7
 (ROOT/'.tdd/red'/f'{name}.json').write_text(json.dumps(canonical,ensure_ascii=False,indent=2)+'\n')
record['originalSourceAndTestsUnchanged']=all(digest(ROOT/p)==h for p,h in BEFORE.items());record['testCopiesUnchanged']=all(digest(SCRATCH/'replay-test'/Path(p).name)==BEFORE[p] for p in TESTS);record['finishedAt']=stamp();record['status']='PASS' if record['originalSourceAndTestsUnchanged'] and record['testCopiesUnchanged'] else 'SHARED_SOURCE_CHANGED';save();print('FINISHED',record['status'],flush=True)
