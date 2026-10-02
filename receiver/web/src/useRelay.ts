import { useEffect, useRef, useState } from 'react';
import { createRequestId, mergeAlerts, parseTimeStandard, websocketURL, type Ack, type Alert, type Device, type Identity, type Notifier, type TimeStandard } from './protocol';
export type Login = { channel: string; deviceId: string; apiKey: string; testMode?: boolean };
export function useRelay(login: Login | null) {
  const [status, setStatus] = useState('未连接');
  const [connected, setConnected] = useState(false);
  const [devices, setDevices] = useState<Device[]>([]);
  const [registered, setRegistered] = useState<Identity[]>([]);
  const [notifiers, setNotifiers] = useState<Notifier[]>([]);
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [receipts, setReceipts] = useState<Record<string,string[]>>({});
  const [acks, setAcks] = useState<Ack[]>([]);
  const [timeStandard, setTimeStandard] = useState<TimeStandard | null>(null);
  const transport = useRef<WebSocket | null>(null);
  const refresh = useRef<() => void>(() => {});
  useEffect(() => {
    setDevices([]); setRegistered([]); setNotifiers([]); setAlerts([]); setAcks([]); setReceipts({}); setTimeStandard(null); setConnected(false);
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
        const response = await fetch('/api/alerts?limit=100', { headers: { 'X-API-Key': login.apiKey }, signal: abort.signal, cache: 'no-store' });
        if (!response.ok) throw new Error('历史记录读取失败');
        const data = await response.json();
        if (!stopped && !abort.signal.aborted && Array.isArray(data.alerts)) setAlerts(existing => mergeAlerts(existing, data.alerts));
      } catch { if (!stopped && !abort.signal.aborted) setStatus('连接已建立，历史记录读取失败'); }
    };
    refresh.current = () => { if (authenticated) { ws?.send(JSON.stringify({type:'get-devices'})); ws?.send(JSON.stringify({type:'get-notification-scopes'})); ws?.send(JSON.stringify({type:'get-time-standard'})); void history(); } };
    const connect = () => {
      if (stopped || terminal) return;
      setStatus('连接中'); opened = performance.now();
      try { ws = new WebSocket(websocketURL(location.origin, login.testMode)); } catch (e) { terminal = true; setStatus((e as Error).message); return; }
      const current = ws; transport.current = current;
      current.onopen = () => { if (!stopped && ws === current) current.send(JSON.stringify({ type:'auth', ...login, deviceName:'Web 控制台', role:'console', nodeType:'console', platform:'web' })); };
      current.onmessage = event => {
        if (stopped || ws !== current || typeof event.data !== 'string') return;
        try {
          const m = JSON.parse(event.data);
          if (m.type === 'kicked') { terminal = true; disconnect('此身份已在另一处连接，请重新登录'); return; }
          if (m.type === 'auth-result') {
            if (!m.success) { terminal = true; disconnect('身份或通道不匹配，请重新登录'); return; }
            authenticated = true; retry = 1_000; setConnected(true); setStatus('已连接'); void history();
            setTimeStandard(parseTimeStandard(m.timeStandard));
          }
          if (!authenticated) return;
          if (!['auth-result','heartbeat-ack','device-list','notification-scopes','alert','command-ack','notification-scope-result','notification-receipt','screenshot-data','time-standard','time-standard-result'].includes(m.type)) return;
          lastResponse = performance.now();
          switch(m.type) {
            case 'time-standard': { const value = parseTimeStandard(m); if (value) setTimeStandard(value); break; }
            case 'heartbeat-ack': if (typeof m.serverTime === 'string' && Number.isFinite(Date.parse(m.serverTime))) setTimeStandard(old => old ? {...old,serverTime:m.serverTime} : null); break;
            case 'device-list': if (Array.isArray(m.devices)) setDevices(m.devices); break;
            case 'notification-scopes': if (Array.isArray(m.detectors) && Array.isArray(m.notifiers)) { setRegistered(m.detectors); setNotifiers(m.notifiers); } break;
            case 'alert': setAlerts(old => mergeAlerts(old, [m])); break;
            case 'command-ack': case 'notification-scope-result': case 'time-standard-result': setAcks(old => [m,...old.filter(a => a.requestId !== m.requestId)].slice(0,20)); break;
            case 'notification-receipt': setReceipts(old => ({...old,[m.alertId]: [...new Set([...(old[m.alertId] ?? []),m.notifierId])] })); break;
            case 'screenshot-data': void history(); break;
          }
        } catch { setStatus('收到无效消息'); }
      };
      current.onerror = () => { if (ws === current && !stopped) disconnect('连接断开，等待重试'); };
      current.onclose = () => { if (ws === current && !stopped) disconnect('连接关闭，等待重试'); };
    };
    connect();
    const timer = setInterval(() => {
      const now = performance.now();
      if (authenticated && now - lastResponse > 45_000) disconnect('服务响应超时，等待重试');
      if (ws && !authenticated && now - opened > 12_000) disconnect('认证超时，等待重试');
      if (!ws && now >= nextAttempt && !terminal) connect();
      if (authenticated && ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({type:'heartbeat-console',deviceId:login.deviceId}));
    }, 3_000);
    return () => { stopped = true; clearInterval(timer); historyAbort?.abort(); refresh.current = () => {}; disconnect('未连接'); };
  }, [login]);
  function send(message: Record<string, unknown>): string {
    const requestId = createRequestId();
    if (!connected || transport.current?.readyState !== WebSocket.OPEN) { setStatus('尚未连接，请稍后重试'); return ''; }
    transport.current.send(JSON.stringify({...message,requestId}));
    setAcks(old => [{requestId,phase:'pending',success:true,reason:'等待执行回执',command:message.type === 'set-time-standard' ? '统一时间' : undefined,targetDeviceId: String(message.targetDeviceId ?? message.targetNotifierId ?? '')},...old].slice(0,20));
    return requestId;
  }
  return { status, connected, devices, registered, notifiers, alerts, receipts, acks, timeStandard, send, refresh: () => refresh.current() };
}
