import React, { useEffect, useRef, useState } from 'react';
import { accountRequest, type Login } from './account';
import type { Ack, Device } from './protocol';
import { HardDrive, RefreshCw, Trash2 } from 'lucide-react';

export type CacheReport = { categories: {id:string;label:string;files:number;bytes:number;cleanableFiles:number;cleanableBytes:number}[]; removedFiles:number;removedBytes:number;releasedBytes:number|null;failedFiles:number };
const size = (bytes:number) => bytes >= 1024*1024 ? `${(bytes/1024/1024).toFixed(1)} MB` : `${(bytes/1024).toFixed(1)} KB`;
function Report({report}:{report:CacheReport}) {
  return <><dl>{report.categories.map(category=><React.Fragment key={category.id}><dt>{category.label}</dt><dd>{size(category.bytes)} · {category.files} 个<br/><small>可清理 {size(category.cleanableBytes)} · {category.cleanableFiles} 个</small></dd></React.Fragment>)}</dl>
    <p className={report.failedFiles?'error':'subtle'} role="status">本次删除 {report.removedFiles} 个文件（{size(report.removedBytes)}）；{report.releasedBytes === null ? "磁盘释放量未计量" : `释放 ${size(report.releasedBytes)}`}。{report.failedFiles>0 && `${report.failedFiles} 个失败，请重新盘点。`}</p></>;
}
export function ServerCacheMaintenance({login}:{login:Login}) {
  const [scope,setScope] = useState('account'), [report,setReport] = useState<CacheReport|null>(null), [busy,setBusy] = useState(false), [error,setError] = useState('');
  const generation = useRef(0);
  useEffect(()=>{generation.current++;setReport(null);setError('');setBusy(false);return ()=>{generation.current++;};},[login.token,scope]);
  async function run(clean:boolean) {
    const own=++generation.current;setBusy(true);setError('');
    try { const next=await accountRequest<CacheReport>(clean?'/api/cache':`/api/cache?scope=${scope}`,login.token,clean?{scope}:undefined);if(own===generation.current)setReport(next); }
    catch(e){if(own===generation.current){setReport(null);setError((e as Error).message+'；结果未确认时，请重新盘点。');}}
    finally{if(own===generation.current)setBusy(false);}
  }
  return <section className="panel settings-panel cache-panel" id="settings-cache"><div className="section-title"><h2>统一服务缓存</h2><HardDrive size={18} aria-hidden="true"/></div><p className="subtle">清理过期且未被事件引用的截图，默认保留 72 小时。</p>
    {login.account.isAdmin && <label>范围<select value={scope} disabled={busy} onChange={e=>setScope(e.target.value)}><option value="account">当前账号</option><option value="legacy">旧公共截图目录</option></select></label>}
    {busy&&<p role="status">正在处理…</p>}{error&&<p className="error" role="alert">{error}</p>}{report&&<Report report={report}/>}
    <div className="actions"><button className="button secondary" disabled={busy} onClick={()=>void run(false)}><RefreshCw size={16}/>盘点缓存</button><button className="button" disabled={busy||!report?.categories.some(c=>c.cleanableFiles>0)} title={!report?'请先盘点缓存':report.categories.some(c=>c.cleanableFiles>0)?undefined:'没有可清理的缓存'} onClick={()=>void run(true)}><Trash2 size={16}/>清理缓存</button></div></section>;
}
export function NodeCacheMaintenance({node,connected,acks,send}:{node:Device;connected:boolean;acks:Ack[];send:(m:Record<string,unknown>)=>string}) {
  const [request,setRequest] = useState('');
  const ack=acks.find(a=>a.requestId===request), busy=ack?.phase==='pending'||ack?.phase==='forwarded';
  const report=ack?.phase==='completed'&&ack.success?ack.cache:undefined;
  if(!node.capabilities.includes('cache-maintenance'))return null;
  return <section className="panel cache-panel"><div className="section-title"><h2>节点缓存</h2><HardDrive size={18} aria-hidden="true"/></div><p className="subtle">节点按固定目录和保留期限处理；下载、当前使用文件和持久数据受保护。</p><div className="actions">
    <button className="button secondary" disabled={!connected||!node.online||busy} onClick={()=>setRequest(send({type:'command',targetDeviceId:node.deviceId,command:'cache-inspect'}))}>盘点缓存</button>
    <button className="button" disabled={!connected||!node.online||busy||!report?.categories.some(c=>c.cleanableFiles>0)} onClick={()=>setRequest(send({type:'command',targetDeviceId:node.deviceId,command:'cache-clean'}))}>清理可回收缓存</button></div>
    {ack&&<p className={ack.success?'subtle':'error'} role={ack.success?'status':'alert'}>{ack.reason}{ack.phase==='uncertain'&&'；请重新盘点。'}</p>}{report&&<Report report={report}/>}</section>;
}
