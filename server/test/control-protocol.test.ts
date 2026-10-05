import assert from 'node:assert/strict';
import test from 'node:test';
import { validateSetConfigValue } from '../src/services/ControlProtocol';

test('validateSetConfigValue accepts target sampling rates from 1 to 5', () => {
  assert.deepEqual(validateSetConfigValue('targetSamplingRate', '1', []), { ok: true, value: '1' });
  assert.deepEqual(validateSetConfigValue('targetSamplingRate', '5', []), { ok: true, value: '5' });
});

test('validateSetConfigValue rejects target sampling rates outside 1 to 5', () => {
  assert.equal(validateSetConfigValue('targetSamplingRate', '0', []).ok, false);
  assert.equal(validateSetConfigValue('targetSamplingRate', '6', []).ok, false);
  assert.equal(validateSetConfigValue('targetSamplingRate', '2.5', []).ok, false);
});

test('validateSetConfigValue only accepts model keys advertised by target device', () => {
  const options = ['yolo26n_320', 'yolo26s_640'];

  assert.deepEqual(validateSetConfigValue('modelKey', 'yolo26s_640', options), { ok: true, value: 'yolo26s_640' });
  assert.equal(validateSetConfigValue('modelKey', 'yolov5nu_320', options).ok, false);
  assert.equal(validateSetConfigValue('modelKey', '', options).ok, false);
});

test('Windows confidence matches integer percent editor and complete label lists are preserved',()=>{
 for(const value of ['0.09','0.96','0.505']) assert.equal(validateSetConfigValue('confidence',value,[],true).ok,false);
 assert.deepEqual(validateSetConfigValue('confidence','0.95',[],true),{ok:true,value:'0.95'});
 const labels=Array.from({length:80},(_,i)=>`actual-model-label-${i}`),complete=labels.join(',');
 assert.ok(complete.length>256);assert.deepEqual(validateSetConfigValue('targets',complete,[],true,labels),{ok:true,value:complete});
 assert.equal(validateSetConfigValue('targets','unknown',[],true,labels).ok,false);
 assert.equal(validateSetConfigValue('targets','',[],true,labels).ok,false);
 assert.equal(validateSetConfigValue('targets','a'.repeat(4097)).ok,false);
});
