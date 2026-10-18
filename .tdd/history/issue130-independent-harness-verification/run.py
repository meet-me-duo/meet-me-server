from pathlib import Path
import fcntl,hashlib,json,os,shutil,subprocess,time
repo=Path('/workspace/issue123-representative-evaluation/server');out=Path('/tmp/issue130-independent-tests/whole-cli');runtime=Path('/tmp/issue130-independent-tests/green-102/base/evaluation-runtime.classpath').read_text().strip();classes=out/'classes';classes.mkdir(exist_ok=True)
java='/tmp/meetme-jdk17/bin/java';javac='/tmp/meetme-jdk17/bin/javac';env=os.environ.copy();env.update(OPENAI_API_KEY='',GEMINI_API_KEY='')
original={str(p.relative_to(repo)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (repo/'evaluation/issue130').rglob('*') if p.is_file() and p.suffix in ('.java','.py')}
with open('/tmp/meetme-gradle.lock','a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 subprocess.run([javac,'--release','17','-cp',runtime,'-d',str(classes),str(out/'Issue130CliProxyControl.java')],env=env,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 boot=out/'boot-guard';boot.mkdir(exist_ok=True);shutil.copy2(Path('/tmp/issue130-independent-tests/green-102/base/classes/Issue130SyntheticNetworkGuard.class'),boot/'Issue130SyntheticNetworkGuard.class')
 exported=out/'synthetic-fixture.json'
 subprocess.run([java,f'-Dissue130.sourceRoot={repo}','-cp',runtime,'Issue130SyntheticContractTest','--export-fixture',str(exported)],env=env,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 fixture=json.loads(exported.read_text());plan=Path(fixture['plan']);config=json.loads(plan.read_text());cpfile=next(Path(p) for p in config['runtimeFiles'] if p.endswith('/runtime.classpath'))
 proxyroot=out/'proxy';proxyroot.mkdir(exist_ok=True)
 with (out/'proxy.log').open('w') as proxylog:
  proxy=subprocess.Popen([java,f'-Dissue130.sourceRoot={repo}','-cp',str(classes)+os.pathsep+runtime,'Issue130CliProxyControl',str(proxyroot)],env=env,stdout=proxylog,stderr=subprocess.STDOUT)
  try:
   for i in range(100):
    if (proxyroot/'proxy-ready.json').exists():break
    if proxy.poll() is not None:raise RuntimeError('SYNTHETIC_PROXY_STARTUP_FAILED')
    time.sleep(.1)
   info=json.loads((proxyroot/'proxy-ready.json').read_text())
   digest=hashlib.sha256(plan.read_bytes()).hexdigest();realenv=dict(env,OPENAI_API_KEY='synthetic-issue130-key-MUST-NOT-LEAK',ISSUE130_FROZEN_PLAN_SHA256=digest,ISSUE130_EXECUTE_FROZEN=digest)
   realenv['JAVA_TOOL_OPTIONS']=f'-Xbootclasspath/a:{boot} -Djava.security.manager=Issue130SyntheticNetworkGuard -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort={info["port"]} -Djavax.net.ssl.trustStore={info["trustStore"]} -Djavax.net.ssl.trustStorePassword=synthetic-only-passphrase'
   command=['python3',str(repo/'evaluation/issue130/run_representative_decisions.py'),'--plan',str(plan),'--index',fixture['index'],'--output',fixture['output'],'--classpath-file',str(cpfile),'--execute-frozen']
   started=time.monotonic();first=subprocess.run(command,cwd=repo,env=realenv,capture_output=True,text=True,timeout=60);elapsed=time.monotonic()-started
   (out/'execute.stdout').write_text(first.stdout);(out/'execute.stderr').write_text(first.stderr)
   report=json.loads(first.stdout);assert first.returncode==0 and report.get('status')=='COMPLETED',(first.returncode,report)
   replay=subprocess.run(command,cwd=repo,env=realenv,capture_output=True,text=True,timeout=30);(out/'replay.stdout').write_text(replay.stdout);assert replay.returncode!=0
   dry=subprocess.run(['python3',str(repo/'evaluation/issue130/run_representative_decisions.py')],cwd=repo,env=env,capture_output=True,text=True,timeout=10);dryreport=json.loads(dry.stdout);assert dry.returncode==0 and not dryreport['executionPermitted']
  finally:
   (proxyroot/'stop').touch();proxy.wait(timeout=10)
 proof=json.loads((proxyroot/'proxy-result.json').read_text());assert proof['requests']==16 and proof['connections']==16 and proof['maximumActive']==1 and proof['fixtureFailures']==[],proof
 after={str(p.relative_to(repo)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (repo/'evaluation/issue130').rglob('*') if p.is_file() and p.suffix in ('.java','.py')};assert original==after
 result={'status':'PASS','scope':'actual Python CLI -> javac -> actual child Java -> production canonical classifier/parser/TwoStage -> localTLSsynthetic16','requests':proof['requests'],'maximumConcurrent':proof['maximumActive'],'replayExtraRequests':0,'defaultDryRun':dryreport,'wrapperSeconds':round(elapsed,3),'sourceBeforeAfterEqual':original==after,'actualProviderCalls':0,'externalNetworkCalls':0,'paidCalls':0,'sealedDataRead':False,'bootstrapExternalNetworkTripwire':True}
 (out/'receipt.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
