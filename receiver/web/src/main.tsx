import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Activity, Bell, Camera, CircleHelp, LogOut, Monitor, Radio, RefreshCw, Search, Settings, ShieldCheck, Users } from 'lucide-react';
import { eventLabel, formatTime, mergeDevices, timeStandardLabel, typeLabel, websocketURL, type Ack, type AlarmTimeZone, type Alert, type Device, type Notifier, type Scope, type Stream, type Target, type TimeStandard } from './protocol';
import { useRelay } from './useRelay';
import { accountRequest, AccountRequestError, parseLogin, rotateLogin, type Login } from './account';
import { AccountManagement } from './AccountManagement';
import { AppearanceSelector, useAppearance } from './appearance';
import { Parameters, type ParameterDrafts } from './Parameters';
import './style.css';

type Page = '节点' | '事件' | '通知范围' | '设置' | '账号管理';
function App() {
  const appearance = useAppearance();
  const parameterDrafts = useRef<ParameterDrafts>(new Map());
  const [login, setLogin] = useState<Login | null>(null);
  const loginRef = useRef<Login | null>(null);
  const refreshFlight = useRef<Promise<Login | null> | null>(null);
  function updateLogin(value: Login | null) { loginRef.current = value; setLogin(value); }
  const [loginError, setLoginError] = useState('');
  const relay = useRelay(login);
  const [page, setPage] = useState<Page>('节点');
  const [selected, setSelected] = useState('');
  const [event, setEvent] = useState<Alert | null>(null);
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  function clearSession(error = '') { parameterDrafts.current.clear(); updateLogin(null); setLoginError(error); setEvent(null); setSelected(''); setSearch(''); setTypeFilter(''); setPage('节点'); }
  useEffect(() => { if (relay.authExpired && loginRef.current?.token === login?.token) clearSession('登录已失效，请重新登录'); }, [relay.authExpired, login?.token]);
  useEffect(() => {
    if (!login) return;
    let active = true;
    const delay = Math.min(24 * 60 * 60 * 1000, Math.max(1000, Date.parse(login.expiresAt) - Date.now() - 60_000));
    const timer = setTimeout(() => {
      const flight = rotateLogin(login, replacement => {
        if (!active || loginRef.current?.token !== login.token) return false;
        updateLogin(replacement); return true;
      }, relay.suspend);
      refreshFlight.current = flight;
      void flight.catch(() => { if (active && loginRef.current?.token === login.token) clearSession('登录已过期，请重新登录'); });
      const complete = () => { if (refreshFlight.current === flight) refreshFlight.current = null; };
      void flight.then(complete, complete);
    }, delay);
    return () => { active = false; clearTimeout(timer); };
  }, [login]);
  async function logout() {
    const current = loginRef.current, pending = refreshFlight.current;
    relay.suspend(); clearSession();
    const results = await Promise.allSettled([...(current ? [accountRequest('/api/account/logout', current.token, {})] : []), ...(pending ? [pending] : [])]);
    if (!loginRef.current && results.some(result => result.status === 'rejected' && !(result.reason instanceof AccountRequestError && result.reason.status === 401))) setLoginError('已退出本地会话，服务暂不可达');
  }
  const timeZone = relay.timeStandard?.timeZone ?? 'Asia/Shanghai';
  useEffect(() => { window.scrollTo({ top: 0, behavior: 'instant' }); }, [page, selected]);
  const nodes = useMemo(() => mergeDevices(relay.devices, [...relay.registered, ...relay.notifiers]), [relay.devices, relay.registered, relay.notifiers]);
  const visibleNodes = nodes.filter(n => (!typeFilter || n.nodeType === typeFilter) && `${n.deviceName} ${n.deviceId}`.toLowerCase().includes(search.trim().toLowerCase()));
  const node = visibleNodes.find(d => d.deviceId === selected) ?? visibleNodes[0];
  const visibleEvent = event && (relay.alerts.find(a => a.alertId === event.alertId) ?? event);
  if (!login) return <><div className="login-appearance"><AppearanceSelector preference={appearance}/></div><LoginScreen error={loginError} onLogin={value => { updateLogin(value); setLoginError(''); setPage('节点'); setEvent(null); }} /></>;
  return <div className="app-shell">
    <aside className="sidebar">
      <div className="brand"><img src="/console/icon.png" width="28" height="28" alt=""/><strong>VisionGuard</strong><span>Web 控制台</span></div>
      <nav aria-label="主导航">{([['节点',Monitor],['事件',Activity],['通知范围',Bell],['设置',Settings],['账号管理',Users]] as const).filter(([label])=>label!=='账号管理'||login.account.isAdmin).map(([label,Icon]) => <button key={label} aria-current={page === label ? 'page' : undefined} className={page === label ? 'nav-item active' : 'nav-item'} onClick={() => { setPage(label); }}><Icon size={20}/><span>{label}</span></button>)}</nav>
      <div className="sidebar-footer"><span className={'dot '+(relay.connected ? 'online' : '')}/>{relay.status}<span className="subtle">{login.account.username} · {login.account.isAdmin ? '管理员' : '普通账号'}</span></div>
    </aside>
    <main>
      <header className="page-header"><div><h1>{page}</h1><p>{({节点:'检测节点与通知节点',事件:'实时事件与最近历史',通知范围:'为每个通知节点分配接收范围',设置:'当前会话与连接信息',账号管理:'账号启用、密码与管理员权限'} as Record<Page,string>)[page]}</p></div><div className="toolbar"><span className="connection" role="status"><span className={'dot '+(relay.connected ? 'online' : '')}/>{relay.status}</span><button className="icon-button" aria-label="刷新" onClick={relay.refresh} disabled={!relay.connected}><RefreshCw size={20}/></button></div></header>
      {page === '节点' && <><section className="panel node-filters"><label className="search-field"><Search size={20}/><input aria-label="搜索节点名称" placeholder="搜索节点名称" value={search} onChange={e => setSearch(e.target.value)}/></label><select aria-label="节点类型" value={typeFilter} onChange={e => setTypeFilter(e.target.value)}><option value="">全部类型</option><option value="visual">视觉节点</option><option value="sensor">传感器节点</option><option value="notification">通知节点</option></select></section><div className="master-detail">
        <section className="panel node-list"><div className="section-title"><h2>全部节点</h2><span className="subtle">{visibleNodes.length} 个</span></div><div className="node-table-heading" aria-hidden="true"><span>节点</span><span>类型 / 平台</span><span>连接</span><span>工作状态</span><span>详情</span></div>
          {visibleNodes.length === 0 && <Empty text={nodes.length ? '没有匹配的节点' : '尚无已登记节点'} />}
          {visibleNodes.map(device => <button key={device.deviceId} aria-pressed={node?.deviceId === device.deviceId} className={'node-row '+(node?.deviceId === device.deviceId ? 'selected' : '')} onClick={() => { setSelected(device.deviceId); }}>
            <span className="node-identity"><NodeIcon node={device}/><span><strong>{device.deviceName}</strong><small>{device.deviceId}</small></span></span><span className="node-platform">{typeLabel(device.nodeType)} · {device.platform}</span><span><span className={'dot '+(device.online ? 'online' : '')}/>{device.online ? '在线' : '离线'}</span><span className="node-work">{!device.online ? '等待重新连接' : device.component === 'android-camera' ? (relay.streams.find(s=>s.publisherDeviceId===device.deviceId)?.isStreaming ? '正在推流' : '等待推流') : device.nodeType === 'notification' ? '通知服务已连接' : device.isMonitoring ? '检测中' : device.isReady ? '已就绪' : '未检测'}</span><span className="node-open">{node?.deviceId === device.deviceId ? '当前节点' : '查看'}</span>
          </button>)}
        </section>
        <div className="detail-column">
          {node ? <><NodeDetail key={node.deviceId} node={node} drafts={parameterDrafts.current} acks={relay.acks} stream={relay.streams.find(s=>s.publisherDeviceId===node.deviceId)} timeZone={timeZone} connected={relay.connected} send={relay.send} /><DeviceSettings key={`manage-${node.deviceId}`} node={node} nodes={nodes} streams={relay.streams} login={login} onChanged={relay.refresh}/></> : <section className="panel"><Empty text="登录同一账号的节点将在这里显示"/></section>}
          <section className="panel"><div className="section-title"><h2>操作回执</h2><span className="subtle">以执行端结果为准</span></div>
            {relay.acks.length === 0 ? <Empty text="暂无操作"/> : relay.acks.slice(0,5).map(ack => <div className="receipt-row" key={ack.requestId}><span>{ack.targetDeviceId || '通知范围'}<small>{ack.command}</small></span><span className={!ack.success && ack.phase !== 'uncertain' ? 'error' : 'subtle'}>{ack.phase === 'uncertain' ? '结果待核实' : !ack.success ? '失败' : ack.phase === 'forwarded' ? '已转发' : ack.phase === 'pending' ? '等待执行' : '已完成'}<small>{ack.reason}</small></span></div>)}
          </section>
        </div>
      </div></>}
      {page === '事件' && <section className="panel"><div className="section-title"><h2>最近事件</h2><span className="subtle">{timeStandardLabel(timeZone)} · 最多 100 条</span></div>{relay.alerts.length === 0 ? <Empty text="暂无事件"/> : <div className="event-table">{relay.alerts.map(a => <button className="event-row" key={a.alertId} onClick={() => setEvent(a)}><span className="event-kind">{eventLabel(a.eventKind)}</span><span><strong>{a.deviceName || a.deviceId}{a.sourceName && ` · ${a.sourceName}`}</strong><small>{a.summary || '查看检测详情'}</small></span><span className="subtle">{formatTime(a.timestamp,timeZone)}<small>{(relay.receipts[a.alertId] ?? []).length > 0 ? `${relay.receipts[a.alertId].length} 个通知节点已收件` : '查看详情'}</small></span></button>)}</div>}</section>}
      {page === '通知范围' && <div className="scope-list">{relay.notifiers.length === 0 ? <section className="panel"><Empty text="尚无已登记的通知节点"/></section> : relay.notifiers.map(notifier => <ScopeEditor key={notifier.deviceId} notifier={notifier} nodes={nodes.filter(n => n.role === 'detector')} connected={relay.connected} acks={relay.acks} send={relay.send}/>)}</div>}
      {page === '设置' && <div className="settings-grid"><section className="panel"><h2>外观</h2><AppearanceSelector preference={appearance}/></section><TimeStandardSettings standard={relay.timeStandard} connected={relay.connected} acks={relay.acks} send={relay.send}/><AccountSettings login={login} onDeviceNameChanged={name=>{if(loginRef.current?.token===login.token)updateLogin({...login,device:{...login.device,deviceName:name}});}} onLogout={() => { void logout(); }} onPasswordChanged={() => { if (loginRef.current?.token === login.token) clearSession('密码已修改，请重新登录'); }}/></div>}
      {page === '账号管理' && login.account.isAdmin && <AccountManagement login={login}/>}
    </main>
    {visibleEvent && <EventDialog key={visibleEvent.alertId} alert={visibleEvent} timeZone={timeZone} login={login} receipts={relay.receipts[visibleEvent.alertId] ?? []} onClose={() => setEvent(null)}/>}
  </div>;
}
function TimeStandardSettings({standard,connected,acks,send}:{standard:TimeStandard|null;connected:boolean;acks:Ack[];send:(m:Record<string,unknown>)=>string}) {
  const [draft,setDraft] = useState<AlarmTimeZone>(standard?.timeZone ?? 'Asia/Shanghai');
  const [requestId,setRequestId] = useState('');
  useEffect(() => { if (!connected) setRequestId(''); }, [connected]);
  useEffect(() => { if (standard) setDraft(standard.timeZone); },[standard?.timeZone]);
  const result = acks.find(ack => ack.requestId === requestId);
  const saving = result?.phase === 'pending' || result?.phase === 'forwarded';
  return <section className="panel settings-panel"><h2>统一时间</h2><p className="subtle">本账号的告警列表、详情与通知弹窗使用同一时间标准。</p>
    <dl><dt>当前标准</dt><dd>{standard ? timeStandardLabel(standard.timeZone) : '等待服务响应'}</dd><dt>服务参考时间</dt><dd>{standard ? formatTime(standard.serverTime,standard.timeZone) : '—'}</dd></dl>
    <form onSubmit={e => { e.preventDefault(); setRequestId(send({type:'set-time-standard',timeZone:draft})); }}><label>告警时间标准<select aria-label="告警时间标准" value={draft} onChange={e => setDraft(e.target.value as AlarmTimeZone)} disabled={!connected || !standard || saving}><option value="Asia/Shanghai">北京时间（UTC+8）</option><option value="UTC">UTC（UTC+0）</option></select></label><button className="button" type="submit" disabled={!connected || !standard || saving}>{saving ? '正在保存…' : '保存标准'}</button></form>
    {result && <p role={saving || result.success ? 'status' : 'alert'} className={saving || result.success ? 'subtle' : 'error'}>{saving ? '正在保存…' : result.success ? '时间标准已保存' : result.reason || '保存失败'}</p>}
  </section>;
}
function LoginScreen({onLogin,error}:{onLogin:(value:Login)=>void;error:string}) {
  const [username,setUsername] = useState(''); const [password,setPassword] = useState('');
  const [busy,setBusy] = useState(false); const [failure,setFailure] = useState('');
  async function submit() { setBusy(true); setFailure(''); try { websocketURL(location.origin); const value = await accountRequest('/api/account/login', undefined, {username:username.trim(),password,component:'web-console',deviceCode:navigator.platform || 'Web'}); onLogin(parseLogin(value)); setPassword(''); } catch (e) { setFailure((e as Error).message); } finally { setBusy(false); } }
  return <div className="login-page"><form className="panel login-form" onSubmit={e => { e.preventDefault(); void submit(); }}>
    <div className="brand"><img src="/console/icon.png" width="40" height="40" alt=""/><span>控制台</span></div><h1>登录账号</h1><p>查看和管理同一账号下的设备</p>
    {(failure || error) && <p className="error" role="alert">{failure || error}</p>}
    <label>账号<input required maxLength={64} autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} disabled={busy}/></label>
    <label>密码<input required type="password" maxLength={256} autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} disabled={busy}/></label>
    <button className="button" type="submit" disabled={busy}><ShieldCheck size={20}/>{busy ? '登录中…' : '登录'}</button><small className="subtle">设备登录同一账号后自动关联。</small>
  </form></div>;
}
function AccountSettings({login,onLogout,onPasswordChanged,onDeviceNameChanged}:{login:Login;onLogout:()=>void;onPasswordChanged:()=>void;onDeviceNameChanged:(name:string)=>void}) {
  const [deviceName,setDeviceName] = useState(login.device.deviceName); const [nameBusy,setNameBusy] = useState(false); const [nameMessage,setNameMessage] = useState(''); const [nameError,setNameError] = useState('');
  useEffect(()=>setDeviceName(login.device.deviceName),[login.device.deviceName]);
  async function renameSelf() { setNameBusy(true);setNameMessage('');setNameError('');try { const name=deviceName.trim();await accountRequest(`/api/devices/${encodeURIComponent(login.device.deviceId)}`,login.token,{deviceName:name},'PATCH');onDeviceNameChanged(name);setNameMessage('名称已保存'); } catch(e) { setNameError((e as Error).message); } finally { setNameBusy(false); } }
  const [currentPassword,setCurrentPassword] = useState(''); const [newPassword,setNewPassword] = useState('');
  const [busy,setBusy] = useState(false); const [error,setError] = useState('');
  async function changePassword() { setBusy(true); setError(''); try { await accountRequest('/api/account/password',login.token,{currentPassword,newPassword}); setCurrentPassword(''); setNewPassword(''); onPasswordChanged(); } catch(e) { setError((e as Error).message); } finally { setBusy(false); } }
  return <section className="panel settings-panel"><h2>账号</h2><dl><dt>当前账号</dt><dd>{login.account.username}</dd><dt>服务地址</dt><dd>{location.origin}</dd></dl><form onSubmit={e=>{e.preventDefault();void renameSelf();}}><h3>本机名称</h3><label>设备名称<input required maxLength={64} value={deviceName} onChange={e=>setDeviceName(e.target.value)} disabled={nameBusy}/></label><button className="button secondary" disabled={nameBusy||!deviceName.trim()}>{nameBusy?'保存中…':'保存名称'}</button>{nameMessage&&<p role="status">{nameMessage}</p>}{nameError&&<p className="error" role="alert">{nameError}</p>}</form><button className="button secondary" onClick={onLogout}><LogOut size={20}/>退出登录</button><form className="account-password" onSubmit={e=>{e.preventDefault();void changePassword();}}><h3>修改密码</h3><label>当前密码<input required type="password" autoComplete="current-password" value={currentPassword} onChange={e=>setCurrentPassword(e.target.value)} disabled={busy}/></label><label>新密码<input required type="password" minLength={8} maxLength={256} autoComplete="new-password" value={newPassword} onChange={e=>setNewPassword(e.target.value)} disabled={busy}/></label><p className="subtle">修改后，本账号的设备需要重新登录。</p><button className="button" disabled={busy}>{busy?'保存中…':'修改密码'}</button>{error&&<p className="error" role="alert">{error}</p>}</form></section>;
}
function DeviceSettings({node,nodes,streams,login,onChanged}:{node:Device;nodes:Device[];streams:Stream[];login:Login;onChanged:()=>void}) {
  const [name,setName] = useState(node.deviceName); const [busy,setBusy] = useState(false); const [message,setMessage] = useState(''); const [error,setError] = useState('');
  const [confirmUnbind,setConfirmUnbind] = useState(false);
  const stream = streams.find(s=>s.publisherDeviceId===node.deviceId);
  const [target,setTarget] = useState(stream?.targetDeviceId??'');
  useEffect(()=>setTarget(stream?.targetDeviceId??''),[stream?.targetDeviceId]);
  useEffect(()=>setName(node.deviceName),[node.deviceName]);
  const camera = node.component==='android-camera'||node.capabilities.includes('video-publish');
  const targets = nodes.filter(n=>n.component==='windows-inference'||n.capabilities.includes('video-subscribe'));
  async function mutate(path:string,body:unknown,method?:string) { setBusy(true);setError('');setMessage('');try{await accountRequest(path,login.token,body,method);setMessage(method==='DELETE'?'设备已解绑，需要重新登录':'已保存');onChanged();}catch(e){setError((e as Error).message);}finally{setBusy(false);} }
  return <section className="panel device-management" aria-busy={busy}><details><summary>设备管理 · 身份与关联</summary><form className="device-form" onSubmit={e=>{e.preventDefault();void mutate(`/api/devices/${encodeURIComponent(node.deviceId)}`,{deviceName:name.trim()},'PATCH');}}><label>设备名称<input required maxLength={64} value={name} onChange={e=>setName(e.target.value)} disabled={busy}/></label><button className="button secondary" disabled={busy||!name.trim()}>保存名称</button></form>{camera&&<form className="device-form" onSubmit={e=>{e.preventDefault();void mutate('/api/streams/bind',{publisherDeviceId:node.deviceId,targetDeviceId:target});}}><label>推理节点<select value={target} onChange={e=>setTarget(e.target.value)} disabled={busy}><option value="">选择推理节点</option>{targets.map(t=><option key={t.deviceId} value={t.deviceId}>{t.deviceName}{t.online?'':'（离线）'}</option>)}</select></label><button className="button" disabled={busy||!target}>关联来源</button><p className="subtle">{stream?.isStreaming?'正在推流':stream?.targetDeviceId?'已关联，等待推流':'只有一个推理节点时自动关联'}</p></form>}<button className="text-button danger" disabled={busy} onClick={()=>setConfirmUnbind(true)}>解绑设备</button>{confirmUnbind&&<ConfirmDialog title="解绑设备" description={`解绑“${node.deviceName}”后，该设备需要重新登录。事件记录会保留。`} confirmText="解绑" onClose={()=>setConfirmUnbind(false)} onConfirm={()=>{setConfirmUnbind(false);void mutate(`/api/devices/${encodeURIComponent(node.deviceId)}`,undefined,'DELETE');}}/>}{busy&&<p className="subtle" role="status">正在处理…</p>}{message&&<p role="status">{message}</p>}{error&&<p className="error" role="alert">{error}</p>}</details></section>;
}
function NodeIcon({node}:{node:Device}) { const Icon = node.nodeType === 'visual' ? Camera : node.nodeType === 'notification' ? Bell : Radio; return <span className="node-icon"><Icon size={24}/></span>; }
function NodeDetail({node,stream,connected,send,timeZone,acks,drafts}:{node:Device;stream?:Stream;connected:boolean;send:(m:Record<string,unknown>)=>string;timeZone:AlarmTimeZone;acks:Ack[];drafts:ParameterDrafts}) {
  const [sourceId,setSourceId] = useState('');
  const source = node.sources.find(s => s.sourceId === sourceId);
  const can = (value:string) => node.capabilities.includes(value);
  const ready = connected && node.online;
  const sendCommand = (command:string) => send({type:'command',targetDeviceId:node.deviceId,command,...(source ? {targetSourceId:source.sourceId} : {})});
  return <><section className="panel node-summary"><div className="section-title"><div><h2>{node.deviceName}</h2><p>{typeLabel(node.nodeType)} · {node.platform} · {node.deviceId}</p></div><span className={'status-tag '+(node.online ? 'good' : '')}>{node.online ? '在线' : '离线'}</span></div>
    <div className="node-status"><span><span className={'dot '+((stream?.isStreaming ?? node.isMonitoring) ? 'online' : '')}/>{node.component==='android-camera' ? (stream?.isStreaming?'推流中':stream?.stopReason==='connection-lost'||stream?.stopReason==='frame-stalled'?'推流中断':'未推流') : node.nodeType === 'notification' ? (node.online ? '通知服务已连接' : '通知服务未连接') : node.isMonitoring ? '检测中' : node.isReady ? '已就绪' : '未检测'}</span>{node.lastSeen && <span className="subtle">最近响应 {formatTime(node.lastSeen,timeZone)}</span>}</div>
    {node.components && <div className="component-line">{Object.entries(node.components).map(([key,value]) => <span key={key}>{['resident','residentApp'].includes(key) ? '驻留' : ['detector','detectorApp'].includes(key) ? '视觉节点' : key === 'camera' ? '镜头' : key}：{value === 'running' ? '运行中' : value === 'stopped' ? '已停止' : value}</span>)}</div>}
    <div className="actions">{can('monitor-control') && (!source || can('source-control')) && <button className="button" disabled={!ready} onClick={() => sendCommand((source?.isMonitoring ?? node.isMonitoring) ? 'pause' : 'resume')}>{(source?.isMonitoring ?? node.isMonitoring) ? '暂停检测' : '开始检测'}</button>}
      {can('monitor-control') && (!source || can('source-control')) && <button className="button secondary" disabled={!ready} onClick={() => sendCommand('stop-alarm')}>停止报警</button>}
      {can('app-lifecycle-control') && <><button className="button secondary" disabled={!ready} onClick={() => send({type:'command',targetDeviceId:node.deviceId,command:'open-detector'})}>打开视觉节点</button><button className="button secondary" disabled={!ready} onClick={() => send({type:'command',targetDeviceId:node.deviceId,command:'close-detector'})}>关闭视觉节点</button></>}
    </div>{!node.online && <p className="subtle">离线时保留登记身份和本次编辑草稿；参数将在重新连接后显示。</p>}
    {!can('monitor-control') && node.online && node.component!=='android-camera' && <p className="subtle">此节点未提供检测启停能力。</p>}
  </section>
  {node.nodeType === 'visual' && node.component!=='android-camera' && <section className="panel source-panel">{node.sourceLimitExceeded && <p className="error">来源数量超过服务端上限（最多 {node.maxSources ?? '未报告'} 路），当前列表为上一次成功上报的快照。</p>}<div className="section-title"><h2>检测来源</h2><button className="text-button" onClick={() => setSourceId('')} disabled={!sourceId}>节点整体</button></div>{node.sources.length === 0 ? <Empty text="暂无已连接来源"/> : <div className="source-table-wrap"><table className="source-table"><thead><tr><th>名称</th><th>状态</th><th>帧率（FPS）</th><th>操作</th></tr></thead><tbody>{node.sources.map(s => <tr className={sourceId === s.sourceId ? 'selected' : ''} key={s.sourceId}><td><strong>{s.sourceName || s.sourceId}</strong><small>{s.sourceId}</small></td><td className="source-state"><span className={'dot '+(!node.online ? '' : s.error ? 'error' : !s.isReady ? 'warning' : s.isMonitoring ? 'online' : '')}/>{!node.online ? '离线' : s.error ? '异常' : !s.isReady ? '未就绪' : s.isMonitoring ? '检测中' : '已暂停'}{s.error && <details className="source-error"><summary>查看原因</summary><p className="error">{s.error}</p></details>}</td><td>{!node.online || s.actualFps === undefined ? '—' : s.actualFps.toFixed(1)}</td><td><div className="source-actions">{can('source-control') && can('monitor-control') && <button className="text-button" disabled={!ready} aria-label={`${s.isMonitoring ? '暂停' : '开始'} ${s.sourceName}`} onClick={() => send({type:'command',targetDeviceId:node.deviceId,targetSourceId:s.sourceId,command:s.isMonitoring ? 'pause' : 'resume'})}>{s.isMonitoring ? '暂停' : '开始'}</button>}{can('config-control') && can('source-control') && <button className="text-button" aria-label={`参数 ${s.sourceName}`} onClick={() => setSourceId(s.sourceId)} disabled={s.isMonitoring}><Settings size={16}/>参数</button>}</div></td></tr>)}</tbody></table></div>}</section>}
  {can('config-control') && <Parameters key={`${node.deviceId}/${source?.sourceId ?? ''}`} node={node} source={source} acks={acks} drafts={drafts} disabled={!ready || (!!source && (!can('source-control') || source.isMonitoring)) || (!source && node.nodeType === 'visual' && node.isMonitoring)} send={send}/>}
  </>;
}
function ScopeEditor({notifier,nodes,connected,acks,send}:{notifier:Notifier;nodes:Device[];connected:boolean;acks:Ack[];send:(m:Record<string,unknown>)=>string}) {
  const [scope,setScope] = useState<Scope>(notifier.scope);
  const [requestId,setRequestId] = useState('');
  useEffect(() => { if (!connected) setRequestId(''); }, [connected]);
  const serverScope = JSON.stringify(notifier.scope);
  useEffect(() => { setScope(notifier.scope); }, [serverScope]);
  const result = acks.find(ack => ack.requestId === requestId);
  const saving = result?.phase === 'pending' || result?.phase === 'forwarded';
  const [offlineDevice,setOfflineDevice] = useState('');
  const [offlineSource,setOfflineSource] = useState('');
  const offlineSourceHintId = React.useId();
  const validOfflineSource = /^[A-Za-z0-9_-]{1,64}$/.test(offlineSource);
  const invalidOfflineSource = !!offlineSource && !validOfflineSource;
  const contains = (target:Target) => scope.targets.some(t => t.deviceId === target.deviceId && t.sourceId === target.sourceId);
  function updateScope(value:Scope) { setScope(value); setRequestId(''); }
  function toggle(target:Target) { updateScope({...scope,targets:contains(target) ? scope.targets.filter(t => !(t.deviceId === target.deviceId && t.sourceId === target.sourceId)) : [...scope.targets,target]}); }
  return <section className="panel scope-editor"><div className="section-title"><div><h2>{notifier.deviceName}</h2><p>{notifier.deviceId}</p></div><span className={'status-tag '+(notifier.online ? 'good' : '')}>{notifier.online ? '在线' : '离线'}</span></div><fieldset disabled={!connected || saving}><legend>接收范围</legend><div className="radio-options"><label><input type="radio" name={`scope-${notifier.deviceId}`} checked={scope.mode === 'all'} onChange={() => updateScope({mode:'all',targets:[]})}/>全部节点</label><label><input type="radio" name={`scope-${notifier.deviceId}`} checked={scope.mode === 'selected'} onChange={() => updateScope({mode:'selected',targets:[]})}/>指定节点与来源</label></div>
    {scope.mode === 'selected' && <div className="scope-targets">{nodes.map(n => <div className="scope-node" key={n.deviceId}><label><input type="checkbox" checked={contains({deviceId:n.deviceId})} onChange={() => toggle({deviceId:n.deviceId})}/>{n.deviceName}<small>{typeLabel(n.nodeType)} · {n.online ? '在线' : '离线'}</small></label>{n.nodeType === 'visual' && [...new Map([...scope.targets.filter(t => t.deviceId === n.deviceId && t.sourceId).map(t => ({sourceId:t.sourceId!,sourceName:t.sourceId!})),...n.sources.map(s => ({sourceId:s.sourceId,sourceName:s.sourceName}))].map(s => [s.sourceId,s])).values()].map(s => <label className="source-check" key={s.sourceId}><input type="checkbox" checked={contains({deviceId:n.deviceId,sourceId:s.sourceId})} onChange={() => toggle({deviceId:n.deviceId,sourceId:s.sourceId})}/>{s.sourceName || s.sourceId}</label>)}</div>)}
      <div className="offline-source"><label>离线视觉来源<select value={offlineDevice} aria-describedby={offlineSourceHintId} onChange={e => setOfflineDevice(e.target.value)}><option value="">选择视觉节点</option>{nodes.filter(n => n.nodeType === 'visual' && !n.online).map(n => <option key={n.deviceId} value={n.deviceId}>{n.deviceName}</option>)}</select></label><label>来源 ID<input value={offlineSource} pattern={'[A-Za-z0-9_\\-]{1,64}'} aria-describedby={offlineSourceHintId} aria-invalid={invalidOfflineSource} maxLength={64} onChange={e => setOfflineSource(e.target.value)}/></label><button className="button secondary" type="button" disabled={!offlineDevice || !validOfflineSource || contains({deviceId:offlineDevice,sourceId:offlineSource})} onClick={() => { toggle({deviceId:offlineDevice,sourceId:offlineSource}); setOfflineSource(''); }}>添加</button></div>
      <p id={offlineSourceHintId} className={invalidOfflineSource ? 'error' : 'subtle'} role={invalidOfflineSource ? 'alert' : undefined}>{invalidOfflineSource ? '来源 ID 只能使用字母、数字、下划线或短横线，最多 64 个字符。' : '选择离线节点，填写 1–64 个字母、数字、下划线或短横线组成的来源 ID，点击添加加入范围。'}</p>
      {scope.targets.length === 0 && <p className="subtle">未勾选时不接收检测事件，节点仍独立监测服务响应。</p>}
    </div>}
    <button className="button" onClick={() => setRequestId(send({type:'set-notification-scope',targetNotifierId:notifier.deviceId,scope}))} disabled={scope.targets.length > 100}>{saving ? '正在保存…' : '保存接收范围'}</button></fieldset>{result && <p role={saving || result.success ? 'status' : 'alert'} className={saving || result.success ? 'subtle' : 'error'}>{saving ? '正在保存接收范围…' : result.success ? '接收范围已保存' : result.reason || '保存接收范围失败'}</p>}<p className="subtle">范围保存在服务端，离线节点下次连接时读取。收件确认表示报警已保存。</p>
  </section>;
}
function EventDialog({alert,login,receipts,onClose,timeZone}:{alert:Alert;login:Login;receipts:string[];onClose:()=>void;timeZone:AlarmTimeZone}) {
  const [image,setImage] = useState<string | null>(null);
  const [imageError,setImageError] = useState('');
  const titleId = React.useId();
  React.useEffect(() => {
    setImage(null); setImageError('');
    if (!alert.hasScreenshot || !alert.screenshotUrl) return;
    let url: URL;
    try { url = new URL(alert.screenshotUrl,location.origin); } catch { setImageError('截图地址无效'); return; }
    if (url.origin !== location.origin || !url.pathname.startsWith('/screenshots/')) { setImageError('截图地址无效'); return; }
    const controller = new AbortController(); let objectURL = '';
    void fetch(url, {headers:{Authorization:`Bearer ${login.token}`},signal:controller.signal,cache:'no-store'}).then(async r => { if (!r.ok) throw new Error(); const blob = await r.blob(); if (!blob.type.startsWith('image/')) throw new Error(); if (!controller.signal.aborted) { objectURL = URL.createObjectURL(blob); setImage(objectURL); } }).catch(() => { if (!controller.signal.aborted) setImageError('截图暂不可用'); });
    return () => { controller.abort(); if (objectURL) URL.revokeObjectURL(objectURL); };
  },[alert.alertId,alert.hasScreenshot,alert.screenshotUrl,login.token]);
  const dialog = useModalDialog();
  return <dialog className="event-drawer" ref={dialog} aria-labelledby={titleId} onCancel={onClose} onClick={e => { if(e.target === e.currentTarget) onClose(); }}><div className="dialog-body"><div className="section-title"><h2 id={titleId}>{eventLabel(alert.eventKind)}</h2><button className="text-button" autoFocus onClick={onClose}>关闭</button></div><dl><dt>节点</dt><dd>{alert.deviceName || alert.deviceId}</dd>{alert.sourceId && <><dt>来源</dt><dd>{alert.sourceName || alert.sourceId}</dd></>}<dt>时间</dt><dd>{formatTime(alert.timestamp,timeZone)}</dd><dt>时间标准</dt><dd>{timeStandardLabel(timeZone)}</dd><dt>摘要</dt><dd>{alert.summary || '无文字摘要'}</dd><dt>收件</dt><dd>{receipts.length ? receipts.join('、') : '未观测到本会话收件回执'}</dd></dl>{alert.detections?.map((d,i) => <p key={i}>{d.label} · {Math.round(d.confidence*100)}%</p>)}{image && <img className="event-image" src={image} alt="事件截图" onError={()=>{setImage(null);setImageError('截图无法显示');}}/>}{alert.hasScreenshot && !image && !imageError && <p role="status">{alert.screenshotUrl ? '正在加载截图…' : '等待截图地址…'}</p>}{!alert.hasScreenshot && <p className="subtle">此事件没有截图</p>}{imageError && <p className="error" role="alert">{imageError}</p>}<small className="subtle">历史记录用于查看；过期事件不会重新通知。</small></div></dialog>;
}
function useModalDialog() {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current;
    const previous = document.activeElement;
    element?.showModal();
    return () => { element?.close(); if (previous instanceof HTMLElement && previous.isConnected) previous.focus(); };
  }, []);
  return dialog;
}
function ConfirmDialog({title,description,confirmText,onClose,onConfirm}:{title:string;description:string;confirmText:string;onClose:()=>void;onConfirm:()=>void}) {
  const dialog = useModalDialog();
  const titleId = React.useId();
  const descriptionId = React.useId();
  return <dialog ref={dialog} aria-labelledby={titleId} aria-describedby={descriptionId} onCancel={onClose} onClick={e=>{if(e.target===e.currentTarget)onClose();}}><div className="dialog-body"><h2 id={titleId}>{title}</h2><p id={descriptionId}>{description}</p><div className="dialog-actions"><button className="button secondary" autoFocus onClick={onClose}>取消</button><button className="button danger" onClick={onConfirm}>{confirmText}</button></div></div></dialog>;
}
function Empty({text}:{text:string}) { return <div className="empty"><CircleHelp size={24}/><span>{text}</span></div>; }

createRoot(document.getElementById('root')!).render(<App/>);
