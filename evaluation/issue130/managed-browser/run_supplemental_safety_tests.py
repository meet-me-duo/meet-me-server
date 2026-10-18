#!/usr/bin/env python3
"""Synthetic supplemental root/JDK pin checks; no application/network/executable dispatch."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    java = Path(args.java).resolve(strict=True)
    output = Path(args.output)
    files = ['runtime_pin.py', 'test_runtime_pin.py', 'test_runtime_pin_ancestor.py', 'ManagedBrowserLoopbackGuard.java', 'ManagedBrowserGuardJdkPinContractTest.java', 'run_supplemental_safety_tests.py']
    digest = lambda: {name: hashlib.sha256((root/name).read_bytes()).hexdigest() for name in files}
    with open('/tmp/meetme-gradle.lock','a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        output.mkdir(parents=True,exist_ok=False)
        before=digest()
        env=dict(os.environ,OPENAI_API_KEY='',GEMINI_API_KEY='',PYTHONDONTWRITEBYTECODE='1',JAVA_HOME=str(java.parent.parent))
        for key in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:
            env.pop(key,None)
        start=time.monotonic()
        py=subprocess.run(['python3',str(root/'test_runtime_pin_ancestor.py')],env=env,capture_output=True,text=True,timeout=30)
        (output/'python.log').write_text(py.stdout+py.stderr)
        classes=output/'classes';classes.mkdir()
        compile_run=subprocess.run([str(java.parent/'javac'),'-d',str(classes),str(root/'ManagedBrowserLoopbackGuard.java'),str(root/'ManagedBrowserGuardJdkPinContractTest.java')],env=env,capture_output=True,text=True,timeout=60)
        (output/'compile.log').write_text(compile_run.stdout+compile_run.stderr)
        receipt={'python':{'exitCode':py.returncode,'tests':2 if 'Ran 2 tests' in py.stderr else 0,'failures':py.stderr.count('\nFAIL: '),'errors':py.stderr.count('\nERROR: ')},'compileExitCode':compile_run.returncode,'sourceBefore':before}
        if compile_run.returncode==0:
            run=subprocess.run([str(java),'-cp',str(classes),'com.meetme.integration.managed.ManagedBrowserGuardJdkPinContractTest'],env=env,capture_output=True,text=True,timeout=30)
            (output/'java.log').write_text(run.stdout+run.stderr)
            receipt['java']=dict(json.loads(run.stdout.splitlines()[0]),exitCode=run.returncode)
        else:
            receipt['java']={'tests':0,'classification':'COMPILE_ERROR_NOT_RED'}
        receipt['sourceAfter']=digest();receipt['sourceBeforeAfterEqual']=before==receipt['sourceAfter'];receipt['elapsedSeconds']=time.monotonic()-start
        (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
        return 0 if py.returncode==0 and compile_run.returncode==0 and receipt['java'].get('exitCode')==0 else 1


if __name__=='__main__':
    raise SystemExit(main())
