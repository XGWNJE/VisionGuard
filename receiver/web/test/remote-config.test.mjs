import test from 'node:test';
import assert from 'node:assert/strict';
import {audioImportMetadata, validAudioName, MAX_AUDIO_BYTES} from '../src/remote-config.ts';
test('audio picker bounds input and generates legal display names before upload',()=>{
  assert.deepEqual(audioImportMetadata({name:'提示.wav',size:44,type:'audio/x-wav'}),{name:'提示',mime:'audio/wav'});
  assert.equal(audioImportMetadata({name:'a'.repeat(100)+'.mp3',size:MAX_AUDIO_BYTES,type:''}).name.length,64);
  assert.equal(audioImportMetadata({name:'a'+'😀'.repeat(40)+'.wav',size:44,type:''}).name.length,63);
  for(const size of [0,MAX_AUDIO_BYTES+1]) assert.throws(()=>audioImportMetadata({name:'x.wav',size,type:''}));
  assert.throws(()=>audioImportMetadata({name:'x.exe',size:50,type:'audio/mpeg'}));
  assert.equal(validAudioName('a'.repeat(64)),true);
  for(const name of ['','a'.repeat(65),'x\ny']) assert.equal(validAudioName(name),false);
});
