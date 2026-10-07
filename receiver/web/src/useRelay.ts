import { useEffect, useRef, useState } from 'react';
import { createRequestId, mergeAlerts, parseTimeStandard, websocketURL, type Ack, type Alert, type Device, type Identity, type Notifier, type Stream, type TimeStandard } from './protocol';
import type { Login } from './account';
import { recordConnectionEvent, type ConnectionPhase } from './connectionDiagnostics';
// Keep every operation awaiting its result; bound only completed history.
const retainAcks = (items: Ack[]) => { let history=0; return items.filter(item => ['pending','forwarded'].includes(item.phase ?? '') || history++ < 100); };
export function useRelay(login: Login | null) {
  const scope = login ? `${login.account.accountId}:${login.device.deviceId}:${login.token}` : '';
  const [dataScope, setDataScope] = useState('');
  const [status, setStatus] = useState('未连接');
  const [connected, setConnected] = useState(false);
  const [devices, setDevices] = useState<Device[]>([]);
  const [registered, setRegistered] = useState<Identity[]>([]);
  const [notifiers, setNotifiers] = useState<Notifier[]>([]);
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [receipts, setReceipts] = useState<Record<string,string[]>>({});
  const [acks, setAcks] = useState<Ack[]>([]);
  const [timeStandard, setTimeStandard] = useState<TimeStandard | null>(null);
  const [streams, setStreams] = useState<Stream[]>([]);
  const [expiredToken, setExpiredToken] = useState('');
  const transport = useRef<WebSocket | null>(null);
  const pending = useRef(new Map<string,{key:string;deadline:number}>());
  const refresh = useRef<() => void>(() => {});
  const suspend = useRef<() => void>(() => {});
  useEffect(() => {
    setDataScope(scope); setDevices([]); setRegistered([]); setNotifiers([]); setAlerts([]); setAcks([]); setReceipts({}); setTimeStandard(null); setStreams([]); setExpiredToken(''); setConnected(false);
    if (!login) { setStatus('未连接'); return; }
    let stopped = false, terminal = false, authenticated = false, ws: WebSocket | null = null;
    let lastResponse = performance.now(), opened = 0, retry = 1_000, nextAttempt = 0;
    let attemptId = '', attempt = 0, phase: ConnectionPhase = 'connecting', socketOpened: number | null = null;
    const log = (event: Parameters<typeof recordConnectionEvent>[0]['event'], details: Partial<Parameters<typeof recordConnectionEvent>[0]> = {}) => {
      if (attemptId) recordConnectionEvent({ attemptId, attempt, phase, event, elapsedMs: Math.round(performance.now() - opened),
        ...(socketOpened === null ? {} : { openElapsedMs: Math.round(performance.now() - socketOpened) }), ...details });
    };
    let historyAbort: AbortController | null = null;
    const disconnect = (text: string, reason: Parameters<typeof recordConnectionEvent>[0]['reason'] = 'local-close') => {
      if (ws) log('disconnect', { reason, readyState: ws.readyState });
      pending.current.clear();
      if (!stopped) setAcks(old => old.map(ack => ['pending','forwarded'].includes(ack.phase ?? '') ? {...ack,phase:'uncertain',success:false,reason:'连接中断，执行结果待核实'} : ack));
      authenticated = false; setConnected(false); setStatus(text);
      setDevices([]); setStreams([]); setNotifiers(items => items.map(notifier => ({...notifier,online:false})));
      const old = ws; ws = null; transport.current = null;
      if (old) { old.onclose = null; old.onerror = null; old.onmessage = null; old.onopen = null; old.close(); }
      nextAttempt = performance.now() + retry; retry = Math.min(retry * 2, 30_000);
      if (!stopped && !terminal) log('retry-scheduled', { retryInMs: Math.round(nextAttempt - performance.now()) });
    };
    const history = async () => {
      historyAbort?.abort(); const abort = new AbortController(); historyAbort = abort;
      try {
        const response = await fetch('/api/alerts?limit=100', { headers: { Authorization: `Bearer ${login.token}` }, signal: abort.signal, cache: 'no-store' });
        if (stopped || abort.signal.aborted) return;
        if (response.status === 401) { terminal = true; setExpiredToken(login.token); disconnect('登录已失效，请重新登录', 'history-unauthorized'); return; }
        if (!response.ok) throw new Error('历史记录读取失败');
        const data = await response.json();
        if (!stopped && !abort.signal.aborted && Array.isArray(data.alerts)) setAlerts(existing => mergeAlerts(existing, data.alerts));
      } catch { if (!stopped && !abort.signal.aborted) setStatus('连接已建立，历史记录读取失败'); }
    };
    suspend.current = () => { terminal = true; historyAbort?.abort(); disconnect('正在更新会话', 'session-rotation'); };
    refresh.current = () => { if (authenticated) { ws?.send(JSON.stringify({type:'get-devices'})); ws?.send(JSON.stringify({type:'get-notification-scopes'})); ws?.send(JSON.stringify({type:'get-time-standard'})); ws?.send(JSON.stringify({type:'get-streams'})); void history(); } };
    const connect = () => {
      if (stopped || terminal) return;
      setStatus('连接中'); opened = performance.now();
      attemptId = createRequestId(); attempt++; phase = 'connecting'; socketOpened = null;
      log('connect-start');
      try {
        const url = new URL(websocketURL(location.origin)); url.searchParams.set('traceId', attemptId);
        ws = new WebSocket(url.href);
      } catch (e) { log('connect-error', { reason: 'invalid-url' }); terminal = true; setStatus((e as Error).message); return; }
      const current = ws; transport.current = current;
      current.onopen = () => {
        if (stopped || ws !== current) return;
        socketOpened = performance.now(); phase = 'authenticating'; log('socket-open');
        current.send(JSON.stringify({ type:'auth', token:login.token })); log('auth-sent');
      };
      current.onmessage = event => {
        if (stopped || ws !== current || typeof event.data !== 'string') return;
        try {
          const m = JSON.parse(event.data);
          if (m.type === 'kicked' || m.type === 'session-revoked') { terminal = true; setExpiredToken(login.token); disconnect('登录已失效，请重新登录', 'session-revoked'); return; }
          if (m.type === 'auth-result') {
            if (!m.success) { log('auth-rejected', { reason: m.reason === 'auth timeout' ? 'auth-timeout' : m.reason === 'invalid session' ? 'invalid-session' : 'server-rejected' }); terminal = true; setExpiredToken(login.token); disconnect('登录已失效，请重新登录', 'server-rejected'); return; }
            log('auth-success'); phase = 'authenticated';
            authenticated = true; retry = 1_000; setConnected(true); setStatus('已连接'); void history();
            setTimeStandard(parseTimeStandard(m.timeStandard));
          }
          if (!authenticated) return;
          if (!['auth-result','heartbeat-ack','device-list','notification-scopes','alert','command-ack','notification-scope-result','notification-receipt','screenshot-data','time-standard','time-standard-result','stream-list'].includes(m.type)) return;
          lastResponse = performance.now();
          switch(m.type) {
            case 'time-standard': { const value = parseTimeStandard(m); if (value) setTimeStandard(value); break; }
            case 'heartbeat-ack': if (typeof m.serverTime === 'string' && Number.isFinite(Date.parse(m.serverTime))) setTimeStandard(old => old ? {...old,serverTime:m.serverTime} : null); break;
            case 'device-list': if (Array.isArray(m.devices)) setDevices(m.devices); break;
            case 'stream-list': if (Array.isArray(m.streams)) setStreams(m.streams); break;
            case 'notification-scopes': if (Array.isArray(m.detectors) && Array.isArray(m.notifiers)) { setRegistered(m.detectors); setNotifiers(m.notifiers); } break;
            case 'alert': setAlerts(old => mergeAlerts(old, [m])); break;
            case 'command-ack': case 'notification-scope-result': case 'time-standard-result': if (m.phase==='completed'||m.success===false||m.type!=='command-ack') pending.current.delete(m.requestId); setAcks(old => retainAcks([{...m,phase:m.success===false?'completed':m.phase},...old.filter(a => a.requestId !== m.requestId)])); break;
            case 'notification-receipt': setReceipts(old => ({...old,[m.alertId]: [...new Set([...(old[m.alertId] ?? []),m.notifierId])] })); break;
            case 'screenshot-data': void history(); break;
          }
        } catch { setStatus('收到无效消息'); }
      };
      current.onerror = () => { if (ws === current && !stopped) { log('socket-error', { readyState: current.readyState }); disconnect('连接断开，等待重试', 'transport-error'); } };
      current.onclose = event => {
        if (ws !== current || stopped) return;
        const reason = event.reason === 'auth timeout' ? 'auth-timeout' : event.reason === 'invalid session' ? 'invalid-session'
          : event.reason === 'session revoked' ? 'session-revoked' : 'transport-close';
        log('socket-close', { code: event.code, wasClean: event.wasClean, reason });
        if (event.code === 4001 || event.code === 4003 || event.code === 4401) { terminal = true; setExpiredToken(login.token); }
        disconnect(terminal ? '登录已失效，请重新登录' : '连接关闭，等待重试', reason);
      };
    };
    connect();
    const timer = setInterval(() => {
      const now = performance.now();
      for (const [id,request] of pending.current) if (now>=request.deadline) { pending.current.delete(id); setAcks(old=>old.map(ack=>ack.requestId===id?{...ack,phase:'uncertain',success:false,reason:'执行回执超时，请核对节点状态'}:ack)); }
      if (authenticated && now - lastResponse > 45_000) { log('timeout', { reason: 'service-timeout' }); disconnect('服务响应超时，等待重试', 'service-timeout'); }
      if (ws && !authenticated && now - opened > 12_000) {
        const reason = socketOpened === null ? 'connect-timeout' : 'auth-timeout';
        log('timeout', { reason, readyState: ws.readyState }); disconnect('认证超时，等待重试', reason);
      }
      if (!ws && now >= nextAttempt && !terminal) connect();
      if (authenticated && ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({type:'heartbeat-console'}));
    }, 3_000);
    return () => { stopped = true; clearInterval(timer); historyAbort?.abort(); refresh.current = () => {}; suspend.current = () => {}; disconnect('未连接', 'effect-cleanup'); };
  }, [login]);
  function send(message: Record<string, unknown>): string {
    const requestId = createRequestId();
    if (dataScope !== scope || !connected || transport.current?.readyState !== WebSocket.OPEN) { setStatus('尚未连接，请稍后重试'); return ''; }
    const key=JSON.stringify([message.type,message.targetDeviceId,message.targetNotifierId,message.targetSourceId,message.command,message.key]);
    for (const [id,item] of pending.current) if (item.key===key) return id;
    try { transport.current.send(JSON.stringify({...message,requestId})); } catch { setStatus('发送失败，请重试'); return ''; }
    pending.current.set(requestId,{key,deadline:performance.now()+20_000});
    setAcks(old => retainAcks([{requestId,phase:'pending',success:true,reason:'等待执行回执',command:message.type === 'set-config' ? `set-config:${message.key}` : String(message.command ?? message.type),targetDeviceId: String(message.targetDeviceId ?? message.targetNotifierId ?? ''),targetSourceId:message.targetSourceId as string|undefined},...old]));
    return requestId;
  }
  const current = dataScope === scope;
  return { status: current ? status : '未连接', connected: current && connected, devices: current ? devices : [], registered: current ? registered : [], notifiers: current ? notifiers : [], alerts: current ? alerts : [], receipts: current ? receipts : {}, acks: current ? acks : [], timeStandard: current ? timeStandard : null, streams: current ? streams : [], authExpired: !!login && expiredToken === login.token, send, refresh: () => refresh.current(), suspend: () => suspend.current() };
}
