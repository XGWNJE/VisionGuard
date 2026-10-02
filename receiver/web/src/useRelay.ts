import { useEffect, useRef, useState } from 'react';
import { createRequestId, mergeAlerts, parseTimeStandard, websocketURL, type Ack, type Alert, type Device, type Identity, type Notifier, type Stream, type TimeStandard } from './protocol';
import type { Login } from './account';
export function useRelay(login: Login | null) {
  const scope = login ? `${login.account.accountId}:${login.device.deviceId}` : '';
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
  const refresh = useRef<() => void>(() => {});
  const suspend = useRef<() => void>(() => {});
  useEffect(() => {
    setDataScope(scope); setDevices([]); setRegistered([]); setNotifiers([]); setAlerts([]); setAcks([]); setReceipts({}); setTimeStandard(null); setStreams([]); setExpiredToken(''); setConnected(false);
    if (!login) { setStatus('未连接'); return; }
    let stopped = false, terminal = false, authenticated = false, ws: WebSocket | null = null;
    let lastResponse = performance.now(), opened = 0, retry = 1_000, nextAttempt = 0;
    let historyAbort: AbortController | null = null;
    const disconnect = (text: string) => {
      authenticated = false; setConnected(false); setStatus(text);
      const old = ws; ws = null; transport.current = null;
      if (old) { old.onclose = null; old.onerror = null; old.onmessage = null; old.onopen = null; old.close(); }
      nextAttempt = performance.now() + retry; retry = Math.min(retry * 2, 30_000);
    };
    const history = async () => {
      historyAbort?.abort(); const abort = new AbortController(); historyAbort = abort;
      try {
        const response = await fetch('/api/alerts?limit=100', { headers: { Authorization: `Bearer ${login.token}` }, signal: abort.signal, cache: 'no-store' });
        if (stopped || abort.signal.aborted) return;
        if (response.status === 401) { terminal = true; setExpiredToken(login.token); disconnect('登录已失效，请重新登录'); return; }
        if (!response.ok) throw new Error('历史记录读取失败');
        const data = await response.json();
        if (!stopped && !abort.signal.aborted && Array.isArray(data.alerts)) setAlerts(existing => mergeAlerts(existing, data.alerts));
      } catch { if (!stopped && !abort.signal.aborted) setStatus('连接已建立，历史记录读取失败'); }
    };
    suspend.current = () => { terminal = true; historyAbort?.abort(); disconnect('正在更新会话'); };
    refresh.current = () => { if (authenticated) { ws?.send(JSON.stringify({type:'get-devices'})); ws?.send(JSON.stringify({type:'get-notification-scopes'})); ws?.send(JSON.stringify({type:'get-time-standard'})); ws?.send(JSON.stringify({type:'get-streams'})); void history(); } };
    const connect = () => {
      if (stopped || terminal) return;
      setStatus('连接中'); opened = performance.now();
      try { ws = new WebSocket(websocketURL(location.origin)); } catch (e) { terminal = true; setStatus((e as Error).message); return; }
      const current = ws; transport.current = current;
      current.onopen = () => { if (!stopped && ws === current) current.send(JSON.stringify({ type:'auth', token:login.token })); };
      current.onmessage = event => {
        if (stopped || ws !== current || typeof event.data !== 'string') return;
        try {
          const m = JSON.parse(event.data);
          if (m.type === 'kicked' || m.type === 'session-revoked') { terminal = true; setExpiredToken(login.token); disconnect('登录已失效，请重新登录'); return; }
          if (m.type === 'auth-result') {
            if (!m.success) { terminal = true; setExpiredToken(login.token); disconnect('登录已失效，请重新登录'); return; }
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
            case 'command-ack': case 'notification-scope-result': case 'time-standard-result': setAcks(old => [m,...old.filter(a => a.requestId !== m.requestId)].slice(0,20)); break;
            case 'notification-receipt': setReceipts(old => ({...old,[m.alertId]: [...new Set([...(old[m.alertId] ?? []),m.notifierId])] })); break;
            case 'screenshot-data': void history(); break;
          }
        } catch { setStatus('收到无效消息'); }
      };
      current.onerror = () => { if (ws === current && !stopped) disconnect('连接断开，等待重试'); };
      current.onclose = event => { if (ws === current && !stopped) { if (event.code === 4001 || event.code === 4003 || event.code === 4401) { terminal = true; setExpiredToken(login.token); } disconnect(terminal ? '登录已失效，请重新登录' : '连接关闭，等待重试'); } };
    };
    connect();
    const timer = setInterval(() => {
      const now = performance.now();
      if (authenticated && now - lastResponse > 45_000) disconnect('服务响应超时，等待重试');
      if (ws && !authenticated && now - opened > 12_000) disconnect('认证超时，等待重试');
      if (!ws && now >= nextAttempt && !terminal) connect();
      if (authenticated && ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({type:'heartbeat-console'}));
    }, 3_000);
    return () => { stopped = true; clearInterval(timer); historyAbort?.abort(); refresh.current = () => {}; suspend.current = () => {}; disconnect('未连接'); };
  }, [login]);
  function send(message: Record<string, unknown>): string {
    const requestId = createRequestId();
    if (dataScope !== scope || !connected || transport.current?.readyState !== WebSocket.OPEN) { setStatus('尚未连接，请稍后重试'); return ''; }
    transport.current.send(JSON.stringify({...message,requestId}));
    setAcks(old => [{requestId,phase:'pending',success:true,reason:'等待执行回执',command:message.type === 'set-time-standard' ? '统一时间' : undefined,targetDeviceId: String(message.targetDeviceId ?? message.targetNotifierId ?? '')},...old].slice(0,20));
    return requestId;
  }
  const current = dataScope === scope;
  return { status: current ? status : '未连接', connected: current && connected, devices: current ? devices : [], registered: current ? registered : [], notifiers: current ? notifiers : [], alerts: current ? alerts : [], receipts: current ? receipts : {}, acks: current ? acks : [], timeStandard: current ? timeStandard : null, streams: current ? streams : [], authExpired: !!login && expiredToken === login.token, send, refresh: () => refresh.current(), suspend: () => suspend.current() };
}
