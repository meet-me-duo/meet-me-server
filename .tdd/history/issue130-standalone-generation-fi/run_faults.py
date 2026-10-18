#!/usr/bin/env python3
"""Independent B-only FI: frozen public synthetic tests in an isolated source overlay, no Gradle or paid calls."""
import argparse
import datetime
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

parser=argparse.ArgumentParser();parser.add_argument('--source-root',required=True);parser.add_argument('--output',required=True);args=parser.parse_args()
SOURCE=Path(args.source_root).resolve();OUT=Path(args.output).resolve();OUT.mkdir(parents=True,exist_ok=True);OUT.chmod(0o700)
OVERLAY=OUT/'overlay';OVERLAY.mkdir(exist_ok=True)
JAVA_REL='evaluation/issue130/Issue130StandaloneGenerationCli.java';PY_REL='evaluation/issue130/run_standalone_generation.py';TEST_REL='evaluation/issue130/standalone_tests'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def inventory(root):
    files=list((root/'src/main').rglob('*.kt'))+list((root/'evaluation/issue130').rglob('*.java'))+list((root/'evaluation/issue130').rglob('*.py'))
    return {str(p.relative_to(root)):sha(p) for p in sorted(files) if '__pycache__' not in p.parts}
before=inventory(SOURCE);(OUT/'original-before.json').write_text(json.dumps(before,indent=2)+'\n')
assert sha(SOURCE/JAVA_REL)=='49936292adad57cde36d10d4fd6a3776a22f9b7a0bf55da1b1865552fe442341'
assert sha(SOURCE/PY_REL)=='b00e7b82553adad77f9554c80e39dcf4e8f880fede21119b669c1afebf809108'
frozen=json.loads((SOURCE/'.tdd/verification/issue130-standalone-generation-red.json').read_text())
for p,digest in frozen['testFiles'].items():assert sha(SOURCE/p)==digest,p
selected=list(frozen['testFiles'])+[JAVA_REL,PY_REL,
 'src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderContract.kt',
 'src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/GeminiPreSubmissionCheckProvider.kt',
 'src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderRuntimeProperties.kt',
 'src/main/kotlin/com/meetme/server/precheck/application/port/output/PreSubmissionCheckProvider.kt']
for rel in selected:
    destination=OVERLAY/rel;destination.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(SOURCE/rel,destination)
shutil.copyfile(SOURCE/'.git',OVERLAY/'.git')
baseline={p:sha(OVERLAY/p) for p in selected};(OUT/'overlay-baseline.json').write_text(json.dumps(baseline,indent=2)+'\n')
JAVA_HOME=Path('/tmp/meetme-jdk17');JAVA=str(JAVA_HOME/'bin/java');JAVAC=str(JAVA_HOME/'bin/javac')
PRODUCTION_CP=(SOURCE/'.codex/tdd-evidence/issue130-standalone-generation-current-green/production-runtime.classpath').read_text().strip()
CLASSES=OUT/'classes';CLASSES.mkdir(exist_ok=True);CP=str(CLASSES)+os.pathsep+PRODUCTION_CP
ENV=dict(os.environ,OPENAI_API_KEY='',GEMINI_API_KEY='',JAVA_HOME=str(JAVA_HOME),ISSUE130_TEST_SOURCE_ROOT=str(OVERLAY),ISSUE130_TEST_JAVA=JAVA,ISSUE130_TEST_CLASSPATH=CP)
for key in ('NODE_EXTRA_CA_CERTS','https_proxy','HTTPS_PROXY','http_proxy','HTTP_PROXY','ALL_PROXY','all_proxy'):ENV.pop(key,None)
def logged(command,path,timeout=180):
    with path.open('w') as stream:return subprocess.run(command,cwd=OVERLAY,env=ENV,stdout=stream,stderr=subprocess.STDOUT,timeout=timeout).returncode
def compile_at(directory):
    directory.mkdir(parents=True,exist_ok=True)
    command=[JAVAC,'--release','17','-encoding','UTF-8','-cp',PRODUCTION_CP,'-d',str(CLASSES),str(OVERLAY/JAVA_REL),*map(str,sorted((OVERLAY/TEST_REL).glob('*.java')))]
    code=logged(command,directory/'compile.log',60)
    if code:raise RuntimeError('FI_COMPILE_SETUP_FAILURE')
def java_at(klass,names,directory):
    directory.mkdir(parents=True,exist_ok=True);command=[JAVA,f'-Dissue130.sourceRoot={OVERLAY}','-cp',CP,klass,*names];code=logged(command,directory/'java.jsonl')
    rows=[]
    for line in (directory/'java.jsonl').read_text().splitlines():
        try:rows.append(json.loads(line))
        except ValueError:pass
    summary=rows[-1];assert 'tests' in summary
    return {'command':command,'exitCode':code,'summary':summary,'detectedBy':[r.get('test',klass) for r in rows if r.get('status')=='ASSERTION_FAILURE'] or ([klass+'.PRIVATE_CREATION'] if summary.get('assertionFailures') else [])}
