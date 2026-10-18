#!/usr/bin/env python3
"""Independent decimal wire GREEN/FI; public synthetic cached fixtures, no Gradle/private/model/network."""
import argparse,datetime,difflib,fcntl,hashlib,json,os,re,shutil,subprocess,sys,time
from pathlib import Path
args=argparse.ArgumentParser();args.add_argument('--source-root',required=True);args.add_argument('--output',required=True);args=args.parse_args()
ROOT=Path(args.source_root).resolve();OUT=Path(args.output).resolve();OUT.mkdir(parents=True,exist_ok=True);OUT.chmod(0o700)
REL='evaluation/issue130/tests/test_cost_decimal_wire_contract.py';WRAPPERS=['evaluation/issue130/run_representative_decisions.py','evaluation/issue130/run_standalone_generation.py']
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def inventory():return {str(p.relative_to(ROOT)):sha(p) for d in [ROOT/'src/main',ROOT/'evaluation/issue130'] for p in sorted(d.rglob('*')) if p.is_file() and p.suffix in ('.kt','.java','.py') and '__pycache__' not in p.parts}
A_CP=(ROOT/'.codex/tdd-evidence/issue130-candidate-a24-green/evaluation-runtime.classpath').read_text().strip();B_CP=(ROOT/'.codex/tdd-evidence/issue130-standalone-generation-current-green/evaluation-runtime.classpath').read_text().strip()
ENV=dict(os.environ,OPENAI_API_KEY='',GEMINI_API_KEY='',ISSUE130_TEST_JAVA='/tmp/meetme-jdk17/bin/java',ISSUE130_DECIMAL_A24_CLASSPATH=A_CP,ISSUE130_DECIMAL_B2_CLASSPATH=B_CP)
for key in ('NODE_EXTRA_CA_CERTS','https_proxy','HTTPS_PROXY','http_proxy','HTTP_PROXY','ALL_PROXY','all_proxy'):ENV.pop(key,None)
def run(root,folder):
    folder.mkdir(parents=True,exist_ok=True);env=dict(ENV,ISSUE130_TEST_SOURCE_ROOT=str(root));command=[sys.executable,str(root/REL)]
    with (folder/'python.log').open('w') as log:result=subprocess.run(command,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=90)
    text=(folder/'python.log').read_text();match=re.search(r'Ran (\d+) tests?',text);assert match,'FIXTURE_SETUP_FAILURE'
    failed=re.findall(r'^FAIL: (test_\w+)',text,re.M);errors=re.findall(r'^ERROR: (test_\w+)',text,re.M)
    record={'command':command,'exitCode':result.returncode,'tests':int(match.group(1)),'assertionFailures':len(failed),'errors':len(errors),'detectedBy':failed,'passed':int(match.group(1))-len(failed)-len(errors)}
    (folder/'receipt.json').write_text(json.dumps(record,indent=2)+'\n');return record
started=time.monotonic()
with open('/tmp/meetme-gradle.lock','a') as lock:
    fcntl.flock(lock,fcntl.LOCK_EX);before=inventory();(OUT/'original-before.json').write_text(json.dumps(before,indent=2)+'\n')
    green=run(ROOT,OUT/'initial-green');assert green['exitCode']==0 and green['tests']==56 and not green['errors']
    sources={p:sha(ROOT/p) for p in WRAPPERS};test={REL:sha(ROOT/REL)}
    assert test[REL]=='b24ab6d8987c3106c7edfd23a44a679f34e50ef36f7e3cd94490939ec43ac1e7'
    assert inventory()==before
OVERLAY=OUT/'overlay';OVERLAY.mkdir(exist_ok=True)
dependencies=[
    'evaluation/issue130/Issue130RepresentativeDecisionsCli.java','evaluation/issue130/Issue130StandaloneGenerationCli.java',
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassificationContract.kt',
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/OpenAiAmbiguityClassifier.kt',
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassifierOptions.kt',
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/TwoStagePreSubmissionCheckProvider.kt',
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderContract.kt',
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/GeminiPreSubmissionCheckProvider.kt',
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderRuntimeProperties.kt',
    'src/main/kotlin/com/meetme/server/precheck/application/port/output/PreSubmissionCheckProvider.kt']
