import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import test from 'node:test';
const compiled=ts.transpileModule(readFileSync(new URL('../src/connectionDiagnostics.ts',import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
function recorder(initial=[],blocked=false){
  let saved=JSON.stringify(initial);const logs=[],module={exports:{}};
  const sessionStorage={getItem(){if(blocked)throw Error('storage blocked');return saved;},setItem(_key,value){saved=value;}};
  new vm.Script(compiled).runInContext(vm.createContext({module,exports:module.exports,sessionStorage,console:{info:(_tag,value)=>logs.push(JSON.parse(value))}}));
  return{record:module.exports.recordConnectionEvent,logs,stored:()=>JSON.parse(saved)};
}
const entry={attemptId:'a'.repeat(32),attempt:1,phase:'connecting',event:'connect-start',elapsedMs:0};
test('diagnostics retain at most 100 recent records and discard old records and extra credential fields',()=>{
  const r=recorder([{...entry,timestamp:new Date(Date.now()-25*3600000).toISOString(),token:'old-private-token'}]);
  for(let i=0;i<105;i++)r.record({...entry,attempt:i,token:'private-token',password:'private-password',url:'https://example.test/?token=private'});
  assert.equal(r.stored().length,100);assert.equal(r.stored()[0].attempt,5);
  const text=JSON.stringify([...r.logs,...r.stored()]);
  assert.ok(!text.includes('private'));assert.ok(!text.includes('token'));assert.ok(!text.includes('password'));assert.ok(!text.includes('https://'));
});
test('denied browser storage does not prevent diagnostic output or throw into the login flow',()=>{
  const r=recorder([],true);assert.doesNotThrow(()=>r.record(entry));assert.equal(r.logs.length,1);
});
