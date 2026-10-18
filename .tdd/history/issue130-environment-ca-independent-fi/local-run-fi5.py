import pathlib,subprocess,json,os,fcntl,hashlib,difflib,datetime,re
repo=pathlib.Path('/workspace/issue123-representative-evaluation/server');raw=repo/'.codex/tdd-evidence/issue130-environment-ca-independent-fi';raw.mkdir(parents=True,exist_ok=True)
cp=(repo/'.codex/tdd-evidence/issue130-environment-ca-current-green/compile/evaluation-runtime.classpath').read_text().strip()
java='/tmp/meetme-jdk17/bin/java';javac='/tmp/meetme-jdk17/bin/javac'
env=dict(os.environ,OPENAI_API_KEY='',GEMINI_API_KEY='');env.pop('NODE_EXTRA_CA_CERTS',None)
sha=lambda p:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def inventory():return {str(p.relative_to(repo)):sha(p) for p in sorted((repo/'evaluation/issue130').rglob('*')) if p.is_file() and '__pycache__' not in p.parts}
def run(name,cls='Issue130EnvironmentCaContractTest',ids=None,prefix=None):
 d=raw/name;d.mkdir(exist_ok=True);cmd=[java,f'-Dissue130.sourceRoot={repo}','-cp',(str(prefix)+os.pathsep if prefix else '')+cp,cls,*(ids or [])]
 with (d/'run.log').open('w') as f:r=subprocess.run(cmd,cwd=repo,env=env,stdout=f,stderr=subprocess.STDOUT,timeout=90)
 rows=[]
 for line in (d/'run.log').read_text().splitlines():
  try:rows.append(json.loads(line))
  except ValueError:pass
 value={'exitCode':r.returncode,'counts':rows[-1] if rows else None,'failedTests':[v['test'] for v in rows if v.get('status')=='ASSERTION_FAILURE'],'commandClass':cls,'selectedIds':ids or []};(d/'receipt.json').write_text(json.dumps(value,indent=2)+'\n');return value
with open('/tmp/meetme-gradle.lock','a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 before=inventory();(raw/'source-before.json').write_text(json.dumps(before,indent=2)+'\n')
 helper=repo/'evaluation/issue130/Issue130RepresentativeDecisionsCli.java';current=helper.read_text();helperSha=sha(helper)
 assert helperSha=='c5b07a6fe7637591a272db6d270faf459e2d93a64025e08b365938777a05c7c2'
 cases=[
 ('FCA1','Remove exact PEM byte pin','require(raw.length > 0 && sha(raw).equals(pemPin), "CONFIGURATION");','require(raw.length > 0, "CONFIGURATION");','CA13_pem_pin_mismatch'),
 ('FCA2','Ignore certificate current validity','            certificate.checkValidity();','            // FI: certificate validity omitted','CA25_invalid_certificate_expired'),
 ('FCA3','Replace default hostname verification with permissive verifier','        builder.sslSocketFactory(context.getSocketFactory(), trust);','        builder.sslSocketFactory(context.getSocketFactory(), trust);\n        builder.hostnameVerifier((host, session) -> true);','CA28_same_CA_wrong_hostname_reject'),
 ('FCA4','Remove fixed provider origin confinement','            requireIo(url.scheme().equals("https") && url.host().equals("api.openai.com") && url.port() == 443, "CONFIGURATION");','            // FI: origin confinement omitted','CA30_origin_confinement_40'),
 ('FCA5','Drop all existing JDK trust anchors','        for (X509Certificate certificate : trustManager(null).getAcceptedIssuers()) anchors.setCertificateEntry(sha(certificate.getEncoded()), certificate);','        // FI: JDK trust anchor preservation omitted','CA01_valid_client_adds_one_CA_retains_JDK_anchors')]
 results=[]
 for tag,fault,old,new,target in cases:
  assert current.count(old)==1,tag
  d=pathlib.Path('/tmp/issue130-ca-tests')/tag;d.mkdir(exist_ok=True);source=d/'Issue130RepresentativeDecisionsCli.java';classes=d/'classes';classes.mkdir(exist_ok=True)
  mutant=current.replace(old,new);(raw/(tag+'.patch')).write_text(''.join(difflib.unified_diff(current.splitlines(True),mutant.splitlines(True),fromfile='baseline/evaluation/issue130/Issue130RepresentativeDecisionsCli.java',tofile='mutant/evaluation/issue130/Issue130RepresentativeDecisionsCli.java')))
  def compile_text(text,phase):
   source.write_text(text)
   with (raw/(tag+'-'+phase+'-compile.log')).open('w') as f:r=subprocess.run([javac,'--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(source)],cwd=repo,env=env,stdout=f,stderr=subprocess.STDOUT,timeout=45)
   assert r.returncode==0,(tag,phase)
  compile_text(mutant,'mutant');fail=run(tag+'-mutant',ids=[target],prefix=classes)
  assert fail['counts']['assertionFailures']==1 and fail['counts']['errors']==0,(tag,fail)
  compile_text(current,'restored');restore=run(tag+'-restored',ids=[target],prefix=classes)
  assert restore['exitCode']==0 and restore['counts']['passed']==1 and sha(source)==helperSha,(tag,restore)
  results.append({'tag':tag,'fault':fault,'detectedBy':fail['failedTests'][0],'mutant':fail,'restore':restore,'exactSourceRestored':True})
  print(json.dumps({'phase':tag,'detected':True,'exactRestoreGreen':True}),flush=True)
 finalJava=run('final-java45');assert finalJava['exitCode']==0 and finalJava['counts']['passed']==45
 finalSupplement=run('final-supplement3',cls='Issue130EnvironmentCaRunPlanSupplementTest');assert finalSupplement['exitCode']==0 and finalSupplement['counts']['passed']==3
 penv=dict(env,ISSUE130_TEST_SOURCE_ROOT=str(repo),ISSUE130_TEST_JAVA=java,ISSUE130_TEST_CLASSPATH=cp)
 with (raw/'final-python18.log').open('w') as f:pr=subprocess.run(['python3','evaluation/issue130/tests/test_environment_ca.py'],cwd=repo,env=penv,stdout=f,stderr=subprocess.STDOUT,timeout=90)
 assert pr.returncode==0
 text=(raw/'final-python18.log').read_text();assert re.search(r'Ran 18 tests',text)
 after=inventory();assert before==after;(raw/'source-after.json').write_text(json.dumps(after,indent=2)+'\n')
 prior=json.loads((repo/'.codex/tdd-evidence/issue130-environment-ca-supplement-history/receipt.json').read_text())
 receipt={'status':'CA_FI6_DETECTED_EXACT_RESTORED_FINAL66_GREEN','newCAFaultCount':6,'priorEnvironmentProxyFaultCountSeparate':8,'fiveFactoryFaults':results,'sixthRunPlanCryptoFault':{'rawArchive':'.codex/tdd-evidence/issue130-environment-ca-supplement-history','assertionFailures':prior['fault']['counts']['assertionFailures'],'restoredPassed':prior['final']['counts']['passed']},'currentHelperSha256':helperSha,'pythonHelperSha256':sha(repo/'evaluation/issue130/run_representative_decisions.py'),'final':{'java':45,'python':18,'supplement':3,'total':66,'failures':0,'errors':0,'skips':0},'sourceBeforeAfterIdentical':True,'allScratchVariantsExactRestored':True,'workspaceProductEdits':0,'actualProviderCalls':0,'sealedDataRead':False,'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z')}
 (raw/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps({'status':receipt['status'],'newFaults':6,'finalTests':66,'sourceIdentical':True}),flush=True)
