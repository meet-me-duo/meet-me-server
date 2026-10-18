import test from 'node:test';
import assert from 'node:assert/strict';
import {validateLocalDockerTarget} from './docker-target.mjs';
const base={environmentHost:null,environmentContext:null,contextEndpoint:'unix:///var/run/docker.sock',tlsVerify:null,certificatePath:null};
function valid(name,patch){test(name,()=>assert.deepEqual(validateLocalDockerTarget({...base,...patch}),{local:true}));}
function invalid(name,patch){test(name,()=>assert.throws(()=>validateLocalDockerTarget({...base,...patch}),error=>error instanceof Error&&error.code==='LOCAL_DOCKER_REQUIRED'));}
valid('DT01 default local Unix daemon',{});
valid('DT02 alternate explicitly allowed Unix daemon',{contextEndpoint:'unix:///run/docker.sock'});
valid('DT03 literal IPv4 loopback context and selected environment',{environmentHost:'tcp://127.0.0.1:2375',environmentContext:'local-test',contextEndpoint:'tcp://127.0.0.1:2375'});
valid('DT04 IPv6 loopback context and localhost selected environment',{environmentHost:'http://localhost:2375',contextEndpoint:'https://[::1]:2376'});
invalid('DT05 remote environment cannot hide behind local default context',{environmentHost:'tcp://203.0.113.10:2375'});
invalid('DT06 remote selected context rejected even if environment host is local',{environmentHost:'unix:///var/run/docker.sock',contextEndpoint:'ssh://remote.invalid'});
invalid('DT07 wildcard listen address is not a loopback target',{contextEndpoint:'tcp://0.0.0.0:2375'});
invalid('DT08 hostname prefix does not authorize unrelated DNS',{contextEndpoint:'http://localhost.external.invalid:2375'});
invalid('DT09 URL userinfo is forbidden',{contextEndpoint:'http://synthetic:credential@localhost:2375'});
invalid('DT10 endpoint query and fragment forbidden',{contextEndpoint:'http://localhost:2375/?credential=SYNTHETIC#private'});
invalid('DT11 non-root endpoint path forbidden',{contextEndpoint:'http://localhost:2375/docker'});
invalid('DT12 ambient TLS credential settings forbidden',{tlsVerify:'1',certificatePath:'/synthetic-private-certs'});
invalid('DT13 unsupported pipe transport forbidden',{contextEndpoint:'npipe:////./pipe/docker_engine'});
invalid('DT14 malformed or missing context endpoint cannot invoke daemon',{contextEndpoint:null});
invalid('DT15 shortened numeric IPv4 is not an approved literal',{contextEndpoint:'http://127.1:2375'});
invalid('DT16 encoded host is not an approved literal',{contextEndpoint:'http://%31%32%37.0.0.1:2375'});
invalid('DT17 even false-looking nonempty TLS settings use unapproved credentials',{tlsVerify:'0'});
invalid('DT18 other Unix socket is not implicitly approved',{contextEndpoint:'unix:///tmp/unapproved-docker.sock'});
invalid('DT19 malformed environment host type rejected',{environmentHost:false});
valid('DT20 empty optional environment selection is unset',{environmentHost:'',environmentContext:'',tlsVerify:'',certificatePath:''});
