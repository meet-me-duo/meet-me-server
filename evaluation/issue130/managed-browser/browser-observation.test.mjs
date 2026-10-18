import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {createBrowserObservation} from './browser-observation.mjs';
const allowed=['https://localhost:19443','https://localhost:19444','http://127.0.0.1:34567'];
function surface(existing=[]) {
  const value=new EventEmitter();value.calls=[];value.pages=()=>existing;
  for(const method of ['route','routeFromHAR','routeWebSocket']) value[method]=function(...args){value.calls.push({method,args,self:this});return method+'-original-result';};
  return value;
}
test('BO01 known request leaves zero counters and exposes numeric metadata only',()=>{const o=createBrowserObservation(allowed),c=surface();assert.equal(o.wrapContext(c),c);c.emit('request',{url:()=>allowed[1]+'/api/rooms/synthetic?private=DO_NOT_STORE'});assert.deepEqual(o.snapshot(),{routesIntercepted:0,unexpectedOrigins:0});});
test('BO02 unexpected or malformed request origin is counted without storing URL',()=>{const o=createBrowserObservation(allowed),c=surface();o.wrapContext(c);c.emit('request',{url:()=>'https://external.invalid/PRIVATE_URL?credential=SYNTHETIC'});c.emit('request',{url:()=>'not-a-url'});assert.deepEqual(o.snapshot(),{routesIntercepted:0,unexpectedOrigins:2});assert.equal(JSON.stringify(o.snapshot()).includes('PRIVATE'),false);});
test('BO03 context routing methods count and preserve this arguments and return value',()=>{const o=createBrowserObservation(allowed),c=surface();o.wrapContext(c);const args=['synthetic-pattern',()=>{}];for(const method of ['route','routeFromHAR','routeWebSocket']){assert.equal(c[method](...args),method+'-original-result');const last=c.calls.at(-1);assert.equal(last.self,c);assert.deepEqual(last.args,args);}assert.deepEqual(o.snapshot(),{routesIntercepted:3,unexpectedOrigins:0});});
test('BO04 existing and future pages are wrapped once even on duplicate context notification',()=>{const old=surface(),c=surface([old]),o=createBrowserObservation(allowed);o.wrapContext(c);o.wrapContext(c);c.emit('page',old);old.route('old',()=>{});const next=surface();c.emit('page',next);next.routeFromHAR('new');next.routeWebSocket('ws',()=>{});c.emit('request',{url:()=>'https://external.invalid/'});assert.deepEqual(o.snapshot(),{routesIntercepted:3,unexpectedOrigins:1});});
test('BO05 upstream route error remains the same error and attempt still counted',()=>{const c=surface(),failure=new Error('SYNTHETIC_UPSTREAM_ERROR');c.route=()=>{throw failure;};const o=createBrowserObservation(allowed);o.wrapContext(c);assert.throws(()=>c.route('x',()=>{}),error=>error===failure);assert.deepEqual(o.snapshot(),{routesIntercepted:1,unexpectedOrigins:0});});
test('BO06 websocket destinations on pages follow the same exact-origin allowlist',()=>{const p=surface(),c=surface([p]),o=createBrowserObservation(allowed);o.wrapContext(c);p.emit('websocket',{url:()=>'wss://external.invalid/DO_NOT_STORE'});assert.deepEqual(o.snapshot(),{routesIntercepted:0,unexpectedOrigins:1});});
