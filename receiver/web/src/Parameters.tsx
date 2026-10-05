import React, { useEffect, useRef, useState } from 'react';
import type { Ack, Device, Source } from './protocol';
type Entry = { draft: string; editing: boolean; request?: string; status?: 'pending'|'confirmed'|'failed'|'uncertain'; message?: string; model?: string };
export type ParameterDrafts = Map<string, Record<string, Entry>>;
export function Parameters({node,source,disabled,send,acks,drafts}:{node:Device;source?:Source;disabled:boolean;send:(m:Record<string,unknown>)=>string;acks:Ack[];drafts:ParameterDrafts}) {
  const context = `${node.deviceId}/${source?.sourceId ?? ''}`;
  const [entries,setEntries] = useState(() => drafts.get(context) ?? {});
  const config = source ?? node;
  const fields = node.nodeType === 'sensor' ? [['confidence','置信度阈值'],['cooldown','报警冷却（秒）']] : [['modelKey','推理模型'],['confidence','置信度阈值'],['cooldown','报警冷却（秒）'],['targetSamplingRate','采样频率（FPS）'],['targets','检测目标']];
  function write(key:string, entry:Entry|null) { setEntries(previous => { const next={...previous}; if(entry)next[key]=entry;else delete next[key];drafts.set(context,next);return next; }); }
  return <section className="panel parameter-panel"><div className="section-title"><h2>{source ? source.sourceName || source.sourceId : node.deviceName} · 参数</h2><span className="subtle">{disabled ? '暂停并连接后可保存' : '逐项编辑'}</span></div>
    <div className="parameter-grid">{fields.map(([key,label]) => <ParameterField key={key} name={key} label={label} value={config[key as keyof typeof config]} model={config.modelKey ?? ''} node={node} source={source} disabled={disabled} send={send} acks={acks} entry={entries[key]} write={entry=>write(key,entry)}/>)}</div>
  </section>;
}
function ParameterField({name,label,value,model,node,source,disabled,send,acks,entry,write}:{name:string;label:string;value:unknown;model:string;node:Device;source?:Source;disabled:boolean;send:(m:Record<string,unknown>)=>string;acks:Ack[];entry?:Entry;write:(entry:Entry|null)=>void}) {
  const id=React.useId(), current=value === undefined ? '' : String(value);
  const editButton=useRef<HTMLButtonElement>(null), wasEditing=useRef(false);
  useEffect(()=>{ if(wasEditing.current && !entry?.editing)editButton.current?.focus();wasEditing.current=!!entry?.editing; },[entry?.editing]);
  const labels=node.modelLabels?.[model] ?? [];
  const [search,setSearch]=useState('');
  const choices=labels.filter(item=>`${item.label} ${item.value}`.toLowerCase().includes(search.trim().toLowerCase()));
  const targets=(entry?.draft ?? current).split(',').map(item=>item.trim()).filter(Boolean);
  const targetNames=current.split(',').filter(Boolean).map(target=>labels.find(item=>item.value===target)?.label ?? target);
  const display=name === 'targets' ? targetNames.length > 3 ? `${targetNames.length} 项 · ${targetNames.slice(0,3).join('、')}…` : targetNames.join('、') : current;
  const result=acks.find(ack=>ack.requestId===entry?.request);
  const saving=entry?.status==='pending';
  useEffect(()=>{
    if (!entry?.request || !['pending','uncertain'].includes(entry.status ?? '')) return;
    if (result?.phase==='completed') write({...entry,status:result.success?'confirmed':'failed',editing:!result.success,message:result.reason});
    else if (result?.phase==='uncertain' || disabled) write({...entry,status:'uncertain',message:result?.reason ?? '连接或节点状态改变，结果待核实'});
  },[result?.phase,result?.success,result?.reason,disabled,entry?.request,entry?.status]);
  const modelMissing=name==='modelKey' && !node.modelOptions?.length;
  const labelsMissing=name==='targets' && !labels.length;
  const numeric=!['modelKey','targets'].includes(name);
  const min=name==='confidence' ? node.platform==='windows' ? .1 : .01 : 1;
  const max=name==='confidence' ? node.platform==='windows' ? .95 : 1 : name==='targetSamplingRate' ? 5 : 300;
  const draft=entry?.draft ?? current, number=Number(draft);
  const valid=name==='modelKey' ? !!node.modelOptions?.includes(draft) : name==='targets' ? targets.length>0 && targets.every(item=>labels.some(label=>label.value===item)) : draft.trim()!=='' && Number.isFinite(number) && number>=min && number<=max && (name==='confidence' ? node.platform!=='windows' || Math.abs(number*100-Math.round(number*100))<.000001 : Number.isInteger(number));
  const matches=name==='targets' ? targets.slice().sort().join(',')===current.split(',').filter(Boolean).sort().join(',') : numeric ? Number(draft)===Number(current) : draft===current;
  const feedback=entry?.status==='confirmed' ? matches ? '已保存' : '执行端已保存，等待读回' : entry?.status==='pending' ? '等待执行端结果…' : entry?.message;
  function change(next:string) { if(entry)write({...entry,draft:next,status:undefined,message:''}); }
  function save() { if(disabled || saving || !valid || !entry) return; const request=send({type:'set-config',targetDeviceId:node.deviceId,...(source?{targetSourceId:source.sourceId}:{}),key:name,value:draft});write({...entry,request,status:request?'pending':'failed',message:request?'':'发送失败，草稿已保留'}); }
  return <form className="parameter-field" onKeyDown={event=>{if(event.key==='Escape'&&entry?.editing&&!saving){event.preventDefault();write(null);}}} onSubmit={event=>{event.preventDefault();save();}}><div className="parameter-reading"><label htmlFor={id}>{label}</label><span className="current-value" title={name==='targets'?targetNames.join('、'):current}>{display || '未报告'}</span>{!entry?.editing && <button ref={editButton} className="text-button" type="button" disabled={disabled||saving||modelMissing||labelsMissing} onClick={()=>write({draft:current,editing:true,model})}>{name==='targets'?'选择':'编辑'}</button>}</div>
    {entry?.editing && <div className="parameter-editing">
      {name==='modelKey' ? <select autoFocus id={id} value={draft} disabled={disabled||saving} onChange={event=>change(event.target.value)}>{!node.modelOptions?.includes(draft)&&<option value={draft}>当前模型不可用：{draft||'未报告'}</option>}{node.modelOptions?.map(option=><option key={option}>{option}</option>)}</select>
      : name==='targets' ? <><input autoFocus id={id} aria-label="搜索检测目标" placeholder="搜索中文名称或标签" value={search} onChange={event=>setSearch(event.target.value)}/><div className="target-options">{choices.map(item=><label key={item.value}><input type="checkbox" checked={targets.includes(item.value)} disabled={disabled||saving||targets.length===1&&targets.includes(item.value)} onChange={()=>change(targets.includes(item.value)?targets.filter(value=>value!==item.value).join(','):[...targets,item.value].join(','))}/>{item.label}<small>{item.value}</small></label>)}{!choices.length&&<span className="subtle">没有匹配的目标</span>}</div><small className="subtle">已选 {targets.length} 项 · 当前模型 {model}{entry.model!==model?' · 模型已变化，请重新核对':''}</small></>
      : <input autoFocus id={id} type="number" required min={min} max={max} step={name==='confidence'?.01:1} value={draft} disabled={disabled||saving} onChange={event=>change(event.target.value)}/>}
      <div className="actions"><button className="button" type="submit" disabled={disabled||saving||!valid}>{saving?'等待结果…':'保存'}</button><button className="text-button" type="button" disabled={saving} onClick={()=>write({...entry,draft:current,status:undefined,message:'',model})}>还原</button><button className="text-button" type="button" disabled={saving} onClick={()=>write(null)}>取消</button></div>
      {!valid && <small className="error">{name==='targets'?'至少一项，且必须属于当前模型':name==='modelKey'?'请选择节点可用模型':`范围 ${min}–${max}${name==='confidence'?'':'，整数'}`}</small>}
    </div>}
    {(modelMissing||labelsMissing)&&<small className="subtle">{modelMissing?'节点未报告可用模型':'当前模型未报告标签，不能猜测检测目标'}</small>}
    {feedback&&<small role={entry?.status==='failed'?'alert':'status'} className={entry?.status==='failed'?'error':'subtle'}>{feedback}</small>}
  </form>;
}