def python_at(names,directory):
    directory.mkdir(parents=True,exist_ok=True);command=[sys.executable,str(OVERLAY/TEST_REL/'test_standalone_generation.py'),*names];code=logged(command,directory/'python.log')
    text=(directory/'python.log').read_text();count=re.search(r'Ran (\d+) tests?',text);assert count
    failures=re.findall(r'^FAIL: (test_\w+)',text,re.M);errors=re.findall(r'^ERROR: (test_\w+)',text,re.M)
    return {'command':command,'exitCode':code,'summary':{'tests':int(count.group(1)),'assertionFailures':len(failures),'errors':len(errors),'passed':int(count.group(1))-len(set(failures))-len(set(errors))},'detectedBy':list(dict.fromkeys(failures))}
def run_target(target,directory):
    kind,klass,*names=target
    return java_at(klass,names,directory) if kind=='java' else python_at([klass,*names],directory)
def replace_once(text,old,new):
    assert text.count(old)==1,(old,text.count(old));return text.replace(old,new,1)
faults=[
 ('F01_CA_lifetime_validation_removed',JAVA_REL,lambda s:replace_once(s,'certificate.checkValidity();','/* FI: lifetime validation removed */'),('java','Issue130StandaloneClientTest','BT20_invalid_crypto_expired')),
 ('F02_hostname_verification_bypassed',JAVA_REL,lambda s:replace_once(s,'builder.sslSocketFactory(context.getSocketFactory(), trust);','builder.sslSocketFactory(context.getSocketFactory(), trust).hostnameVerifier((host, session)->true);'),('java','Issue130StandaloneClientTest','BT07_trusted_CA_wrong_SAN_HTTP_zero')),
 ('F03_fixed_origin_path_predicate_removed',JAVA_REL,lambda s:replace_once(s,'requireIo(r.method().equals("POST")&&u.scheme().equals("https")&&u.host().equals(HOST)&&u.port()==443&&uri.getRawUserInfo()==null&&uri.getRawQuery()==null&&uri.getRawFragment()==null&&(u.encodedPath().equals(COUNT)||u.encodedPath().equals(GENERATE)),"CONFIGURATION");','requireIo(true,"CONFIGURATION");'),('java','Issue130StandaloneClientTest','BT08_forbidden_URL_7')),
 ('F04_retained_hold_and_cost_validation_skipped',JAVA_REL,lambda s:replace_once(s,'environmentCertificate(p,env);cost(p);','environmentCertificate(p,env);/* FI: cost and old hold validation skipped */'),('java','Issue130StandaloneRuntimeTest','BF05_money_rollback_priorHeldUsd')),
 ('F05_replay_marker_and_CREATE_NEW_bypassed',JAVA_REL,lambda s:replace_once(replace_once(s,'require(!Files.exists(output.resolve(".suite-consumed.json"),LinkOption.NOFOLLOW_LINKS),"ALREADY_CONSUMED");','/* FI: consumed marker ignored */'),'Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)','Set.of(StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)'),('java','Issue130StandaloneRuntimeTest','BF01_replay_zero_extraHTTP')),
 ('F06_percase15_expanded_to_batch120',JAVA_REL,lambda s:replace_once(s,'Math.min(Duration.ofSeconds(15).toNanos(),remaining(suiteDeadline,deps.nanoTime()))','Math.min(Duration.ofSeconds(120).toNanos(),remaining(suiteDeadline,deps.nanoTime()))'),('java','Issue130StandaloneRuntimeTest','BL15_late_count_does_not_reset15s_or_generate','BL16_expiry_at_transport_before_open_HTTP0')),
 ('F07_incomplete_usage_accepted',JAVA_REL,lambda s:replace_once(s,'require(!usage.getInvalid()&&usage.getTokens().get("INPUT")!=null&&usage.getTokens().get("CANDIDATE")!=null&&usage.getTokens().get("THOUGHT")!=null,"INVALID_RESPONSE");','/* FI: incomplete usage accepted */'),('java','Issue130StandaloneRuntimeTest','BL10_all_usage_unknown_not_success')),
 ('F08_count_fullbody_preflight_comparison_removed',JAVA_REL,lambda s:replace_once(s,'&&Arrays.equals(CONTRACT.countRequestBytes(gen,MODEL),count)',''),('java','Issue130StandaloneRuntimeTest','BF08_count_fullbody_different_resealed_HTTP0')),
 ('F09_private_creation_mode_0644',JAVA_REL,lambda s:replace_once(s,'Files.newByteChannel(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))','Files.newByteChannel(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--")))'),('java','Issue130StandalonePrivateCreationTest')),
 ('F10_wrapper_startup_deadline_reset_after_validation',PY_REL,lambda s:replace_once(s,"deadline = started + plan['suiteSeconds']","deadline = time.monotonic() + plan['suiteSeconds']"),('python','StandaloneGenerationPlanTest.test_BW18_startup_expiry_no_spawn')),
 ('F11_503_final_network_terminal_guard_removed',JAVA_REL,lambda s:replace_once(s,'if(response.code()!=200){int code=response.code();response.close();throw new AiHttpProviderStatusException(code,null,new byte[0]);}','/* FI: non200 no longer terminal before OkHttp followup */'),('java','Issue130StandaloneClientTest','BT22_503_retry_after_zero_single_exchange')),
]
os.umask(0o022)
started=time.monotonic();records=[]
compile_at(OUT/'baseline-compile')
for name,rel,mutate,target in faults:
    folder=OUT/name;folder.mkdir(exist_ok=True);path=OVERLAY/rel;original=path.read_text();baseline_result=run_target(target,folder/'baseline')
    assert baseline_result['exitCode']==0 and baseline_result['summary']['errors']==0,(name,'BASELINE_NOT_GREEN')
    changed=mutate(original);(folder/'exact.patch').write_text(''.join(difflib.unified_diff(original.splitlines(True),changed.splitlines(True),fromfile='a/'+rel,tofile='b/'+rel)))
    result=None;restored=None
    try:
        path.write_text(changed)
        if rel==JAVA_REL:compile_at(folder/'mutant')
        result=run_target(target,folder/'mutant')
        assert result['exitCode']!=0 and result['summary']['errors']==0 and result['summary']['assertionFailures']>0,(name,'SURVIVED_OR_SETUP',result)
    finally:
        path.write_text(original)
        if rel==JAVA_REL:compile_at(folder/'restored')
        restored=run_target(target,folder/'restored')
        assert restored['exitCode']==0 and restored['summary']['errors']==0,(name,'RESTORE_NOT_GREEN')
        assert {p:sha(OVERLAY/p) for p in selected}==baseline
        assert inventory(SOURCE)==before,'ORIGINAL_SOURCE_DRIFT'
    record={'fault':name,'source':rel,'mutantSha256':hashlib.sha256(changed.encode()).hexdigest(),'patchSha256':sha(folder/'exact.patch'),'baseline':baseline_result,'mutant':result,'restored':restored,'overlayRestoredExact':True,'originalUnchanged':True}
    records.append(record);(folder/'receipt.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps({'fault':name,'assertionFailures':result['summary']['assertionFailures'],'restoredPass':restored['summary']['passed']}),flush=True)
final=[]
for klass in ['Issue130StandaloneClientTest','Issue130StandaloneRuntimeTest','Issue130StandalonePrivateCreationTest']:final.append(java_at(klass,[],OUT/('final-'+klass)))
final.append(python_at([],OUT/'final-python'))
assert all(v['exitCode']==0 and v['summary']['errors']==0 for v in final)
assert sum(v['summary']['tests'] for v in final)==143
after=inventory(SOURCE);assert before==after;assert {p:sha(OVERLAY/p) for p in selected}==baseline
(OUT/'original-after.json').write_text(json.dumps(after,indent=2)+'\n')
receipt={'status':'PASS_INDEPENDENT_SYNTHETIC_FAULT_DETECTION_NOT_EXECUTION_OR_QUALITY_APPROVAL','sourceRevision':subprocess.check_output(['git','rev-parse','HEAD'],cwd=SOURCE,text=True).strip(),'sourceFiles':{p:before[p] for p in [JAVA_REL,PY_REL]},'testFiles':frozen['testFiles'],'faults':records,'faultCount':len(records),'allMutantsDetected':True,'final':final,'finalMethods':143,'originalSourceBeforeAfterEqual':True,'overlayExactRestore':True,'durationSeconds':round(time.monotonic()-started,3),'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z'),'actualProviderCalls':0,'externalNetworkCalls':0,'sealedDataRead':False,'limitations':'Public synthetic stub responses prove transport/parser/runner behavior only. No model semantic efficacy, managed PG/TwoStage/UI graph, actual invoice cap or paid execution authorization.'}
(OUT/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps({'status':receipt['status'],'faultCount':len(records),'final143':True,'originalUnchanged':True,'durationSeconds':receipt['durationSeconds']}),flush=True)
