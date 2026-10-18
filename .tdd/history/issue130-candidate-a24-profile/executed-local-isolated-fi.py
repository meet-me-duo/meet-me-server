import argparse,datetime,difflib,fcntl,hashlib,json,os,subprocess,sys,tempfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--runtime-classpath',required=True);p.add_argument('--output',required=True);args=p.parse_args()
repo=Path('/workspace/issue123-representative-evaluation/server');out=Path(args.output);out.mkdir(parents=True,exist_ok=True)
java=Path('/tmp/meetme-jdk17/bin/java');javac=java.with_name('javac');cp=Path(args.runtime_classpath).read_text().strip()
env=dict(os.environ,OPENAI_API_KEY='',GEMINI_API_KEY='',ISSUE130_TEST_SOURCE_ROOT=str(repo),ISSUE130_TEST_JAVA=str(java),ISSUE130_TEST_CLASSPATH=cp);env.pop('NODE_EXTRA_CA_CERTS',None)
jpath=repo/'evaluation/issue130/Issue130RepresentativeDecisionsCli.java';ppath=repo/'evaluation/issue130/run_representative_decisions.py'
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
def inv():return {str(p.relative_to(repo)):sha(p) for directory in [repo/'src/main',repo/'evaluation/issue130'] for p in sorted(directory.rglob('*')) if p.is_file() and p.suffix in ('.java','.kt','.py')}
def execute(command,name):
    with (out/(name+'.log')).open('w') as f:r=subprocess.run(command,cwd=repo,env=env,stdout=f,stderr=subprocess.STDOUT,timeout=160)
    return r.returncode
jcommand=[str(java),f'-Dissue130.sourceRoot={repo}','-cp',cp,'Issue130CandidateA24ContractTest']
pcommand=[sys.executable,str(repo/'evaluation/issue130/tests/test_candidate_a24.py')]
def replace_exact(source,old,new,count=1):
    assert source.count(old)==count,(old,source.count(old));return source.replace(old,new)
def java_variant(source,old,new):return replace_exact(source,old,new)
def python_variant(source,kind):
    if kind=='deadline':return replace_exact(source,"deadline = started + plan['suiteSeconds']","deadline = time.monotonic() + plan['suiteSeconds']")
    if kind=='money':
        for line in ["        need(all(amount(cost, key) == Decimal(value) for key, value in expected.items()))\n","        need(amount(cost, 'perCallReservedUsd') * count == amount(cost, 'maximumNewReservedUsd'))\n","        need(amount(cost, 'priorHeldUsd') + amount(cost, 'maximumNewReservedUsd') == amount(cost, 'maximumCumulativeReservedUsd') <= amount(cost, 'approvedAggregateUsd'))\n"]:source=replace_exact(source,line,'')
        return source
    if kind=='profile':return replace_exact(source,"            need(plan['evaluationProfile'] == 'CANDIDATE_A24_USD3_V1' and plan.get('transportMode') == 'ENVIRONMENT_HTTPS_PROXY' and isinstance(plan.get('environmentCa'), dict))","            need(plan.get('transportMode') == 'ENVIRONMENT_HTTPS_PROXY' and isinstance(plan.get('environmentCa'), dict))")
    raise AssertionError(kind)
with open('/tmp/meetme-gradle.lock','a') as lock:
    fcntl.flock(lock,fcntl.LOCK_EX);before=inv();(out/'source-before.json').write_text(json.dumps(before,indent=2)+'\n')
    assert execute(jcommand,'baseline-java')==0;assert execute(pcommand,'baseline-python')==0
    js=jpath.read_text();ps=ppath.read_text()
    faults=[
      ('F1_JAVA_TRUNCATE24_TO16','java',java_variant(js,'for (int i = 0; i < caseCount(setup.plan()); i++)','for (int i = 0; i < 16; i++)'),'A24R01_actual24_serial_routes_fullhold_replay0'),
      ('F2_JAVA_PREP_DUPLICATE_ALLOWED','java',java_variant(js,'require(ids.add(id), "INVALID_REQUEST");','ids.add(id);'),'A24P04_duplicate_rejected'),
      ('F3_JAVA_A24_CA_OPTIONAL','java',replace_exact(js,' && plan.path("environmentCa").isObject()',''),'A24D_missing_CA'),
      ('F4_PYTHON_FRESH_WHOLE_DEADLINE','python',python_variant(ps,'deadline'),'test_A24PY12_350second_validation_has70left_no_reset'),
      ('F5_PYTHON_MONEY_GUARDS_BYPASS','python',python_variant(ps,'money'),'test_A24PY06_old_hold_and_cap_rejected'),
      ('F6_PYTHON_UNKNOWN_PROFILE_ALLOWED','python',python_variant(ps,'profile'),'test_A24PY03_exact_profile_unknown_type_or_value')]
    results=[]
    for label,language,mutant,test in faults:
        variant=out/label;variant.mkdir();original=js if language=='java' else ps
        (variant/'mutation.patch').write_text(''.join(difflib.unified_diff(original.splitlines(True),mutant.splitlines(True),fromfile=str(jpath if language=='java' else ppath),tofile='isolated-mutant')))
        if language=='java':
            target=variant/'Issue130RepresentativeDecisionsCli.java';target.write_text(mutant);classes=variant/'classes';classes.mkdir()
            assert execute([str(javac),'--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(target)],label+'-compile')==0
            cmd=[str(java),f'-Dissue130.sourceRoot={repo}','-cp',str(classes)+os.pathsep+cp,'Issue130CandidateA24ContractTest',test]
            failed=execute(cmd,label+'-fault');log=(out/(label+'-fault.log')).read_text();assert failed!=0 and '"ASSERTION_FAILURE"' in log and '"errors":0' in log,(label,log[-1000:])
            restored=execute(jcommand+[test],label+'-restore');assert restored==0
        else:
            target=variant/'runner.py';target.write_text(mutant)
            launch=variant/'execute.py';launch.write_text("import importlib.util,unittest\nfrom pathlib import Path\ndef load(name,path):\n s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m\nt=load('contract',"+repr(str(repo/'evaluation/issue130/tests/test_candidate_a24.py'))+")\nt.RUNNER=load('mutant',"+repr(str(target))+")\nr=unittest.TextTestRunner(verbosity=2).run(unittest.TestSuite([t.CandidateA24Contract("+repr(test)+")]))\nraise SystemExit(0 if r.wasSuccessful() else 1)\n")
            failed=execute([sys.executable,str(launch)],label+'-fault');log=(out/(label+'-fault.log')).read_text();assert failed!=0 and 'AssertionError:' in log and '\nERROR:' not in log,(label,log[-1000:])
            restored=execute(pcommand+['CandidateA24Contract.'+test],label+'-restore');assert restored==0
        results.append({'fault':label,'language':language,'detectedBy':test,'faultExitCode':failed,'restoredExitCode':restored,'patchSha256':sha(variant/'mutation.patch'),'result':'DETECTED'})
    assert execute(jcommand,'final-java')==0;assert execute(pcommand,'final-python')==0
    after=inv();assert before==after
    (out/'source-after.json').write_text(json.dumps(after,indent=2)+'\n')
    receipt={'status':'BASELINE41_GREEN_SIX_FAULTS_DETECTED_RESTORED_FINAL41_GREEN','injections':results,'sourceBeforeAfterEqual':True,'originalHelperEdited':False,'freshHoldoutRead':False,'actualModelCalls':0,'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z')}
    (out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
