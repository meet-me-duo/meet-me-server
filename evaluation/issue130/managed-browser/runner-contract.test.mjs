import assert from 'node:assert/strict';
import test from 'node:test';
import {validateManagedReadiness, validateManagedCompletion} from './runner-contract.mjs';

const hash = 'a'.repeat(64);
const graph = {provider:'TwoStagePreSubmissionCheckProvider',classifier:'DecisionAmbiguityClassifierAdapter',budget:'DurableAiBudgetService',repository:'JdbcDurableAiBudgetRepository',gate:'AiHttpDispatchGate'};
const pins = {schemaVersion:1,web:{head:'1'.repeat(40),files:{'src/App.tsx':hash}},server:{head:'2'.repeat(40),files:{'src/main/Provider.kt':hash}},runtime:{files:{'classes/Provider.class':hash,'lib/runtime.jar':'b'.repeat(64)}},graph,scenarios:['M01','M02','M03','M04'],projects:['desktop','mobile']};
const provenance = {webHead:pins.web.head,serverHead:pins.server.head,webFiles:pins.web.files,serverFiles:pins.server.files,runtimeFiles:pins.runtime.files};
const ready = {provenanceBefore:provenance,health:{fixture:'issue130-managed-browser-v1',production:false,primaryProviderFake:false,managedEnabled:true,providerEnabled:true,routingEnabled:true,classifierEnabled:true,graph},outbound:{mode:'LOOPBACK_DENY_ALL_OTHER',installedBeforeApplication:true,negativeProbeDenied:true,nonLoopbackConnections:0},credentials:{syntheticOnly:true,realEnvironmentKeysInherited:false}};
const complete = {...ready,provenanceAfter:structuredClone(provenance),assetsBefore:{'assets/app.js':hash},assetsAfter:{'assets/app.js':hash},browser:{cases:pins.scenarios.flatMap(scenario=>pins.projects.map(project=>({scenario,project,status:'passed',attempts:1}))),routesIntercepted:0,unexpectedOrigins:0},ledger:{reservations:5,dispatchClaims:5,observations:5,operationCounts:{PRECHECK_CLASSIFICATION:3,PRECHECK_COUNT:1,PRECHECK_GENERATION:1},missingOrMismatchedDispatches:0},network:{remoteCalls:0,localSyntheticCalls:5},cleanup:{ownedContainersRemaining:0,ownedListenersRemaining:0,privateTempMaterialRemaining:0}};
const copy = value => structuredClone(value);
function reject(name, code, mutate, stage='completion') {
  test(name, () => {const p=copy(pins),o=copy(stage==='readiness'?ready:complete);mutate(p,o);assert.throws(()=> (stage==='readiness'?validateManagedReadiness:validateManagedCompletion)(p,o),error=>error instanceof Error && error.code===code,code+' must fail closed');});
}

