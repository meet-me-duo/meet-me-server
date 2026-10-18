import pathlib,subprocess,json,os,fcntl,hashlib,difflib,datetime
repo=pathlib.Path('/workspace/issue123-representative-evaluation/server')
raw=repo/'.codex/tdd-evidence/issue130-environment-ca-supplement-history';raw.mkdir(parents=True,exist_ok=True)
initial=repo/'.codex/tdd-evidence/issue130-environment-ca-supplement-initial'
cp=(initial/'compile/evaluation-runtime.classpath').read_text().strip()
java='/tmp/meetme-jdk17/bin/java';javac='/tmp/meetme-jdk17/bin/javac'
env=dict(os.environ,OPENAI_API_KEY='',GEMINI_API_KEY='');env.pop('NODE_EXTRA_CA_CERTS',None)
sha=lambda p:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def inventory():return {str(p.relative_to(repo)):sha(p) for p in sorted((repo/'evaluation/issue130').rglob('*')) if p.is_file() and '__pycache__' not in p.parts}
def run(name,prefix=None):
 d=raw/name;d.mkdir(exist_ok=True)
 cmd=[java,f'-Dissue130.sourceRoot={repo}','-cp',(str(prefix)+os.pathsep if prefix else '')+cp,'Issue130EnvironmentCaRunPlanSupplementTest']
 with (d/'run.log').open('w') as f:r=subprocess.run(cmd,cwd=repo,env=env,stdout=f,stderr=subprocess.STDOUT,timeout=90)
 rows=[]
 for line in (d/'run.log').read_text().splitlines():
  try:rows.append(json.loads(line))
  except ValueError:pass
 value={'command':cmd,'exitCode':r.returncode,'counts':rows[-1] if rows else None,'assertionTests':[v['test'] for v in rows if v.get('status')=='ASSERTION_FAILURE']};(d/'receipt.json').write_text(json.dumps(value,indent=2)+'\n');return value
def compile_overlay(name,source):
 d=pathlib.Path('/tmp/issue130-ca-tests')/name;d.mkdir(exist_ok=True);p=d/'Issue130RepresentativeDecisionsCli.java';p.write_bytes(source);classes=d/'classes';classes.mkdir(exist_ok=True)
 with (raw/(name+'-compile.log')).open('w') as f:r=subprocess.run([javac,'--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(p)],cwd=repo,env=env,stdout=f,stderr=subprocess.STDOUT,timeout=45)
 assert r.returncode==0,name;return classes
with open('/tmp/meetme-gradle.lock','a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 before=inventory();(raw/'source-before.json').write_text(json.dumps(before,indent=2)+'\n')
 helper=repo/'evaluation/issue130/Issue130RepresentativeDecisionsCli.java';current=helper.read_bytes();old=pathlib.Path('/tmp/issue130-ca-implementation/before.java').read_bytes()
 assert hashlib.sha256(old).hexdigest()=='c2439ff232fa2eda252c903ebd4cda00f51685c0b1aeca48a11bcce515c5ffdc'
 reconstructed=run('reconstructed-preimplementation-red3',compile_overlay('pre-ca-overlay',old))
 assert reconstructed['counts']['assertionFailures']==3 and reconstructed['counts']['errors']==0
 restored=run('current-restored-green3');assert restored['exitCode']==0 and restored['counts']['passed']==3
 needle=b'        environmentCertificate(p, environment);';assert current.count(needle)==1
 mutant=current.replace(needle,b'        // FI: injected-client run plan skips environment CA cryptographic validation')
 (raw/'crypto-entry-bypass.patch').write_text(''.join(difflib.unified_diff(current.decode().splitlines(True),mutant.decode().splitlines(True),fromfile='baseline/evaluation/issue130/Issue130RepresentativeDecisionsCli.java',tofile='mutant/evaluation/issue130/Issue130RepresentativeDecisionsCli.java')))
 fault=run('runplan-crypto-bypass-mutant3',compile_overlay('crypto-bypass-overlay',mutant))
 assert fault['counts']['assertionFailures']==3 and fault['counts']['errors']==0
 final=run('final-current-green3');assert final['exitCode']==0 and final['counts']['passed']==3
 after=inventory();assert before==after;(raw/'source-after.json').write_text(json.dumps(after,indent=2)+'\n')
 receipt={'status':'INITIAL_PASS3_RECONSTRUCTED_RED3_CURRENT_GREEN3_FI_DETECTED3_FINAL_GREEN3','chronology':'Supplement authored after Java CA implementation was placed. Initial current implementation PASS; exact preserved pre-CA helper replay is post-hoc RECONSTRUCTED_PREIMPLEMENTATION_RED, not chronological RED.','currentHelperSha256':hashlib.sha256(current).hexdigest(),'preCAHelperSha256':hashlib.sha256(old).hexdigest(),'testSha256':sha(repo/'evaluation/issue130/tests/Issue130EnvironmentCaRunPlanSupplementTest.java'),'runnerSha256':sha(repo/'evaluation/issue130/tests/run_environment_ca_supplement_tests.py'),'reconstructed':reconstructed,'restored':restored,'fault':fault,'final':final,'originalBeforeAfterIdentical':True,'workspaceProductEdits':0,'originalCA63FilesUnchanged':all(sha(repo/p)==s for p,s in json.loads((repo/'.tdd/verification/issue130-environment-ca-red.json').read_text())['testFingerprints'].items()),'actualProviderCalls':0,'sealedDataRead':False,'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z')}
 (raw/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt),flush=True)