selected=WRAPPERS+[REL]+dependencies
for rel in selected:
    target=OVERLAY/rel;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/rel,target)
shutil.copyfile(ROOT/'.git',OVERLAY/'.git');baseline={p:sha(OVERLAY/p) for p in selected}
assert run(OVERLAY,OUT/'overlay-control')['exitCode']==0
injections=[]
for index,rel in enumerate(WRAPPERS,1):
    path=OVERLAY/rel;original=path.read_text();start=original.index('def amount(');end=original.index('\ndef ',start+1);body=original[start:end]
    guards=[line for line in body.splitlines(True) if 're.fullmatch' in line and '[0-9]' in line and '1,8' in line]
    assert len(guards)==1,('LEXICAL_GUARD_MISSING_OR_AMBIGUOUS',rel)
    guard=guards[0];changed=original.replace(guard,'    # FI: Java-equivalent lexical guard removed; Decimal arithmetic unchanged.\n',1)
    folder=OUT/('F0'+str(index));folder.mkdir(exist_ok=True);(folder/'exact.patch').write_text(''.join(difflib.unified_diff(original.splitlines(True),changed.splitlines(True),fromfile='a/'+rel,tofile='b/'+rel)))
    mutant=None;restored=None
    try:
        path.write_text(changed);mutant=run(OVERLAY,folder/'mutant');assert mutant['exitCode']!=0 and mutant['tests']==56 and mutant['assertionFailures']==22 and not mutant['errors'],('SURVIVED_OR_SETUP',mutant)
    finally:
        path.write_text(original);restored=run(OVERLAY,folder/'restored');assert restored['exitCode']==0 and restored['passed']==56 and not restored['errors']
        assert {p:sha(OVERLAY/p) for p in selected}==baseline;assert inventory()==before,'ORIGINAL_DRIFT'
    record={'fault':'remove_'+('A24' if index==1 else 'B2')+'_amount_lexical_guard','source':rel,'mutantSha256':hashlib.sha256(changed.encode()).hexdigest(),'patchSha256':sha(folder/'exact.patch'),'mutant':mutant,'restored':restored,'overlayExactRestore':True,'originalUnchanged':True};injections.append(record);(folder/'receipt.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps({'fault':record['fault'],'detectedAssertions':mutant['assertionFailures'],'restorePassed':restored['passed']}),flush=True)
with open('/tmp/meetme-gradle.lock','a') as lock:
    fcntl.flock(lock,fcntl.LOCK_EX);final=run(ROOT,OUT/'final-green');assert final['exitCode']==0 and final['passed']==56 and not final['errors'];after=inventory();assert before==after;(OUT/'original-after.json').write_text(json.dumps(after,indent=2)+'\n')
receipt={'status':'PASS_INDEPENDENT_LEXICAL_REGRESSION_AND_FI_NOT_EXECUTION_APPROVAL','sourceRevision':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'sourceFiles':sources,'testFiles':test,'initialGreen':green,'injections':injections,'finalGreen':final,'originalSourceBeforeAfterEqual':True,'inventoryScope':'src/main Kotlin + evaluation/issue130 Java/Python; root full-tree mandatory gate separate','sourceInventoryFiles':len(before),'overlayExactRestore':True,'actualProviderCalls':0,'externalNetworkCalls':0,'privateDataRead':False,'durationSeconds':round(time.monotonic()-started,3),'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z')};(OUT/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps({'status':receipt['status'],'initialGreen56':True,'FI2assertions44':True,'finalGreen56':True,'originalUnchanged':True}),flush=True)