test('MC01 source-bound isolated managed readiness accepted',()=>assert.equal(validateManagedReadiness(copy(pins),copy(ready)).ready,true));
test('MC02 actual eight plus local managed ledger and complete cleanup accepted',()=>assert.equal(validateManagedCompletion(copy(pins),copy(complete)).verified,true));
reject('MC03 empty inventory is not a pin','INVALID_PINS',p=>{p.runtime.files={};},'readiness');
reject('MC04 stale web HEAD rejected before launch','SOURCE_PIN_MISMATCH',(_,o)=>{o.provenanceBefore.webHead='3'.repeat(40);},'readiness');
reject('MC05 same HEAD with altered approved source rejected','SOURCE_PIN_MISMATCH',(_,o)=>{o.provenanceBefore.serverFiles['src/main/Provider.kt']='c'.repeat(64);},'readiness');
reject('MC06 altered runtime jar rejected','RUNTIME_PIN_MISMATCH',(_,o)=>{o.provenanceBefore.runtimeFiles['lib/runtime.jar']='c'.repeat(64);},'readiness');
reject('MC07 old Primary fake cannot impersonate managed proof','MANAGED_GRAPH_REQUIRED',(_,o)=>{o.health.primaryProviderFake=true;},'readiness');
reject('MC08 managed OFF never accepted','MANAGED_GRAPH_REQUIRED',(_,o)=>{o.health.managedEnabled=false;},'readiness');
reject('MC09 bean graph mismatch fails even with ON flags','MANAGED_GRAPH_REQUIRED',(_,o)=>{o.health.graph.budget='SyntheticAllowAllBudget';},'readiness');
reject('MC10 deny probe required before app startup','OUTBOUND_DENY_REQUIRED',(_,o)=>{o.outbound.installedBeforeApplication=false;},'readiness');
reject('MC11 observed external connection fails','OUTBOUND_DENY_REQUIRED',(_,o)=>{o.outbound.nonLoopbackConnections=1;},'readiness');
reject('MC12 inherited credentials forbidden','SYNTHETIC_CREDENTIALS_REQUIRED',(_,o)=>{o.credentials.realEnvironmentKeysInherited=true;},'readiness');
reject('MC13 changed source after successful browser fails','SOURCE_PIN_MISMATCH',(_,o)=>{o.provenanceAfter.webFiles['src/App.tsx']='d'.repeat(64);});
reject('MC14 changed runtime after browser fails','RUNTIME_PIN_MISMATCH',(_,o)=>{delete o.provenanceAfter.runtimeFiles['lib/runtime.jar'];});
reject('MC15 changed served assets fail','ASSET_PIN_MISMATCH',(_,o)=>{o.assetsAfter['assets/app.js']='c'.repeat(64);});
reject('MC16 duplicate case cannot replace missing mobile coverage','EXACT_BROWSER_MATRIX_REQUIRED',(_,o)=>{o.browser.cases[7]=copy(o.browser.cases[6]);});
reject('MC17 retry is not first-attempt proof','EXACT_BROWSER_MATRIX_REQUIRED',(_,o)=>{o.browser.cases[0].attempts=2;});
reject('MC18 skipped case cannot count as pass','EXACT_BROWSER_MATRIX_REQUIRED',(_,o)=>{o.browser.cases[0].status='skipped';});
reject('MC19 route interception cannot simulate real preflight','EXACT_BROWSER_MATRIX_REQUIRED',(_,o)=>{o.browser.routesIntercepted=1;});
reject('MC20 unexpected origin invalidates isolated browser','EXACT_BROWSER_MATRIX_REQUIRED',(_,o)=>{o.browser.unexpectedOrigins=1;});
reject('MC21 browser success without actual durable claims fails','REAL_MANAGED_LEDGER_REQUIRED',(_,o)=>{o.ledger.dispatchClaims=0;});
reject('MC22 A-only route cannot prove count and generation','REAL_MANAGED_LEDGER_REQUIRED',(_,o)=>{o.ledger.operationCounts.PRECHECK_GENERATION=0;});
reject('MC23 missing ledger binding cannot be hidden by counts','REAL_MANAGED_LEDGER_REQUIRED',(_,o)=>{o.ledger.missingOrMismatchedDispatches=1;});
reject('MC24 remote model request forbidden','EXTERNAL_PROVIDER_FORBIDDEN',(_,o)=>{o.network.remoteCalls=1;});
reject('MC25 no physical local requests means disconnected proof','EXTERNAL_PROVIDER_FORBIDDEN',(_,o)=>{o.network.localSyntheticCalls=0;});
reject('MC26 owned leftover container fails cleanup','CLEANUP_INCOMPLETE',(_,o)=>{o.cleanup.ownedContainersRemaining=1;});
reject('MC27 boolean is not a ledger count','REAL_MANAGED_LEDGER_REQUIRED',(_,o)=>{o.ledger.reservations=true;});
reject('MC28 missing cleanup evidence fails closed','CLEANUP_INCOMPLETE',(_,o)=>{delete o.cleanup;});
