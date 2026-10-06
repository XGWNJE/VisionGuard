import test from 'node:test';
import assert from 'node:assert/strict';
import {sanitizeRemoteSettings, validateRemoteConfig, MAX_AUDIO_BYTES, validAudioId} from '../src/services/RemoteSettings';
const id='library:'+'a'.repeat(64);
const sound={soundLoopCount:10,soundSelection:'system-default',audioPreview:'',audioEntries:[
  {id:'system-default',name:'默认',mutable:false},{id:'preset:voice',name:'内置',mutable:false},{id,name:'自定义',mutable:true}]};
test('remote configuration is bounded and does not expose arbitrary files or malformed catalogs',()=>{
  assert.deepEqual(sanitizeRemoteSettings(sound,'android-notifier'),{...sound,audioLibraryFull:false});
  assert.equal(sanitizeRemoteSettings(sound,'windows-inference'),undefined);
  assert.equal(sanitizeRemoteSettings({...sound,soundLoopCount:11},'android-notifier'),undefined);
  assert.equal(sanitizeRemoteSettings({...sound,audioEntries:[{id:'../../secret',name:'x',mutable:true}]},'android-notifier'),undefined);
  assert.equal(sanitizeRemoteSettings({...sound,audioEntries:[...sound.audioEntries,sound.audioEntries[0]]},'android-notifier'),undefined);
  assert.equal(sanitizeRemoteSettings({...sound,audioEntries:Array.from({length:117},(_,i)=>({id:'preset:x'+i,name:'x',mutable:false}))},'android-notifier'),undefined);
  assert.equal(sanitizeRemoteSettings({...sound,soundSelection:'library:'+'b'.repeat(64)},'android-notifier'),undefined);
  assert.equal(sanitizeRemoteSettings({...sound,audioEntries:[{id:'system-default',name:'a'.repeat(65),mutable:false}]},'android-notifier'),undefined);
  assert.equal(sanitizeRemoteSettings({...sound,audioEntries:[{id:'system-default',name:'x',mutable:true}]},'android-notifier'),undefined);
  const catalog=(count:number)=>({...sound,soundSelection:'library:'+'0'.repeat(64),audioEntries:Array.from({length:count},(_,i)=>({id:'library:'+i.toString(16).padStart(64,'0'),name:'音频',mutable:true}))});
  assert.equal(sanitizeRemoteSettings(catalog(100),'android-notifier')?.audioLibraryFull,true);
  assert.equal(sanitizeRemoteSettings(catalog(101),'android-notifier'),undefined);
  assert.equal(validAudioId('library:/data/private'),false);
});
test('camera updates require valid values and stopped streaming for resolution',()=>{
  const camera={cameraResolution:'480p' as const,cameraDimScreen:false,cameraHidePreview:false};
  assert.equal(sanitizeRemoteSettings(camera,'android-camera')?.cameraResolution,'480p');
  assert.equal(sanitizeRemoteSettings({...camera,cameraDimScreen:'true'},'android-camera'),undefined);
  assert.deepEqual(validateRemoteConfig('cameraResolution','720p',camera,false),{ok:true,value:'720p'});
  assert.equal(validateRemoteConfig('cameraResolution','720p',camera,true).ok,false);
  assert.equal(validateRemoteConfig('cameraResolution','1080p',camera,false).ok,false);
  assert.equal(validateRemoteConfig('cameraDimScreen','false',camera,true).ok,true);
  assert.equal(validateRemoteConfig('cameraHidePreview','1',camera,false).ok,false);
  assert.equal(validateRemoteConfig('cameraHidePreview','true',undefined,false).ok,false);
});
test('sound policy and library management reject invalid selection, protected entries and names',()=>{
  for(const value of ['0','11','1.5','01','NaN']) assert.equal(validateRemoteConfig('soundLoopCount',value,sound,false).ok,false);
  for(const value of ['1','10']) assert.equal(validateRemoteConfig('soundLoopCount',value,sound,false).ok,true);
  assert.equal(validateRemoteConfig('soundSelection',id,sound,false).ok,true);
  assert.equal(validateRemoteConfig('soundSelection','library:'+'b'.repeat(64),sound,false).ok,false);
  assert.equal(validateRemoteConfig('audioDelete','preset:voice',sound,false).ok,false);
  assert.equal(validateRemoteConfig('audioDelete',id,{...sound,soundSelection:id},false).ok,false);
  assert.equal(validateRemoteConfig('audioDelete',id,sound,false).ok,true);
  assert.equal(validateRemoteConfig('audioPreview','system-default',sound,false).ok,false);
  assert.equal(validateRemoteConfig('audioPreview','preset:voice',sound,false).ok,true);
  assert.equal(validateRemoteConfig('audioStopPreview','',sound,false).ok,true);
  assert.equal(validateRemoteConfig('audioStopPreview','data',sound,false).ok,false);
  assert.equal(validateRemoteConfig('audioRename',JSON.stringify({id,name:'x'.repeat(64)}),sound,false).ok,true);
  for(const name of ['', 'x'.repeat(65),'a\nb']) assert.equal(validateRemoteConfig('audioRename',JSON.stringify({id,name}),sound,false).ok,false);
});
test('audio import bounds encoding, count and format before routing',()=>{
  const wav=Buffer.alloc(44); wav.write('RIFF',0); wav.write('WAVE',8);
  const raw=(data=wav.toString('base64'))=>JSON.stringify({name:'声音',mime:'audio/wav',data});
  assert.equal(validateRemoteConfig('audioImport',raw(),sound,false).ok,true);
  assert.equal(validateRemoteConfig('audioImport',raw(),{...sound,audioLibraryFull:true},false).ok,false);
  assert.equal(validateRemoteConfig('audioImport',raw(Buffer.alloc(MAX_AUDIO_BYTES+1).toString('base64')),sound,false).ok,false);
  assert.equal(validateRemoteConfig('audioImport',raw(Buffer.from('not audio').toString('base64')),sound,false).ok,false);
  assert.equal(validateRemoteConfig('audioImport',raw('%%%%'),sound,false).ok,false);
  assert.equal(validateRemoteConfig('audioImport',raw(''),sound,false).ok,false);
});
