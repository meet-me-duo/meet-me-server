from pathlib import Path
import fcntl,hashlib,json,os,re,shutil,subprocess,time
repo=Path('/workspace/issue123-representative-evaluation/server')
out=Path('/tmp/issue130-independent-tests/faults');out.mkdir(exist_ok=True)
scratch=out/'snapshot'
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def inventory():return {str(p.relative_to(repo)):digest(p) for p in sorted((repo/'evaluation/issue130').rglob('*')) if p.is_file() and p.suffix in ('.java','.py')}
env=os.environ.copy();env.update(OPENAI_API_KEY='',GEMINI_API_KEY='',JAVA_HOME='/tmp/meetme-jdk17')
java='/tmp/meetme-jdk17/bin/java';javac='/tmp/meetme-jdk17/bin/javac'
with open('/tmp/meetme-gradle.lock','a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX);original=inventory()
 assert not scratch.exists()
 subprocess.run(['git','clone','--shared','--no-checkout','--quiet',str(repo),str(scratch)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 for name in ['AmbiguityClassificationContract.kt','OpenAiAmbiguityClassifier.kt','AmbiguityClassifierOptions.kt']:
  rel=Path('src/main/kotlin/com/meetme/server/precheck/adapter/output/classification')/name
  (scratch/rel).parent.mkdir(parents=True,exist_ok=True);shutil.copy2(repo/rel,scratch/rel)
 rel=Path('src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/TwoStagePreSubmissionCheckProvider.kt');(scratch/rel).parent.mkdir(parents=True,exist_ok=True);shutil.copy2(repo/rel,scratch/rel)
 shutil.copytree(repo/'evaluation',scratch/'evaluation',ignore=shutil.ignore_patterns('__pycache__'))
 classes=out/'classes';classes.mkdir();prodcp=Path('/tmp/issue130-independent-tests/green-102/base/production-test-runtime.classpath').read_text().strip();runtime=str(classes)+os.pathsep+prodcp
 helper=scratch/'evaluation/issue130/Issue130RepresentativeDecisionsCli.java';python=scratch/'evaluation/issue130/run_representative_decisions.py'
 originals={helper:helper.read_text(),python:python.read_text()}
 def compile_helper(initial=False):
  sources=[helper]+(sorted((scratch/'evaluation/issue130/tests').glob('*.java')) if initial else [])
  r=subprocess.run([javac,'--release','17','-encoding','UTF-8','-cp',runtime,'-d',str(classes),*map(str,sources)],env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=30)
  if r.returncode:raise RuntimeError('MUTATION_COMPILE_ERROR')
 compile_helper(True)
 def run_java(ids,log,private=False):
  cmd=[java,f'-Dissue130.sourceRoot={scratch}','-cp',runtime,'Issue130PrivateCreationContractTest' if private else 'Issue130SyntheticContractTest',*ids]
  with log.open('w') as stream:r=subprocess.run(cmd,cwd=scratch,env=env,stdout=stream,stderr=subprocess.STDOUT,timeout=60,preexec_fn=lambda:os.umask(0o022))
  rows=[]
  for line in log.read_text().splitlines():
   try:rows.append(json.loads(line))
   except ValueError:pass
  if not rows:raise RuntimeError('NO_CONTRACT_RESULT')
  return r.returncode,rows[-1]
 def run_python(log):
  childenv=dict(env,ISSUE130_TEST_CLASSPATH=runtime,ISSUE130_TEST_SOURCE_ROOT=str(scratch),ISSUE130_TEST_JAVA=java)
  with log.open('w') as stream:r=subprocess.run(['python3',str(scratch/'evaluation/issue130/tests/test_representative_decisions.py'),'RunnerContract.test_CLI24_startup_crosses_deadline_kills_created_child'],cwd=scratch,env=childenv,stdout=stream,stderr=subprocess.STDOUT,timeout=30)
  text=log.read_text();return r.returncode,{'tests':1,'assertionFailures':1 if 'FAILED (failures=1)' in text else 0,'errors':1 if 'ERROR:' in text else 0,'passed':1 if '\nOK\n' in text else 0}
 cases=[
 ('root_seal',helper,'require(planSha.equals(environment.get("ISSUE130_FROZEN_PLAN_SHA256")) && planSha.equals(environment.get("ISSUE130_EXECUTE_FROZEN")), "CONFIGURATION");','require(true, "CONFIGURATION");',['L01_missing_root_seal_outbound0','L02_wrong_root_seal_outbound0'],False),
 ('output_binding',helper,'require(frozenOutput.equals(output), "CONFIGURATION");','require(true, "CONFIGURATION");',['L21_alternate_output_directory_rejected'],False),
 ('declared_whole_wire_bytes',helper,'require(bytes.length == integer(record.path("request_bytes")) && sha(bytes).equals(sha), "CONFIGURATION");','require(sha(bytes).equals(sha), "CONFIGURATION");',['L30_wire_byte_count_mismatch_outbound0'],False),
 ('fresh_preopen',helper,'remainingIo(cutoff, deps.nanoTime());','',['L27_fresh_preopen_fence_after_validation'],False),
 ('technical_stop',helper,'if (!Set.of("CLEAR_STOP", "B_REQUIRED_NOT_RUN").contains(result.get("outcome"))) stopped = true;','if (false) stopped = true;',['R06_missing_usage_preserved_unknown_STOP'],False),
 ('startup_kill',python,'except (subprocess.TimeoutExpired, EvaluationFailure):','except subprocess.TimeoutExpired:',[],False),
 ('atomic_private_creation',helper,None,None,[],True),
 ]
 receipts=[]
 for name,path,old,new,ids,private in cases:
  source=originals[path]
  if name=='atomic_private_creation':
   line=next(v for v in source.splitlines() if 'private static void writeNew(' in v)
   mutation='    private static void writeNew(Path path,byte[] raw) throws IOException { canonical(path); try (OutputStream out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) { Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------")); out.write(raw); } }'
   mutated=source.replace(line,mutation)
  else:
   assert old in source,name
   if name!='fresh_preopen':assert source.count(old)==1,(name,source.count(old))
   mutated=source.replace(old,new)
  folder=out/name;folder.mkdir();path.write_text(mutated);(folder/'mutation.diff.txt').write_text('BOUNDARY '+name+'\n'+(old or 'atomic600 open')+'\n=>\n'+(new or 'legacy open/chmod')+'\n')
  try:
   if path==helper:compile_helper()
   code,result=run_python(folder/'mutant.log') if path==python else run_java(ids,folder/'mutant.log',private)
   assert code==1 and result.get('assertionFailures',0)>0 and result.get('errors',0)==0,(name,code,result)
  finally:
   path.write_text(source)
   if path==helper:compile_helper()
  restored_code,restored=run_python(folder/'restored.log') if path==python else run_java(ids,folder/'restored.log',private)
  assert restored_code==0 and restored.get('errors',0)==0 and restored.get('assertionFailures',0)==0,(name,restored_code,restored)
  assert path.read_text()==source
  receipts.append({'boundary':name,'mutant':result,'restored':restored,'restoreExact':True})
  (out/'progress.json').write_text(json.dumps(receipts,indent=2)+'\n')
 assert inventory()==original,'ORIGINAL_SOURCE_DRIFT'
 result={'status':'PASS','mutations':len(receipts),'receipts':receipts,'originalSourceAndTestUnchanged':True,'originalFingerprints':original,'paidCalls':0,'sealedDataRead':False,'scope':'isolated snapshot; canonical TLS mocks/subprocess mock only'}
 (out/'receipt.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'status':'PASS','mutations':len(receipts),'originalSourceAndTestUnchanged':True,'paidCalls':0}))
