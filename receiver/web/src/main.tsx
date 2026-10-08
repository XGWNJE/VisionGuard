import { NodeCacheMaintenance, ServerCacheMaintenance } from './CacheMaintenance';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Activity, ArrowLeft, Bell, BellOff, Camera, ChevronRight, CircleHelp, Clock, LogOut, Monitor, Pause, Play, Radio, RefreshCw, Search, Settings, ShieldCheck, Square, Users, X } from 'lucide-react';
import { eventLabel, formatTime, isWithinEventTime, mergeDevices, parseEventTime, timeStandardLabel, typeLabel, websocketURL, type Ack, type AlarmTimeZone, type Alert, type Device, type Notifier, type Scope, type Stream, type Target, type TimeStandard } from './protocol';
import { useRelay } from './useRelay';
import { accountRequest, AccountRequestError, browserDeviceIdentity, browserDeviceModel, normalizeDisplayName, parseLogin, type Login } from './account';
import { AccountManagement } from './AccountManagement';
import { clearRememberedLogin, readRememberedLogin, saveRememberedLogin } from './rememberedLogin';
import { clearSessionLogin, restoreSessionLogin, saveSessionLogin } from './sessionLogin';
import { AppearanceSelector, useAppearance } from './appearance';
import { Parameters, type ParameterDrafts } from './Parameters';
import { RemoteSettings } from './RemoteSettings';
import { WorkspaceTabs } from './WorkspaceTabs';
import './style.css';

function checkNamePaste(e: React.ClipboardEvent<HTMLInputElement>, onError: (message: string) => void) {
  const input = e.currentTarget;
  const next = input.value.slice(0,input.selectionStart ?? 0) + e.clipboardData.getData('text') + input.value.slice(input.selectionEnd ?? input.value.length);
  try { normalizeDisplayName(next); if(next.length>64)throw new Error('名称最多 64 个字符'); }
  catch(error){ e.preventDefault(); onError((error as Error).message); }
}

