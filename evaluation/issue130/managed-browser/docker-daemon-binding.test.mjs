import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,readFileSync,existsSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {validateManagedDockerBinding} from './docker-daemon-binding.mjs';
const fixed='unix:///var/run/docker.sock';
const here=dirname(fileURLToPath(import.meta.url));
function reject(name,selected,backend){test(name,()=>assert.throws(()=>validateManagedDockerBinding(selected,backend),error=>error instanceof Error&&error.code==='DOCKER_DAEMON_BINDING_REQUIRED'));}
test('DB01 selected runner and fixed backend exact endpoint bind',()=>assert.deepEqual(validateManagedDockerBinding(fixed,fixed),{bound:true}));
reject('DB02 generic-local Unix alias is outside the managed profile','unix:///run/docker.sock',fixed);
reject('DB03 generic-local TCP cannot select another daemon','tcp://127.0.0.1:2375',fixed);
reject('DB04 even matching TCP endpoints cannot replace fixed backend','tcp://127.0.0.1:2375','tcp://127.0.0.1:2375');
reject('DB05 backend endpoint must be the declared fixed profile',fixed,'unix:///run/docker.sock');
reject('DB06 missing backend binding is not proof',fixed,undefined);
reject('DB07 missing selected endpoint is not implicit authority',null,fixed);
reject('DB08 normalized-looking different Unix string is rejected','unix:///var/run/../run/docker.sock',fixed);
reject('DB09 plain path is not a Docker endpoint','/var/run/docker.sock',fixed);
reject('DB10 two aliases cannot impersonate exact fixed profile','unix:///run/docker.sock','unix:///run/docker.sock');
reject('DB11 remote selected endpoint rejected without a separate generic preflight','tcp://203.0.113.10:2375',fixed);
reject('DB12 string-like object cannot grant daemon authority',{toString:()=>fixed},fixed);

function cliReject(name,environmentHost,contextEndpoint){
  test(name,()=>{
    assert.ok(Number(process.versions.node.split('.')[0])>=24,'Pinned runner requires Node24 before the diagnostic');
    const directory=mkdtempSync(join(tmpdir(),'issue130-docker-binding-negative-'));
    try {
      const log=join(directory,'docker-invocations.jsonl');const executable=join(directory,'docker');
      const code=['#!'+process.execPath,"const fs=require('node:fs');",'const args=process.argv.slice(2);',
        'fs.appendFileSync('+JSON.stringify(log)+",JSON.stringify(args)+'\\n');",
        "if(args[0]==='context'){console.log(JSON.stringify("+JSON.stringify(contextEndpoint)+'));process.exit(0);}',
        'process.exit(73);'].join('\n');
      writeFileSync(executable,code+'\n',{mode:0o700});
      const env={PATH:directory+':'+(process.env.PATH??'/usr/bin:/bin'),HOME:directory,TMPDIR:directory,LANG:'C.UTF-8',OPENAI_API_KEY:'',GEMINI_API_KEY:''};
      if(environmentHost!==null)env.DOCKER_HOST=environmentHost;
      const command=[join(here,'run-managed-browser.mjs'),'--manifest',join(directory,'never-read-manifest.json'),'--web','/workspace/issue130-managed-browser-integration/web','--suite','new-managed8'];
      const result=spawnSync(process.execPath,command,{cwd:here,env,encoding:'utf8',timeout:15_000});
      assert.equal(result.error,undefined,'Runner diagnostic must actually execute, not fail preparation');
      const calls=existsSync(log)?readFileSync(log,'utf8').trim().split('\n').filter(Boolean).map(line=>JSON.parse(line)):[];
      const daemonCalls=calls.filter(args=>args[0]!=='context');
      const summaries=(result.stdout??'').trim().split('\n').flatMap(line=>{try{return[JSON.parse(line)];}catch{return[];}});
      if(process.env.MANAGED_DOCKER_BINDING_DIAGNOSTIC_DIR) writeFileSync(join(process.env.MANAGED_DOCKER_BINDING_DIAGNOSTIC_DIR,name.slice(0,4)+'.json'),JSON.stringify({case:name.slice(0,4),metadataCalls:calls.length-daemonCalls.length,daemonCalls:daemonCalls.length,status:result.status,failureCode:summaries.at(-1)?.failureCode??null})+'\n',{mode:0o600});
      assert.equal(daemonCalls.length,0,'Mismatched managed daemon endpoint must reject before Docker info/inspect/run');
      assert.equal(summaries.at(-1)?.failureCode,'DOCKER_DAEMON_BINDING_REQUIRED','CLI must reject the binding, not stumble on an unrelated later setup step');
      assert.equal(result.status,1);assert.equal(summaries.at(-1)?.completed,false);
    } finally {rmSync(directory,{recursive:true,force:true});}
  });
}
cliReject('DB13 CLI rejects ambient localTCP before any daemon command','tcp://127.0.0.1:2375',fixed);
cliReject('DB14 CLI rejects localTCP context before any daemon command',null,'tcp://127.0.0.1:2375');
