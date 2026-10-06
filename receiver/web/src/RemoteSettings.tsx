import {useEffect, useId, useRef, useState} from 'react';
import type {Ack, Device} from './protocol';
import {audioImportMetadata, validAudioName, MAX_AUDIO_BYTES} from './remote-config';

type Props = {node:Device;connected:boolean;acks:Ack[];send:(m:Record<string,unknown>)=>string};
function ConfigRow({label,current,options,disabled,onSave}:{label:string;current:string;options:{value:string;label:string}[];disabled:boolean;onSave:(value:string)=>void}) {
  const [draft,setDraft]=useState(current); const id=useId();
  useEffect(()=>setDraft(current),[current]);
  const currentLabel=options.find(o=>o.value===current)?.label || '未报告';
  return <form className="parameter-field" onSubmit={e=>{e.preventDefault();onSave(draft);}}>
    <label htmlFor={id}>{label}<small className="subtle">当前：{currentLabel}</small></label>
    <div className="actions"><select id={id} value={draft} disabled={disabled} onChange={e=>setDraft(e.target.value)}>
      {!options.some(o=>o.value===draft)&&<option value={draft}>条目已不可用</option>}
      {options.map(o=><option key={o.value} value={o.value}>{o.label}</option>)}</select>
      <button className="button secondary" disabled={disabled||draft===current||!options.some(o=>o.value===draft)}>保存</button></div>
  </form>;
}
export function RemoteSettings({node,connected,acks,send}:Props) {
  const active=useRef(true);
  useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
  const [request,setRequest]=useState(''); const [timedOut,setTimedOut]=useState(false);
  const [error,setError]=useState(''); const [loading,setLoading]=useState(false);
  const [entryId,setEntryId]=useState(''); const [name,setName]=useState(''); const [confirmDelete,setConfirmDelete]=useState(false);
  const [file,setFile]=useState<File|null>(null); const [importName,setImportName]=useState('');
  const entryField=useId(), nameField=useId(), fileField=useId(), importField=useId();
  const result=acks.find(a=>a.requestId===request);
  const pending=loading || !!request && !timedOut && (!result || result.phase==='pending' || result.phase==='forwarded');
  const disabled=!connected||!node.online||pending;
  useEffect(()=>{if(!request)return;const timer=setTimeout(()=>setTimedOut(true),30000);return()=>clearTimeout(timer);},[request]);
  const settings=node.remoteSettings;
  const entries=settings?.audioEntries ?? [];
  const library=entries.filter(e=>e.mutable);
  const entry=library.find(e=>e.id===entryId);
  useEffect(()=>{setName(entry?.name ?? '');setConfirmDelete(false);},[entry?.id,entry?.name]);
  function save(key:string,value:string) {
    setError('');setTimedOut(false);const id=send({type:'set-config',targetDeviceId:node.deviceId,key,value});setRequest(id);
    if(!id)setError('发送失败，请重新连接');
  }
  async function importAudio() {
    if(!file||!validAudioName(importName)||disabled)return;
    setLoading(true);setError('');
    try {
      const metadata=audioImportMetadata(file);
      const data=await new Promise<string>((resolve,reject)=>{
        const reader=new FileReader();reader.onerror=()=>reject(new Error('读取音频失败'));reader.onload=()=>resolve(String(reader.result).split(',')[1] ?? '');reader.readAsDataURL(file);
      });
      if(active.current)save('audioImport',JSON.stringify({name:importName.trim(),mime:metadata.mime,data}));
    } catch(e) {if(active.current)setError((e as Error).message);} finally {if(active.current)setLoading(false);}
  }
  if(!node.capabilities.some(c=>['camera-config','sound-config','audio-library'].includes(c)))return null;
  return <section className="panel remote-settings"><details><summary>{node.component==='android-camera'?'相机远程配置':'声音策略与音频库'}</summary>
    {!settings ? <p className="subtle">等待节点报告当前配置，请保持节点连接。</p> : <>
      {node.capabilities.includes('camera-config')&&<>
        <ConfigRow label="推流规格" current={settings.cameraResolution ?? ''} options={[{value:'480p',label:'480p'},{value:'720p',label:'720p'}]} disabled={disabled||node.isMonitoring} onSave={v=>save('cameraResolution',v)}/>
        {node.isMonitoring&&<p className="subtle">先停止推流再修改规格；实际采集尺寸由设备支持情况决定。</p>}
        <ConfigRow label="本机预览" current={String(settings.cameraHidePreview)} options={[{value:'false',label:'显示'},{value:'true',label:'隐藏'}]} disabled={disabled} onSave={v=>save('cameraHidePreview',v)}/>
        <ConfigRow label="推流期间屏幕亮度" current={String(settings.cameraDimScreen)} options={[{value:'false',label:'系统亮度'},{value:'true',label:'变暗'}]} disabled={disabled} onSave={v=>save('cameraDimScreen',v)}/>
        <p className="subtle">配置保存在节点；摄像头授权、应用前台与解锁仍需在设备上处理。</p>
      </>}
      {node.capabilities.includes('sound-config')&&<>
        <ConfigRow label="默认铃声" current={settings.soundSelection ?? ''} options={entries.map(e=>({value:e.id,label:e.name}))} disabled={disabled} onSave={v=>save('soundSelection',v)}/>
        <ConfigRow label="播放次数" current={String(settings.soundLoopCount)} options={Array.from({length:10},(_,i)=>({value:String(i+1),label:`${i+1} 次`}))} disabled={disabled} onSave={v=>save('soundLoopCount',v)}/>
        <p className="subtle">声音策略用于后续入队报警；已入队报警保留原策略。其他系统铃声须先在本机选择。</p>
      </>}
      {node.capabilities.includes('audio-library')&&<fieldset disabled={disabled}><legend>节点音频库</legend>
        <div className="remote-audio-list">{entries.filter(e=>e.mutable||e.id.startsWith('preset:')).map(e=><div className="parameter-field" key={e.id}><span>{e.name}{!e.mutable?' · 内置':''}{settings.soundSelection===e.id?' · 默认':''}{settings.audioPreview===e.id?' · 试听中':''}</span><button className="text-button" type="button" onClick={()=>save('audioPreview',e.id)}>在节点试听</button></div>)}</div>
        {!entries.some(e=>e.mutable)&&<p className="subtle">暂无自定义音频。</p>}
        <button className="text-button" type="button" onClick={()=>save('audioStopPreview','')}>停止节点试听</button>
        <label htmlFor={entryField}>管理自定义音频<select id={entryField} value={entry?.id ?? ''} onChange={e=>setEntryId(e.target.value)}><option value="">选择条目</option>{library.map(e=><option key={e.id} value={e.id}>{e.name}</option>)}</select></label>
        {entry&&<><label htmlFor={nameField}>名称<input id={nameField} maxLength={64} value={name} onChange={e=>setName(e.target.value)}/></label><div className="actions">
          <button className="button secondary" type="button" disabled={!validAudioName(name)||name.trim()===entry.name} onClick={()=>save('audioRename',JSON.stringify({id:entry.id,name:name.trim()}))}>保存名称</button>
          <button className="text-button danger" type="button" disabled={settings.soundSelection===entry.id} onClick={()=>setConfirmDelete(true)}>删除音频</button></div>
          {settings.soundSelection===entry.id&&<p className="subtle">请先选择其他默认铃声再删除。</p>}
          {confirmDelete&&<div role="group" aria-label="确认删除音频"><p>删除“{entry.name}”的节点文件？正在被报警队列使用时会拒绝删除。</p><div className="actions"><button className="button" type="button" onClick={()=>{setConfirmDelete(false);save('audioDelete',entry.id);}}>确认删除</button><button className="text-button" type="button" onClick={()=>setConfirmDelete(false)}>取消</button></div></div>}
        </>}
        <form onSubmit={e=>{e.preventDefault();void importAudio();}}><label htmlFor={fileField}>导入音频<input id={fileField} type="file" accept=".mp3,.wav,.ogg,.m4a" disabled={!!settings.audioLibraryFull} onChange={e=>{
          const next=e.target.files?.[0]??null;setFile(null);setError('');if(!next)return;
          try{const metadata=audioImportMetadata(next);setFile(next);setImportName(metadata.name);}catch(error){setError((error as Error).message);e.target.value='';}
        }}/></label><label htmlFor={importField}>展示名称<input id={importField} maxLength={64} value={importName} onChange={e=>setImportName(e.target.value)}/></label>
          <button className="button secondary" disabled={!file||file.size>MAX_AUDIO_BYTES||!validAudioName(importName)||!!settings.audioLibraryFull}>导入到节点</button>
        </form><p className="subtle">自定义库最多 100 项；远程导入支持 MP3 / WAV / OGG / M4A，文件最多 512 KiB、时长最多 60 秒。录音和麦克风授权需在本机操作。试听执行回执不代表声音已可听。</p>
        {settings.audioLibraryFull&&<p className="subtle">音频库已满，请先删除不用的条目。</p>}
      </fieldset>}
    </>}
    {request&&<p role={result?.phase==='completed'&&!result.success?'alert':'status'} className={result?.phase==='completed'&&!result.success?'error':'subtle'}>{result?.phase==='completed'?result.reason||(result.success?'已保存':'执行失败'):timedOut||result?.phase==='uncertain'?'结果未确认，请核对节点当前配置后重试':result?.phase==='forwarded'?'已转发，等待节点执行':'正在发送…'}</p>}
    {error&&<p className="error" role="alert">{error}</p>}
  </details></section>;
}