type Page = '节点' | '事件' | '通知范围' | '设置' | '账号管理';
function App() {
  const appearance = useAppearance();
  const parameterDrafts = useRef<ParameterDrafts>(new Map());
  const [login, setLogin] = useState<Login | null>(null);
  const loginRef = useRef<Login | null>(null);
  const [restoringSession, setRestoringSession] = useState(true);
  function updateLogin(value: Login | null) { loginRef.current = value; setLogin(value); }
  const [loginError, setLoginError] = useState('');
  const relay = useRelay(login);
  const [page, setPage] = useState<Page>('节点');
  const [settingsSection,setSettingsSection] = useState<'account'|'general'|'cache'>('account');
  const [selected, setSelected] = useState('');
  const [event, setEvent] = useState<Alert | null>(null);
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [onlineOnly,setOnlineOnly] = useState(false);
  const [nodeDetailOpen,setNodeDetailOpen] = useState(false);
  const [eventSearch,setEventSearch] = useState('');
  const [eventKind,setEventKind] = useState('');
  const [eventStart,setEventStart] = useState('');
  const [eventEnd,setEventEnd] = useState('');
  const [scopeSelected,setScopeSelected]=useState('');
  function clearSession(error = '') { void clearSessionLogin().catch(() => setLoginError('无法清除保存的登录会话，请检查浏览器站点存储权限')); parameterDrafts.current.clear(); updateLogin(null); setLoginError(error); setEvent(null); setSelected(''); setSearch(''); setTypeFilter(''); setOnlineOnly(false); setEventSearch(''); setEventKind(''); setEventStart(''); setEventEnd(''); setNodeDetailOpen(false); setScopeSelected(''); setSettingsSection('account'); setPage('节点'); }
  useEffect(() => { if (relay.authExpired && loginRef.current?.token === login?.token) clearSession('登录已失效，请重新登录'); }, [relay.authExpired, login?.token]);
  useEffect(() => {
    let active = true;
    const abort = new AbortController(), timer = setTimeout(() => abort.abort(), 10_000);
    void restoreSessionLogin(abort.signal).then(restored => {
      if (active && restored && Date.parse(restored.expiresAt) > Date.now()) updateLogin(restored);
    }).catch(() => { if (active) setLoginError('无法恢复登录会话，请检查浏览器站点存储权限'); })
      .finally(() => { clearTimeout(timer); if (active) setRestoringSession(false); });
    return () => { active = false; clearTimeout(timer); abort.abort(); };
  }, []);
  useEffect(() => {
    if (!login) return;
    const expire = () => {
      if (loginRef.current?.token === login.token && Date.parse(login.expiresAt) <= Date.now()) {
        relay.suspend(); clearSession('登录已满 24 小时，请重新登录');
        void accountRequest('/api/account/logout', login.token, {}).catch(() => {});
      }
    };
    const timer = setTimeout(expire, Math.max(0, Date.parse(login.expiresAt) - Date.now()));
    window.addEventListener('focus', expire); document.addEventListener('visibilitychange', expire);
    return () => { clearTimeout(timer); window.removeEventListener('focus', expire); document.removeEventListener('visibilitychange', expire); };
  }, [login]);
  async function logout() {
    const current = loginRef.current;
    relay.suspend(); clearSession();
    try { if (current) await accountRequest('/api/account/logout', current.token, {}); }
    catch (error) { if (!loginRef.current && !(error instanceof AccountRequestError && error.status === 401)) setLoginError('已退出本地会话，服务暂不可达'); }
  }
  const timeZone = relay.timeStandard?.timeZone ?? 'Asia/Shanghai';
  useEffect(() => { setEventStart(''); setEventEnd(''); }, [timeZone]);
  useEffect(() => { window.scrollTo({ top: 0, behavior: 'instant' }); }, [page, selected]);
  const nodes = useMemo(() => mergeDevices(relay.devices, [...relay.registered, ...relay.notifiers]), [relay.devices, relay.registered, relay.notifiers]);
  const visibleNodes = nodes.filter(n => (!typeFilter || n.nodeType === typeFilter) && (!onlineOnly || n.online) && `${n.deviceName} ${n.deviceId}`.toLowerCase().includes(search.trim().toLowerCase()));
  const node = visibleNodes.find(d => d.deviceId === selected) ?? visibleNodes[0];
  const visibleEvent = event && (relay.alerts.find(a => a.alertId === event.alertId) ?? event);
  const rangeStart = parseEventTime(eventStart,timeZone), rangeEnd = parseEventTime(eventEnd,timeZone);
  const eventTimeError = Number.isNaN(rangeStart) || Number.isNaN(rangeEnd) ? '请输入有效时间' : rangeStart !== null && rangeEnd !== null && rangeStart > rangeEnd ? '结束时间不能早于开始时间' : '';
  const filteredEvents = relay.alerts.filter(a=>!eventTimeError && isWithinEventTime(a.timestamp,rangeStart,rangeEnd) && (!eventKind || a.eventKind===eventKind) && `${a.deviceName} ${a.sourceName} ${a.summary}`.toLowerCase().includes(eventSearch.trim().toLowerCase()));
  if (restoringSession) return <div className="login-page" role="status">正在恢复登录…</div>;
  if (!login) return <><div className="login-appearance"><AppearanceSelector preference={appearance} showIcons={false}/></div><LoginScreen error={loginError} onLogin={value => { updateLogin(value); setLoginError(''); setPage('节点'); setEvent(null); }} /></>;
  return <div className={'app-shell '+(page!=='账号管理' ? 'workspace-page ' : '')+(page==='节点' ? 'nodes-page ' : '')+(page==='节点' && nodeDetailOpen ? 'node-detail-open' : '')}>
    <aside className="sidebar">

      <nav aria-label="主导航">{([['节点',Monitor],['事件',Activity],['通知范围',Bell],['设置',Settings],['账号管理',Users]] as const).filter(([label])=>label!=='账号管理'||login.account.isAdmin).map(([label,Icon]) => <button key={label} aria-current={page === label ? 'page' : undefined} className={page === label ? 'nav-item active' : 'nav-item'} onClick={() => { setPage(label); }}><Icon size={20}/><span>{label}</span></button>)}</nav>

    </aside>
    <main className={page==='设置'?'settings-page':undefined}>
      <header className="page-header"><div><span className="eyebrow">控制台 / {page}</span><h1>{({节点:'节点工作区',事件:'事件记录',通知范围:'通知范围',设置:'设置',账号管理:'账号管理'} as Record<Page,string>)[page]}</h1></div><div className="toolbar"><span className="connection" role="status"><span className={'dot '+(relay.connected ? 'online' : '')}/>{relay.status}</span><button className="icon-button" aria-label="刷新" title="刷新节点与事件" onClick={relay.refresh} disabled={!relay.connected}><RefreshCw size={18}/></button></div></header>
      {page === '节点' && <><div className="master-detail">
        <section className="panel node-list"><div className="overview-bar" aria-label="账号运行概览"><span><span>节点在线</span><strong>{nodes.filter(n=>n.online).length}<small> / {nodes.length}</small></strong></span><span><span>检测中</span><strong>{nodes.filter(n=>n.online&&n.component!=='android-camera'&&n.nodeType!=='notification'&&n.isMonitoring).length}</strong></span><span><span>推流中</span><strong>{relay.streams.filter(s=>s.isStreaming).length}</strong></span><button className="text-button" aria-label={`${relay.alerts.length} 条最近事件`} onClick={()=>setPage('事件')}><span>最近事件</span><strong>{relay.alerts.length}</strong><ChevronRight size={12}/></button></div><div className="section-title"><h2>节点目录</h2><span className="count-badge">{visibleNodes.length}</span></div><div className="directory-filters"><label className="search-field"><Search size={16}/><input aria-label="搜索节点名称" placeholder="搜索名称或设备 ID" value={search} onChange={e => setSearch(e.target.value)}/></label><div className="filter-row"><select aria-label="节点类型" value={typeFilter} onChange={e => setTypeFilter(e.target.value)}><option value="">全部类型</option><option value="visual">视觉类型（推理 / 相机）</option><option value="sensor">传感器节点</option><option value="notification">通知节点</option></select><button type="button" className={'filter-toggle '+(onlineOnly?'active':'')} aria-pressed={onlineOnly} onClick={()=>setOnlineOnly(!onlineOnly)}>仅在线</button></div></div><div className="node-directory">
          {visibleNodes.length === 0 && <Empty text={nodes.length ? '没有匹配的节点' : '尚无已登记节点'} />}
          {visibleNodes.map(device => <button key={device.deviceId} aria-pressed={node?.deviceId === device.deviceId} className={'node-row '+(node?.deviceId === device.deviceId ? 'selected' : '')} onClick={() => { setSelected(device.deviceId); setNodeDetailOpen(true); }}>
            <span className="node-identity"><NodeIcon node={device}/><span><strong title={device.deviceName}>{device.deviceName}</strong><small>{device.component==='android-camera'?'相机推流节点':typeLabel(device.nodeType)} · {device.platform}</small></span></span><span className="node-work"><span className={'dot '+(device.online ? 'online' : '')}/>{!device.online ? '离线' : device.component === 'android-camera' ? (relay.streams.find(s=>s.publisherDeviceId===device.deviceId)?.isStreaming ? '正在推流' : '等待推流') : device.nodeType === 'notification' ? '通知服务已连接' : device.isMonitoring ? '检测中' : device.isReady ? '已就绪' : '未检测'}</span><ChevronRight className="node-chevron" size={16} aria-hidden="true"/>
          </button>)}
        </div></section>
        <div className="detail-column">
          <button className="text-button node-back" onClick={()=>setNodeDetailOpen(false)}><ArrowLeft size={20}/>全部节点</button>
          {node ? <NodeDetail key={node.deviceId} node={node} drafts={parameterDrafts.current} acks={relay.acks} stream={relay.streams.find(s=>s.publisherDeviceId===node.deviceId)} timeZone={timeZone} connected={relay.connected} send={relay.send} events={<LatestNodeEvent node={node} alerts={relay.alerts} login={login} timeZone={timeZone} onOpen={setEvent}/>} management={<DeviceSettings key={`manage-${node.deviceId}`} node={node} nodes={nodes} streams={relay.streams} login={login} onChanged={relay.refresh}/>}><NodeContext node={node} nodes={nodes} stream={relay.streams.find(s=>s.publisherDeviceId===node.deviceId)} notifier={relay.notifiers.find(n=>n.deviceId===node.deviceId)} alerts={relay.alerts} login={login} timeZone={timeZone} onOpen={setEvent} onScope={()=>{setScopeSelected(node.deviceId);setPage('通知范围');}}/></NodeDetail> : <section className="panel"><Empty text="登录同一账号的节点将在这里显示"/></section>}
        </div>
      </div></>}
      {page === '事件' && <section className="panel events-panel">
        <div className="event-toolbar"><div className="section-title"><h2>最近事件 <span className="count-badge">{filteredEvents.length}</span></h2><small>{timeStandardLabel(timeZone)} · 最多 100 条</small></div>
          <div className="node-filters event-filters"><label className="search-field"><Search size={18}/><input aria-label="搜索事件" placeholder="搜索节点、来源或摘要" value={eventSearch} onChange={e=>setEventSearch(e.target.value)}/></label><select aria-label="事件类型" value={eventKind} onChange={e=>setEventKind(e.target.value)}><option value="">全部事件</option>{[...new Set(relay.alerts.map(alert=>alert.eventKind))].map(kind=><option key={kind} value={kind}>{eventLabel(kind)}</option>)}</select><label className="event-time-filter">开始时间<input type="datetime-local" step="1" value={eventStart} onChange={e=>setEventStart(e.target.value)} aria-invalid={!!eventTimeError} aria-describedby={eventTimeError?'event-time-error':undefined}/></label><label className="event-time-filter">结束时间<input type="datetime-local" step="1" value={eventEnd} onChange={e=>setEventEnd(e.target.value)} aria-invalid={!!eventTimeError} aria-describedby={eventTimeError?'event-time-error':undefined}/></label>{(eventStart || eventEnd) && <button className="button secondary" onClick={()=>{setEventStart('');setEventEnd('');}}>清除时间</button>}</div>
          {eventTimeError && <p className="error" id="event-time-error" role="alert">{eventTimeError}</p>}
        </div>
        {eventTimeError ? null : filteredEvents.length===0?<Empty text={relay.alerts.length?'没有匹配的事件':'暂无事件'}/>:<div className="event-table"><div className="event-table-heading" aria-hidden="true"><span>事件类型</span><span>节点 / 来源与摘要</span><span>时间 / 收件状态</span><span/></div>{filteredEvents.map(a=><button className="event-row" key={a.alertId} onClick={()=>setEvent(a)}><span className={'event-kind '+(a.eventKind==='visual-detection'||a.eventKind==='sensor-detection'?'':'warning')}><Activity size={16}/>{eventLabel(a.eventKind)}</span><span><strong>{a.deviceName||a.deviceId}{a.sourceName&&` · ${a.sourceName}`}</strong><small>{a.summary||'查看检测详情'}</small></span><span className="event-time"><time>{formatTime(a.timestamp,timeZone)}</time><small>{(relay.receipts[a.alertId]??[]).length>0?`${relay.receipts[a.alertId].length} 个通知节点已收件`:'未观测到收件回执'}</small></span><ChevronRight size={16} aria-hidden="true"/></button>)}</div>}
      </section>}
      {page === '通知范围' && <ScopeWorkspace initialSelected={scopeSelected} notifiers={relay.notifiers} nodes={nodes.filter(n=>n.role==='detector')} connected={relay.connected} acks={relay.acks} send={relay.send}/>}
      {page === '设置' && <div className="settings-workspace" data-section={settingsSection}><div className="settings-section-nav" role="group" aria-label="设置分区">{([{value:"account",label:"账号与本机"},{value:"general",label:"常规设置"},{value:"cache",label:"缓存"}] as const).map(item=><button type="button" key={item.value} aria-pressed={settingsSection===item.value} aria-controls={`settings-${item.value}`} onClick={()=>setSettingsSection(item.value)}>{item.label}</button>)}</div><div className="settings-grid"><div className="settings-stack"><AccountSettings login={login} onDeviceNameChanged={name=>{if(loginRef.current?.token===login.token){const renamed={...login,device:{...login.device,deviceName:name}};updateLogin(renamed);void saveSessionLogin(renamed).catch(()=>{});}}} onLogout={() => { void logout(); }} onPasswordChanged={warning => { if (loginRef.current?.token === login.token) clearSession(warning || '密码已修改，请重新登录'); }}/></div><div className="settings-stack"><section className="panel settings-panel general-settings" id="settings-general"><div className="section-title"><h2>常规设置</h2><Settings size={18} aria-hidden="true"/></div><div className="settings-field-row settings-appearance-row"><span>外观</span><AppearanceSelector preference={appearance}/></div><TimeStandardSettings standard={relay.timeStandard} connected={relay.connected} acks={relay.acks} send={relay.send}/></section><ServerCacheMaintenance key={login.token} login={login}/></div></div></div>}
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
  return <section className="settings-block time-panel" aria-labelledby="console-time-heading"><h3 id="console-time-heading">统一时间</h3>
    <dl className="settings-readings"><dt>当前标准</dt><dd>{standard ? timeStandardLabel(standard.timeZone) : '等待服务响应'}</dd><dt>服务参考时间</dt><dd>{standard ? formatTime(standard.serverTime,standard.timeZone) : '—'}</dd></dl>
    <form className="settings-form" onSubmit={e => { e.preventDefault(); setRequestId(send({type:'set-time-standard',timeZone:draft})); }}><div className="settings-field-row"><label htmlFor="console-time-zone">时间标准</label><select id="console-time-zone" aria-label="告警时间标准" value={draft} onChange={e => setDraft(e.target.value as AlarmTimeZone)} disabled={!connected || !standard || saving}><option value="Asia/Shanghai">北京时间（UTC+8）</option><option value="UTC">UTC（UTC+0）</option></select><button className="button" type="submit" disabled={!connected || !standard || saving}>{saving ? '正在保存…' : '保存标准'}</button></div></form>
    {result && <p role={saving || result.success ? 'status' : 'alert'} className={saving || result.success ? 'subtle' : 'error'}>{saving ? '正在保存…' : result.success ? '时间标准已保存' : result.reason || '保存失败'}</p>}
  </section>;
}
function LoginScreen({onLogin,error}:{onLogin:(value:Login)=>void;error:string}) {
  const [username,setUsername] = useState(''); const [password,setPassword] = useState('');
  const [busy,setBusy] = useState(false); const [failure,setFailure] = useState('');
  const [remember,setRemember] = useState(false), [restoring,setRestoring] = useState(true);
  useEffect(()=>{let active=true;void readRememberedLogin().then(saved=>{if(active&&saved){setUsername(saved.username);setPassword(saved.password);setRemember(true);}}).catch(()=>{if(active)setFailure('无法读取记住的账号密码，可手动填写后登录');}).finally(()=>{if(active)setRestoring(false);});return()=>{active=false;};},[]);
  async function changeRemember(checked:boolean) {
    if(checked){setRemember(true);return;}
    setBusy(true);setFailure('');
    try { await clearRememberedLogin();setRemember(false); } catch { setFailure('无法清除已记住的账号密码，请检查浏览器站点存储权限'); } finally { setBusy(false); }
  }
  async function submit() {
    setBusy(true);setFailure('');
    try {
      websocketURL(location.origin);
      const value=parseLogin(await accountRequest('/api/account/login',undefined,{username:username.trim(),password,component:'web-console',deviceIdentity:await browserDeviceIdentity(),deviceModel:browserDeviceModel()}));
      if(remember) {
        try { await saveRememberedLogin({username:value.account.username,password}); }
        catch { await accountRequest('/api/account/logout',value.token,{}).catch(()=>{});throw new Error('账号密码未能保存，请取消“记住账号密码”后重试'); }
      }
      try { await saveSessionLogin(value); }
      catch { await accountRequest('/api/account/logout',value.token,{}).catch(()=>{});throw new Error('登录会话未能保存，请检查浏览器站点存储权限后重试'); }
      onLogin(value);setPassword('');
    } catch(e) {setFailure((e as Error).message);} finally {setBusy(false);}
  }
  return <div className="login-page"><div className="login-layout">
    <header className="login-header"><strong>VisionGuard</strong></header>
    <form className="login-form" onSubmit={e => { e.preventDefault(); void submit(); }}>
    <div className="login-form-heading"><h1>登录控制台</h1></div>
    {(failure || error) && <p className="error" role="alert">{failure || error}</p>}
    <label>账号<input required maxLength={64} autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} disabled={busy||restoring}/></label>
    <label>密码<input required type="password" maxLength={256} autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} disabled={busy||restoring}/></label>
    <label className="remember-login"><input type="checkbox" checked={remember} onChange={e=>void changeRemember(e.target.checked)} disabled={busy||restoring}/>记住账号密码</label>
    <button className="button" type="submit" disabled={busy||restoring}>{busy ? '登录中…' : '登录'}</button>
  </form></div></div>;
}
function AccountSettings({login,onLogout,onPasswordChanged,onDeviceNameChanged}:{login:Login;onLogout:()=>void;onPasswordChanged:(warning?:string)=>void;onDeviceNameChanged:(name:string)=>void}) {
  const [deviceName,setDeviceName] = useState(login.device.deviceName); const [nameBusy,setNameBusy] = useState(false); const [nameMessage,setNameMessage] = useState(''); const [nameError,setNameError] = useState('');
  useEffect(()=>setDeviceName(login.device.deviceName),[login.device.deviceName]);
  async function renameSelf() { setNameBusy(true);setNameMessage('');setNameError('');try { const name=normalizeDisplayName(deviceName);await accountRequest(`/api/devices/${encodeURIComponent(login.device.deviceId)}`,login.token,{deviceName:name},'PATCH');onDeviceNameChanged(name);setNameMessage('名称已保存'); } catch(e) { setNameError((e as Error).message); } finally { setNameBusy(false); } }
  const [currentPassword,setCurrentPassword] = useState(''); const [newPassword,setNewPassword] = useState('');
  const [busy,setBusy] = useState(false); const [error,setError] = useState('');
  async function changePassword() {
    setBusy(true);setError('');
    try {
      await accountRequest('/api/account/password',login.token,{currentPassword,newPassword});
      let warning:string|undefined;
      try { await clearRememberedLogin(); } catch { warning='密码已修改，但旧账号密码记忆未能清除；请填写新密码后重新登录'; }
      setCurrentPassword('');setNewPassword('');onPasswordChanged(warning);
    } catch(e) {setError((e as Error).message);} finally {setBusy(false);}
  }
  return <section className="panel settings-panel account-settings" id="settings-account">
    <div className="section-title"><h2>账号与本机</h2><ShieldCheck size={18} aria-hidden="true"/></div>
    <div className="account-summary"><div><strong>{login.account.username}</strong><small>{login.account.isAdmin?'管理员':'普通账号'}</small></div><button className="button secondary" onClick={onLogout}><LogOut size={16}/>退出登录</button></div>
    <dl className="settings-readings"><dt>服务地址</dt><dd>{location.origin}</dd></dl>
    <form className="settings-block settings-form name-settings" onSubmit={e=>{e.preventDefault();void renameSelf();}}><div className="settings-field-row"><label htmlFor="console-device-name">本机名称</label><input id="console-device-name" aria-label="设备名称" required maxLength={64} value={deviceName} onPaste={e=>checkNamePaste(e,setNameError)} onChange={e=>{setNameError('');setDeviceName(e.target.value);}} disabled={nameBusy}/><button className="button secondary" disabled={nameBusy||!deviceName.trim()}>{nameBusy?'保存中…':'保存名称'}</button></div>{nameMessage&&<p className="settings-feedback" role="status">{nameMessage}</p>}{nameError&&<p className="error settings-feedback" role="alert">{nameError}</p>}</form>
    <form className="settings-block settings-form account-password" onSubmit={e=>{e.preventDefault();void changePassword();}}><h3>修改密码</h3><div className="settings-field-row"><label htmlFor="console-current-password">当前密码</label><input id="console-current-password" required type="password" autoComplete="current-password" value={currentPassword} onChange={e=>setCurrentPassword(e.target.value)} disabled={busy}/></div><div className="settings-field-row"><label htmlFor="console-new-password">新密码</label><input id="console-new-password" required type="password" minLength={8} maxLength={256} autoComplete="new-password" value={newPassword} onChange={e=>setNewPassword(e.target.value)} disabled={busy}/></div><small className="settings-feedback">至少 8 个字符，修改后所有设备需重新登录。</small><div className="settings-form-footer"><button className="button" disabled={busy}>{busy?'保存中…':'修改密码'}</button></div>{error&&<p className="error settings-feedback" role="alert">{error}</p>}</form>
  </section>;
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
  return <section className="panel device-management" aria-busy={busy}><div className="section-title"><h2>设备身份与关联</h2><Settings size={18} aria-hidden="true"/></div><p className="device-id">设备 ID：{node.deviceId}</p><form className="device-form" onSubmit={e=>{e.preventDefault();void mutate(`/api/devices/${encodeURIComponent(node.deviceId)}`,{deviceName:name},'PATCH');}}><label>设备名称<input required maxLength={64} value={name} onPaste={e=>checkNamePaste(e,setError)} onChange={e=>{setError('');setName(e.target.value);}} disabled={busy}/></label><button className="button secondary" disabled={busy||!name.trim()}>保存名称</button><p className="subtle">名称最多 64 个字符，不能包含换行。</p></form>{camera&&<form className="device-form" onSubmit={e=>{e.preventDefault();void mutate('/api/streams/bind',{publisherDeviceId:node.deviceId,targetDeviceId:target});}}><label>推理节点<select value={target} onChange={e=>setTarget(e.target.value)} disabled={busy}><option value="">选择推理节点</option>{targets.map(t=><option key={t.deviceId} value={t.deviceId}>{t.deviceName}{t.online?'':'（离线）'}</option>)}</select></label><button className="button" disabled={busy||!target}>关联来源</button><p className="subtle">{stream?.isStreaming?'正在推流':stream?.targetDeviceId?'已关联，等待推流':'只有一个推理节点时自动关联'}</p></form>}<div className="danger-zone"><div><strong>解绑设备</strong><small>设备需重新登录，事件记录保留</small></div><button className="button secondary danger-outline" disabled={busy} onClick={()=>setConfirmUnbind(true)}>解绑设备</button></div>{confirmUnbind&&<ConfirmDialog title="解绑设备" description={`解绑“${node.deviceName}”后，该设备需要重新登录。事件记录会保留。`} confirmText="解绑" onClose={()=>setConfirmUnbind(false)} onConfirm={()=>{setConfirmUnbind(false);void mutate(`/api/devices/${encodeURIComponent(node.deviceId)}`,undefined,'DELETE');}}/>}{busy&&<p className="subtle" role="status">正在处理…</p>}{message&&<p role="status">{message}</p>}{error&&<p className="error" role="alert">{error}</p>}</section>;
}
function NodeContext({node,nodes,stream,notifier,onScope}:{node:Device;nodes:Device[];stream?:Stream;notifier?:Notifier;alerts:Alert[];login:Login;timeZone:AlarmTimeZone;onOpen:(alert:Alert)=>void;onScope:()=>void}) {
  if(node.nodeType==='notification')return <section className="panel"><div className="section-title"><h2>接收范围</h2><Bell size={18} aria-hidden="true"/></div><p>{!notifier?'等待服务报告接收范围':notifier.scope.mode==='all'?'接收全部检测节点':notifier.scope.targets.length?`接收指定的 ${notifier.scope.targets.length} 个节点 / 来源`:'尚未选择检测节点与来源，不接收检测事件'}</p><div className="actions"><button className="button secondary" onClick={onScope}>配置接收范围<ChevronRight size={16}/></button></div><p>收件确认只表示报警已保存，声音播放需在通知设备核对。</p></section>;
  if(node.component==='android-camera')return <section className="panel"><div className="section-title"><h2>推流关联</h2><Camera size={18} aria-hidden="true"/></div><dl><dt>目标视觉推理节点</dt><dd>{nodes.find(n=>n.deviceId===stream?.targetDeviceId)?.deviceName||stream?.targetDeviceId||'未关联'}</dd><dt>检测来源</dt><dd>{stream?.sourceName||'未上报'}</dd><dt>推流状态</dt><dd>{stream?.isStreaming?'正在推流':'未推流'}</dd></dl><p>在「设备管理」调整关联，在「参数与配置」调整规格、预览和屏幕亮度。</p><p>相机前台、摄像头权限与设备解锁须在本机处理。</p></section>;
  return null;
}
function LatestNodeEvent({node,alerts,login,timeZone,onOpen}:{node:Device;alerts:Alert[];login:Login;timeZone:AlarmTimeZone;onOpen:(alert:Alert)=>void}) {
  const latest = alerts.find(alert => alert.deviceId === node.deviceId);
  return <section className="panel latest-event"><div className="section-title"><h2>最近事件</h2><span className="subtle">历史画面</span></div>{latest ? <><div className="event-preview"><EventImage alert={latest} login={login}/></div><span className="event-kind">{eventLabel(latest.eventKind)}</span><strong className="latest-source">{latest.sourceName || node.deviceName}</strong><small>{formatTime(latest.timestamp,timeZone)}</small><p>{latest.summary || '无文字摘要'}</p><button className="button secondary" onClick={()=>onOpen(latest)}>查看事件详情<ChevronRight size={16}/></button></> : <Empty text="收到事件后在这里显示画面"/>}</section>;
}
function NodeIcon({node}:{node:Device}) { const Icon = node.nodeType === 'visual' ? Camera : node.nodeType === 'notification' ? Bell : Radio; return <span className="node-icon"><Icon size={24}/></span>; }
function NodeDetail({node,stream,connected,send,timeZone,acks,drafts,children,management,events}:{node:Device;stream?:Stream;connected:boolean;send:(m:Record<string,unknown>)=>string;timeZone:AlarmTimeZone;acks:Ack[];drafts:ParameterDrafts;children:React.ReactNode;management:React.ReactNode;events:React.ReactNode}) {
  const [tab,setTab]=useState<'run'|'config'|'manage'>('run');
  const tabsId=React.useId();
  const [sourceId,setSourceId] = useState('');
  const [controlRequest,setControlRequest] = useState('');
  const [controlTimedOut,setControlTimedOut] = useState(false);
  const controlResult = acks.find(ack=>ack.requestId===controlRequest);
  const controlPending = !!controlRequest && !controlTimedOut && (!controlResult || ['pending','forwarded'].includes(controlResult.phase??''));
  useEffect(()=>{ if(!controlRequest)return; const timer=setTimeout(()=>setControlTimedOut(true),30000); return ()=>clearTimeout(timer); },[controlRequest]);
  function issueCommand(command:string,targetSourceId?:string) { setControlTimedOut(false);setControlRequest(send({type:'command',targetDeviceId:node.deviceId,command,...(targetSourceId?{targetSourceId}:{})})); }
  const source = node.sources.find(s => s.sourceId === sourceId);
  const can = (value:string) => node.capabilities.includes(value);
  const ready = connected && node.online && !controlPending;
  const cameraStartReason = !stream?.targetDeviceId ? '请先关联视觉推理节点' : node.components?.cameraApp!=='foreground' ? '请在设备上打开相机应用并保持前台' : node.components?.cameraPermission!=='granted' ? '请在设备上授予摄像头权限' : '';
  const sendCommand = (command:string) => issueCommand(command,source?.sourceId);
  const nodeAcks=acks.filter(ack=>ack.targetDeviceId===node.deviceId);
  return <div className="node-detail-layout"><div className="node-primary"><section className="panel node-summary">
    <div className="node-summary-main"><div className="node-detail-title"><NodeIcon node={node}/><div className="node-summary-identity"><div className="node-heading-line"><h2>{node.deviceName}</h2><span className={'status-tag '+(node.online ? 'good' : '')}>{node.online ? '在线' : '离线'}</span></div><div className="node-subtitle"><span>{node.component==='android-camera'?'相机推流节点':typeLabel(node.nodeType)} · {node.platform}</span>
{node.online&&<span className="node-working-state"><span className={'dot '+((stream?.isStreaming ?? node.isMonitoring) ? 'online' : '')}/>{node.component==='android-camera' ? (stream?.isStreaming?'推流中':stream?.stopReason==='connection-lost'||stream?.stopReason==='frame-stalled'?'推流中断':'未推流') : node.nodeType === 'notification' ? (node.online ? '通知服务已连接' : '通知服务未连接') : node.isMonitoring ? '检测中' : node.isReady ? '已就绪' : '未检测'}</span>}</div></div></div>
    <div className="actions primary-actions node-summary-actions">{can('monitor-control') && (!source || can('source-control')) && <button className="button" disabled={!ready} onClick={() => sendCommand((source?.isMonitoring ?? node.isMonitoring) ? 'pause' : 'resume')}>{(source?.isMonitoring ?? node.isMonitoring)?<Pause size={16}/>:<Play size={16}/>}{(source?.isMonitoring ?? node.isMonitoring) ? '暂停检测' : '开始检测'}</button>}
      {can('monitor-control') && (!source || can('source-control')) && <button className="button secondary" disabled={!ready} onClick={() => sendCommand('stop-alarm')}><BellOff size={16}/>停止报警</button>}
      {can('stream-control') && <><button className="button" disabled={!ready || !!stream?.isStreaming || !!cameraStartReason} onClick={()=>issueCommand('start-stream')}><Play size={16}/>开始推流</button><button className="button secondary" disabled={!ready} onClick={()=>issueCommand('stop-stream')}><Square size={16}/>停止推流</button></>}
      {can('alarm-control') && <button className="button" disabled={!ready} onClick={()=>issueCommand('stop-alarm')}><BellOff size={16}/>确认当前报警</button>}
      {can('app-lifecycle-control') && <div className="app-actions"><button className="text-button" disabled={!ready} onClick={() => issueCommand('open-detector')}>打开视觉推理节点</button><button className="text-button" disabled={!ready} onClick={() => issueCommand('close-detector')}>关闭视觉推理节点</button></div>}
    </div></div>
    <div className="node-summary-footer"><div className="control-context"><span>控制对象：<strong>{source ? source.sourceName || source.sourceId : '节点整体'}</strong></span>{source&&<button className="text-button" onClick={()=>setSourceId('')}>切回节点整体</button>}</div>
    {node.components && <div className="component-line">{Object.entries(node.components).map(([key,value]) => <span key={key}>{['resident','residentApp'].includes(key) ? '驻留' : ['detector','detectorApp'].includes(key) ? '视觉推理节点' : key === 'camera' ? '镜头' : key === 'cameraApp' ? '相机状态' : key === 'cameraPermission' ? '摄像头权限' : key}：{value === 'running' ? '运行中' : value === 'stopped' ? '已停止' : value === 'foreground' ? '前台' : value === 'background' ? '后台' : value === 'granted' ? '已授权' : value === 'required' ? '待授权' : value}</span>)}</div>}{node.lastSeen&&<span className="node-last-seen">最近响应 <time>{formatTime(node.lastSeen,timeZone)}</time></span>}</div>
    {!node.online && <p className="subtle">离线时保留登记身份和本次编辑草稿；参数将在重新连接后显示。</p>}
    {can('stream-control') && !stream?.isStreaming && cameraStartReason && <p className="subtle">{cameraStartReason}</p>}
    {can('alarm-control') && <p className="subtle">仅确认当前报警；如有排队报警，将继续播放。通知服务离线时需在设备上开启。</p>}
    {controlRequest && <p role={controlResult?.success||controlPending?'status':'alert'} className={controlResult?.success||controlPending?'subtle':'error'}>{controlResult?.phase==='completed' ? controlResult.reason || (controlResult.success?'已执行':'操作失败') : controlTimedOut ? '执行结果未确认，请刷新节点状态后重试' : controlResult?.phase==='forwarded' ? '已转发，等待节点执行' : '正在发送操作…'}</p>}
    {!can('monitor-control') && node.online && node.nodeType==='visual' && node.component!=='android-camera' && <p className="subtle">此节点未提供检测启停能力。</p>}
  </section>
  <WorkspaceTabs id={tabsId} label="节点工作区" tabs={[{value:'run',label:'运行'},{value:'config',label:'参数与配置'},{value:'manage',label:'设备管理'}]} value={tab} onChange={setTab}/>
  <div role="tabpanel" id={`${tabsId}-panel-run`} aria-labelledby={`${tabsId}-tab-run`} hidden={tab!=='run'} className="workspace-body runtime-grid">
  {node.nodeType === 'visual' && node.component!=='android-camera' && <section className="panel source-panel">{node.sourceLimitExceeded && <p className="error">来源数量超过服务端上限（最多 {node.maxSources ?? '未报告'} 路），当前列表为上一次成功上报的快照。</p>}<div className="section-title"><h2>检测来源 <span className="count-badge">{node.sources.length}</span></h2><button className="text-button" onClick={() => setSourceId('')} disabled={!sourceId}>节点整体</button></div>{node.sources.length === 0 ? <Empty text="暂无已连接来源"/> : <div className="source-table-wrap"><table className="source-table"><thead><tr><th>名称</th><th>状态</th><th>帧率（FPS）</th><th>操作</th></tr></thead><tbody>{node.sources.map(s => <tr className={sourceId === s.sourceId ? 'selected' : ''} key={s.sourceId}><td><strong>{s.sourceName || s.sourceId}</strong><small>{s.sourceId}</small></td><td className="source-state"><span className={'dot '+(!node.online ? '' : s.error ? 'error' : !s.isReady ? 'warning' : s.isMonitoring ? 'online' : '')}/>{!node.online ? '离线' : s.error ? '异常' : !s.isReady ? '未就绪' : s.isMonitoring ? '检测中' : '已暂停'}{s.error && <details className="source-error"><summary>查看原因</summary><p className="error">{s.error}</p></details>}</td><td>{!node.online || s.actualFps === undefined ? '—' : s.actualFps.toFixed(1)}</td><td><div className="source-actions">{can('source-control') && can('monitor-control') && <button className="text-button" disabled={!ready} aria-label={`${s.isMonitoring ? '暂停' : '开始'} ${s.sourceName}`} onClick={() => issueCommand(s.isMonitoring ? 'pause' : 'resume',s.sourceId)}>{s.isMonitoring ? '暂停' : '开始'}</button>}{can('config-control') && can('source-control') && <button className="text-button" aria-label={`参数 ${s.sourceName}`} onClick={() => { setSourceId(s.sourceId); setTab('config'); }}><Settings size={16}/>参数</button>}</div></td></tr>)}</tbody></table></div>}</section>}
  {children}
  </div>
  <div role="tabpanel" id={`${tabsId}-panel-config`} aria-labelledby={`${tabsId}-tab-config`} hidden={tab!=='config'} className="workspace-body config-workspace">
    {can('config-control')&&<><label className="config-context">编辑对象<select aria-label="参数编辑对象" value={sourceId} onChange={e=>setSourceId(e.target.value)}><option value="">节点整体</option>{can('source-control')&&node.sources.map(s=><option key={s.sourceId} value={s.sourceId}>{s.sourceName||s.sourceId}{s.isMonitoring?'（检测中）':''}</option>)}</select></label><Parameters key={`${node.deviceId}/${source?.sourceId ?? ''}`} node={node} source={source} acks={acks} drafts={drafts} disabled={!ready || (!!source && (!can('source-control') || source.isMonitoring)) || (!source && node.nodeType === 'visual' && node.isMonitoring)} send={send}/></>}
    <RemoteSettings key={node.deviceId} node={node} connected={connected} acks={acks} send={send}/>
    {!node.capabilities.some(c=>['config-control','camera-config','sound-config','audio-library'].includes(c))&&<Empty text={node.online?'此节点未提供远程配置能力':'节点离线，连接后可读取配置能力'}/>}
  </div>
  <div role="tabpanel" id={`${tabsId}-panel-manage`} aria-labelledby={`${tabsId}-tab-manage`} hidden={tab!=='manage'} className="workspace-body management-grid">{management}<NodeCacheMaintenance key={`cache-${node.deviceId}`} node={node} connected={connected} acks={acks} send={send}/></div>

  </div><aside className="node-events" aria-label="当前节点事件与操作回执">{events}<section className="panel node-receipts" aria-label="当前节点操作回执"><div className="section-title"><h2>操作回执 <span className="count-badge">{nodeAcks.length}</span></h2><Clock size={18} aria-hidden="true"/></div><p className="subtle">以执行端结果为准</p><div className="node-receipt-list">{nodeAcks.length===0?<Empty text="此节点暂无操作回执"/>:nodeAcks.map(ack=><div className="receipt-row" key={ack.requestId}><span><strong>{commandLabel(ack.command)}</strong><small>{ack.targetSourceId||'节点整体'}</small></span><span className={!ack.success&&ack.phase!=='uncertain'?'error':'subtle'}>{ack.phase==='uncertain'?'结果待核实':!ack.success?'失败':ack.phase==='forwarded'?'已转发':ack.phase==='pending'?'等待执行':'已完成'}<small>{ack.reason}</small></span></div>)}</div></section></aside></div>;
}
function commandLabel(command?:string) {
  if(command?.startsWith('set-config:'))return `保存${({modelKey:'推理模型',confidence:'置信度',cooldown:'报警冷却',targetSamplingRate:'采样频率',targets:'检测目标',cameraResolution:'推流规格',cameraHidePreview:'预览设置',cameraDimScreen:'屏幕亮度',soundSelection:'默认铃声',soundLoopCount:'播放次数',audioRename:'音频名称',audioImport:'导入音频',audioDelete:'删除音频',audioPreview:'音频试听',audioStopPreview:'停止试听'} as Record<string,string>)[command.slice(11)]||'节点配置'}`;
  return ({pause:'暂停检测',resume:'开始检测','stop-alarm':'停止 / 确认报警','start-stream':'开始推流','stop-stream':'停止推流','open-detector':'打开视觉推理节点','close-detector':'关闭视觉推理节点','set-config':'保存配置','set-notification-scope':'保存通知范围','cache-inspect':'盘点缓存','cache-clean':'清理缓存'} as Record<string,string>)[command??'']||'节点操作';
}
function ScopeWorkspace({initialSelected='',notifiers,nodes,connected,acks,send}:{initialSelected?:string;notifiers:Notifier[];nodes:Device[];connected:boolean;acks:Ack[];send:(m:Record<string,unknown>)=>string}) {
  const [selected,setSelected]=useState(initialSelected);
  const notifier=notifiers.find(n=>n.deviceId===selected)??notifiers[0];
  if(!notifier)return <section className="panel scope-empty"><Empty text="尚无已登记的通知节点"/></section>;
  return <div className="scope-workspace"><section className="panel scope-directory"><div className="section-title"><h2>通知节点</h2><span className="count-badge">{notifiers.length}</span></div><div className="scope-node-list">{notifiers.map(n=><button key={n.deviceId} className={'scope-select '+(n.deviceId===notifier.deviceId?'selected':'')} aria-pressed={n.deviceId===notifier.deviceId} onClick={()=>setSelected(n.deviceId)}><Bell size={18} aria-hidden="true"/><span><strong>{n.deviceName}</strong><small>{n.online?'在线':'离线'} · {n.scope.mode==='all'?'接收全部节点':`指定 ${n.scope.targets.length} 项`}</small></span><ChevronRight size={16} aria-hidden="true"/></button>)}</div></section><div className="scope-editor-column">{notifiers.map(n=><div key={n.deviceId} hidden={n.deviceId!==notifier.deviceId}><ScopeEditor notifier={n} nodes={nodes} connected={connected} acks={acks} send={send}/></div>)}</div></div>;
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
  return <section className="panel scope-editor"><div className="section-title"><div><h2>{notifier.deviceName}</h2><p>{notifier.deviceId}</p></div><span className={'status-tag '+(notifier.online ? 'good' : '')}>{notifier.online ? '在线' : '离线'}</span></div><fieldset disabled={!connected || saving}><legend>接收范围</legend><div className="scope-mode-bar"><div className="radio-options"><label><input type="radio" name={`scope-${notifier.deviceId}`} checked={scope.mode === 'all'} onChange={() => updateScope({mode:'all',targets:[]})}/>全部节点</label><label><input type="radio" name={`scope-${notifier.deviceId}`} checked={scope.mode === 'selected'} onChange={() => updateScope({mode:'selected',targets:[]})}/>指定节点与来源</label></div><button className="button" onClick={()=>setRequestId(send({type:'set-notification-scope',targetNotifierId:notifier.deviceId,scope}))} disabled={scope.targets.length>100}>{saving?'正在保存…':'保存接收范围'}</button></div>
    {scope.mode === 'selected' && <div className="scope-targets">{nodes.map(n => <div className="scope-node" key={n.deviceId}><label><input type="checkbox" checked={contains({deviceId:n.deviceId})} onChange={() => toggle({deviceId:n.deviceId})}/>{n.deviceName}<small>{typeLabel(n.nodeType)} · {n.online ? '在线' : '离线'}</small></label>{n.nodeType === 'visual' && [...new Map([...scope.targets.filter(t => t.deviceId === n.deviceId && t.sourceId).map(t => ({sourceId:t.sourceId!,sourceName:t.sourceId!})),...n.sources.map(s => ({sourceId:s.sourceId,sourceName:s.sourceName}))].map(s => [s.sourceId,s])).values()].map(s => <label className="source-check" key={s.sourceId}><input type="checkbox" checked={contains({deviceId:n.deviceId,sourceId:s.sourceId})} onChange={() => toggle({deviceId:n.deviceId,sourceId:s.sourceId})}/>{s.sourceName || s.sourceId}</label>)}</div>)}
      <details className="offline-source-details"><summary>添加未上报的离线来源</summary><div className="offline-source"><label>离线视觉来源<select value={offlineDevice} aria-describedby={offlineSourceHintId} onChange={e => setOfflineDevice(e.target.value)}><option value="">选择视觉推理节点</option>{nodes.filter(n => n.nodeType === 'visual' && !n.online).map(n => <option key={n.deviceId} value={n.deviceId}>{n.deviceName}</option>)}</select></label><label>来源 ID<input value={offlineSource} pattern={'[A-Za-z0-9_\\-]{1,64}'} aria-describedby={offlineSourceHintId} aria-invalid={invalidOfflineSource} maxLength={64} onChange={e => setOfflineSource(e.target.value)}/></label><button className="button secondary" type="button" disabled={!offlineDevice || !validOfflineSource || contains({deviceId:offlineDevice,sourceId:offlineSource})} onClick={() => { toggle({deviceId:offlineDevice,sourceId:offlineSource}); setOfflineSource(''); }}>添加</button></div>
      <p id={offlineSourceHintId} className={invalidOfflineSource ? 'error' : 'subtle'} role={invalidOfflineSource ? 'alert' : undefined}>{invalidOfflineSource ? '来源 ID 只能使用字母、数字、下划线或短横线，最多 64 个字符。' : '选择离线节点，填写 1–64 个字母、数字、下划线或短横线组成的来源 ID，点击添加加入范围。'}</p></details>
      {scope.targets.length === 0 && <p className="subtle">未勾选时不接收检测事件，节点仍独立监测服务响应。</p>}
    </div>}
    </fieldset>{result && <p role={saving || result.success ? 'status' : 'alert'} className={saving || result.success ? 'subtle' : 'error'}>{saving ? '正在保存接收范围…' : result.success ? '接收范围已保存' : result.reason || '保存接收范围失败'}</p>}<p className="subtle">范围保存在服务端，离线节点下次连接时读取。收件确认表示报警已保存。</p>
  </section>;
}
function EventImage({alert,login}:{alert:Alert;login:Login}) {
  const [image,setImage] = useState<string | null>(null);
  const [imageError,setImageError] = useState('');
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
  return <>{image && <img className="event-image" src={image} alt="事件截图" onError={()=>{setImage(null);setImageError('截图无法显示');}}/>}{alert.hasScreenshot && !image && !imageError && <p role="status">{alert.screenshotUrl ? '正在加载截图…' : '等待截图地址…'}</p>}{!alert.hasScreenshot && <p className="subtle">此事件没有截图</p>}{imageError && <p className="error" role="alert">{imageError}</p>}</>;
}
function EventDialog({alert,login,receipts,onClose,timeZone}:{alert:Alert;login:Login;receipts:string[];onClose:()=>void;timeZone:AlarmTimeZone}) {
  const titleId = React.useId();
  const dialog = useModalDialog();
  return <dialog className="event-drawer" ref={dialog} aria-labelledby={titleId} onCancel={onClose} onClick={e => { if(e.target === e.currentTarget) onClose(); }}><div className="dialog-body"><div className="section-title"><h2 id={titleId}>{eventLabel(alert.eventKind)}</h2><button className="icon-button" aria-label="关闭事件详情" title="关闭" autoFocus onClick={onClose}><X size={18}/></button></div><EventImage alert={alert} login={login}/><dl><dt>节点</dt><dd>{alert.deviceName || alert.deviceId}</dd>{alert.sourceId && <><dt>来源</dt><dd>{alert.sourceName || alert.sourceId}</dd></>}<dt>时间</dt><dd>{formatTime(alert.timestamp,timeZone)}</dd><dt>时间标准</dt><dd>{timeStandardLabel(timeZone)}</dd><dt>摘要</dt><dd>{alert.summary || '无文字摘要'}</dd><dt>收件</dt><dd>{receipts.length ? receipts.join('、') : '未观测到本会话收件回执'}</dd></dl>{!!alert.detections?.length && <details><summary>检测结果 · {alert.detections.length} 项</summary>{alert.detections.map((d,i) => <p key={i}>{d.label} · {Math.round(d.confidence*100)}%</p>)}</details>}<small className="subtle">历史记录用于查看；过期事件不会重新通知。</small></div></dialog>;
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
