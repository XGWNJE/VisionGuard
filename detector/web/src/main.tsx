import { useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { createRoot } from 'react-dom/client';
import { Camera, ChevronRight, CircleHelp, Info, RefreshCw, Square } from 'lucide-react';
import './style.css';
import { browserDeviceModel, CameraSettingsStore, indexedDbIdentityPort, readOrCreateDeviceIdentity } from './device';
import { cameraContextIssue } from './protocol';
import type { CameraSettings, LoginResult } from './protocol';
import { ControlClient, loginCamera, type ControlPorts, type ControlSocket } from './control';
import { MediaPublisher, type MediaPorts, type MediaSocket } from './media';
import { CameraSource, listCameras, type CameraDevice } from './cameraSource';
import { setProtocolVersion, versionHeaders } from './session';

const SESSION_KEY = 'visionguard.camera.session';
const REMEMBER_KEY = 'visionguard.camera.remember';
const RESPONSE_MESSAGES: Record<number, string> = { 400: '请检查填写内容', 401: '账号或密码不正确', 403: '没有权限执行此操作',
  404: '账号、设备或关联目标不存在', 409: '操作冲突，请刷新后重试', 429: '尝试过于频繁，请稍后重试' };

async function fetchJson(path: string, init?: { method?: string; body?: string }, token?: string): Promise<any> {
  const response = await fetch(path, { method: init?.method ?? (init?.body === undefined ? 'GET' : 'POST'),
    headers: { ...versionHeaders(), ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(init?.body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: init?.body, cache: 'no-store', credentials: 'omit' });
  let data: any;
  try { data = await response.json(); } catch { throw new Error('服务暂不可用'); }
  if (!response.ok || data?.ok === false) {
    if (response.status === 401 && path !== '/api/account/login') throw new Error('登录已失效，请重新登录');
    throw new Error(RESPONSE_MESSAGES[response.status] ?? (response.status >= 500 ? '服务暂不可用' : '请求失败'));
  }
  return data;
}

function socketFactory<T extends ControlSocket | MediaSocket>(url: string): T {
  return new WebSocket(url) as unknown as T;
}

function readStoredSession(): LoginResult | undefined {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    const parsed = raw ? JSON.parse(raw) : undefined;
    if (!(parsed && typeof parsed.token === 'string' && parsed.device?.component === 'web-camera')) return undefined;
    setProtocolVersion(parsed.version);
    return parsed as LoginResult;
  } catch { return undefined; }
}

function readRemembered(): boolean {
  try { return localStorage.getItem(REMEMBER_KEY) === 'true'; } catch { return false; }
}

function localStorageStore() {
  return { read: (key: string) => { try { return localStorage.getItem(key); } catch { return null; } },
    write: (key: string, value: string) => { localStorage.setItem(key, value); } };
}

export function App() {
  const contextIssue = useMemo(() => cameraContextIssue(location), []);
  const [session, setSession] = useState<LoginResult | undefined>(readStoredSession);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [remember, setRemember] = useState(readRemembered);
  const [loginError, setLoginError] = useState('');
  const [busy, setBusy] = useState(false);
  const [deviceId, setDeviceId] = useState('');
  const [devices, setDevices] = useState<CameraDevice[]>([]);
  const [notice, setNotice] = useState('');
  const [help, setHelp] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [, setRevision] = useState(0);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const cameraRef = useRef<CameraSource | undefined>(undefined);
  const mediaRef = useRef<MediaPublisher | undefined>(undefined);
  const controlRef = useRef<ControlClient | undefined>(undefined);
  const streamingRef = useRef(false);
  const frameTimerRef = useRef<number | undefined>(undefined);
  const stopStreamingRef = useRef<(reason: 'user' | 'background' | 'locked') => void>(() => {});
  const settingsStore = useMemo(() => new CameraSettingsStore(localStorageStore()), []);
  const bump = () => setRevision(revision => revision + 1);

  useEffect(() => {
    if (!session) return;
    const mediaPorts: MediaPorts = { origin: location.origin, openSocket: url => socketFactory(url), now: () => performance.now(),
      setTimer: (callback, milliseconds) => window.setInterval(callback, milliseconds), clearTimer: handle => window.clearInterval(handle as number),
      onChange: bump, onError: setNotice };
    const media = new MediaPublisher(() => session.token, mediaPorts);
    const controlPorts: ControlPorts = { origin: location.origin, openSocket: url => socketFactory(url), now: () => Date.now(),
      setTimer: (callback, milliseconds) => window.setInterval(callback, milliseconds), clearTimer: handle => window.clearInterval(handle as number),
      fetchJson: (path, init) => fetchJson(path, init, session.token),
      isStreaming: () => streamingRef.current,
      onStreamCommand: (command, done) => {
        if (command === 'stop-stream') { stopStreamingRef.current('user'); done(true, '已停止推流'); return; }
        void startStreaming().then(reason => done(!reason, reason ?? '摄像头已启动，等待画面通道；收帧状态请查看统计'));
      },
      saveConfig: (key, value) => settingsStore.save(key as keyof CameraSettings, value),
      cameraState: () => ({ cameraApp: document.visibilityState === 'visible' ? 'foreground' : 'background',
        cameraPermission: cameraRef.current?.active ? 'granted' : 'required' }),
      onState: bump, onUnauthorized: () => { sessionStorage.removeItem(SESSION_KEY); setSession(undefined); } };
    const control = new ControlClient(session, settingsStore, controlPorts, bump);
    mediaRef.current = media;
    controlRef.current = control;
    control.start();
    void listCameras().then(setDevices).catch(() => setDevices([]));
    const onHidden = () => { if (document.visibilityState === 'hidden') stopStreamingRef.current('background'); };
    document.addEventListener('visibilitychange', onHidden);
    return () => {
      document.removeEventListener('visibilitychange', onHidden);
      stopStreamingRef.current('user');
      control.stop('page closed');
      media.stop('user');
      controlRef.current = undefined;
      mediaRef.current = undefined;
    };
  }, [session, settingsStore]);

  async function submitLogin(event: FormEvent): Promise<void> {
    event.preventDefault();
    setLoginError('');
    setBusy(true);
    try {
      const identity = await readOrCreateDeviceIdentity(indexedDbIdentityPort());
      const next = await loginCamera(username.trim(), password, identity, browserDeviceModel(navigator.platform), fetchJson);
      setProtocolVersion(next.version);
      sessionStorage.setItem(SESSION_KEY, JSON.stringify(next));
      try { localStorage.setItem(REMEMBER_KEY, remember ? 'true' : 'false'); } catch { /* 存储不可用时不影响登录 */ }
      setSession(next);
    } catch (error) {
      setLoginError(error instanceof Error ? error.message : '登录失败');
    } finally { setBusy(false); }
  }

  function logout(): void {
    stopStreamingRef.current('user');
    sessionStorage.removeItem(SESSION_KEY);
    setPassword('');
    setSession(undefined);
  }

  async function startStreaming(): Promise<string | undefined> {
    const media = mediaRef.current, control = controlRef.current;
    if (!media || !control) return '登录状态不可用';
    if (!control.state.stream?.targetDeviceId) return '请先在页面上关联视觉推理节点';
    if (streamingRef.current) return undefined;
    if (!videoRef.current) return '页面未就绪，请重试';
    const source = cameraRef.current ?? (cameraRef.current = new CameraSource(videoRef.current));
    const failure = await source.open(deviceId || undefined, settingsStore.current.cameraResolution);
    if (failure) return failure;
    streamingRef.current = true;
    setStreaming(true);
    media.start();
    const loop = async () => {
      if (!streamingRef.current) return;
      // 采集时间取整：服务端只接受安全整数毫秒，小数会被整帧拒绝。
      const capturedAt = Math.round(performance.now());
      if (media.due(capturedAt)) {
        const frame = await source.capture(settingsStore.current.cameraResolution, capturedAt);
        if (frame && !media.publish(frame)) setNotice('画面处理失败，已跳过这一帧');
      }
      if (streamingRef.current) frameTimerRef.current = window.setTimeout(() => void loop(), 40);
    };
    frameTimerRef.current = window.setTimeout(() => void loop(), 0);
    return undefined;
  }

  function stopStreaming(reason: 'user' | 'background' | 'locked'): void {
    if (frameTimerRef.current !== undefined) { window.clearTimeout(frameTimerRef.current); frameTimerRef.current = undefined; }
    if (!streamingRef.current) return;
    streamingRef.current = false;
    setStreaming(false);
    mediaRef.current?.stop(reason);
    cameraRef.current?.close();
  }
  stopStreamingRef.current = stopStreaming;

  if (contextIssue) return (
    <main className="page notice-only"><section className="panel"><h1>相机推流节点</h1><p className="error" role="alert">{contextIssue}</p></section></main>
  );

  if (!session) return (
    <main className="page login-page">
      <form className="panel login-panel" onSubmit={event => void submitLogin(event)}>
        <div className="brand"><Camera size={28} aria-hidden="true" /><h1>相机推流节点</h1></div>
        <label>账号<input value={username} onChange={event => setUsername(event.target.value)} autoComplete="username" required /></label>
        <label>密码<input type="password" value={password} onChange={event => setPassword(event.target.value)} autoComplete="current-password" required /></label>
        <label className="checkbox"><input type="checkbox" checked={remember} onChange={event => setRemember(event.target.checked)} />记住账号（仅本机浏览器）</label>
        {loginError && <p className="error" role="alert">{loginError}</p>}
        <button type="submit" className="primary" disabled={busy}>{busy ? '正在登录…' : '登录'}</button>
        <p className="subtle">使用与视觉推理节点相同的账号。浏览器只在 HTTPS（或本机 localhost）下提供摄像头。</p>
      </form>
    </main>
  );

  const control = controlRef.current?.state;
  const media = mediaRef.current?.current;
  const settings = settingsStore.current;
  const target = control?.targets.find(item => item.deviceId === control?.stream?.targetDeviceId);

  return (
    <main className="page">
      <header className="topbar">
        <div className="identity"><Camera size={20} aria-hidden="true" /><strong>{session.device.deviceName}</strong>
          <span className={`status-dot ${control?.connected ? 'online' : ''}`} />{control?.connected ? '在线' : '离线'}</div>
        <div className="actions">
          <button type="button" className="icon" aria-label="相机帮助" onClick={() => setHelp(true)}><CircleHelp size={18} /></button>
          <button type="button" className="icon" onClick={logout}>退出</button>
        </div>
      </header>

      <section className="panel preview-panel" aria-label="实时摄像头画面">
        <video ref={videoRef} className={streaming && !settings.cameraHidePreview ? '' : 'hidden'} playsInline muted autoPlay />
        {(!streaming || settings.cameraHidePreview) && <p className="placeholder">{settings.cameraHidePreview && streaming ? '预览已关闭' : streaming ? '等待画面' : '未推流'}</p>}
      </section>

      <section className="panel control-panel">
        <p className="status">{control?.status ?? '正在连接'}{notice && <span className="warning"> · {notice}</span>}</p>
        <dl className="stats">
          <div><dt>入队</dt><dd>{media?.sentFrames ?? 0}</dd></div>
          <div><dt>收帧</dt><dd>{media?.acknowledgedFrames ?? 0}</dd></div>
          <div><dt>丢弃</dt><dd>{media?.droppedFrames ?? 0}/{media?.relayDroppedFrames ?? 0}</dd></div>
        </dl>
        <div className="field">
          <span>目标</span>
          <div className="picker">
            <select aria-label="目标视觉推理节点" value={control?.stream?.targetDeviceId ?? ''} disabled={streaming || !control?.connected}
              onChange={event => { const targetId = event.target.value; void controlRef.current?.bind(targetId).then(reason => reason && setNotice(reason)); }}>
              <option value="">{control?.targetsLoading ? '加载中…' : control?.targetsLoadFailed ? '加载失败' : control?.targets.length ? '选择视觉推理节点' : '暂无节点'}</option>
              {(control?.targets ?? []).map(item => <option key={item.deviceId} value={item.deviceId}>{item.deviceName}</option>)}
            </select>
            <button type="button" className="icon" aria-label="刷新目标" disabled={!control?.connected || control?.targetsLoading}
              onClick={() => void controlRef.current?.refreshTargets()}><RefreshCw size={16} /></button>
          </div>
        </div>
        <div className="field">
          <span>规格</span>
          <div className="segmented">
            {(['480p', '720p'] as const).map(resolution => (
              <button key={resolution} type="button" disabled={streaming} aria-pressed={settings.cameraResolution === resolution}
                className={settings.cameraResolution === resolution ? 'selected' : ''}
                onClick={() => { const failure = settingsStore.save('cameraResolution', resolution); if (failure) setNotice(failure); }}>{resolution === '720p' ? '720P' : '640×480'}</button>
            ))}
          </div>
        </div>
        <div className="field">
          <span>摄像头</span>
          <select aria-label="摄像头" value={deviceId} disabled={streaming} onChange={event => setDeviceId(event.target.value)}>
            <option value="">默认（后置优先）</option>
            {devices.map((device, index) => <option key={device.deviceId || index} value={device.deviceId}>{device.label}</option>)}
          </select>
        </div>
        {!streaming && devices.length === 0 && <p className="warning" role="status">未检测到摄像头设备：请确认本机有摄像头并已授权，浏览器只在 HTTPS 或本机 localhost 下提供设备列表。</p>}
        <label className="checkbox"><input type="checkbox" checked={!settings.cameraHidePreview}
          onChange={event => { const failure = settingsStore.save('cameraHidePreview', String(!event.target.checked)); if (failure) setNotice(failure); }} />显示本机预览</label>
        <p className="subtle">降低窗口亮度由系统控制，页面不做亮度调节。</p>
        {streaming
          ? <button type="button" className="primary danger" onClick={() => stopStreaming('user')}><Square size={16} />停止推流</button>
          : <button type="button" className="primary" disabled={!control?.connected || !control?.stream?.targetDeviceId}
              onClick={() => void startStreaming().then(reason => reason && setNotice(reason))}><ChevronRight size={16} />开始推流</button>}
        {target && <p className="subtle">当前推理来源：{control?.stream?.sourceName || target.deviceName}</p>}
      </section>

      {help && (
        <section className="panel help" role="dialog" aria-label="相机帮助">
          <div className="help-head"><h2>相机帮助</h2><button type="button" className="icon" onClick={() => setHelp(false)}>返回</button></div>
          <h3>开始推流</h3><p>相机与视觉推理节点登录同一账号，先关联目标再开始。规格与摄像头在推流期间不能更换；页面隐藏或锁屏会立即停止推流，回到页面需手动开始。</p>
          <h3>画面与规格</h3><p>640×480 与 720P 是上限，最高 5 帧/秒；实际采集尺寸由浏览器与摄像头能力决定，超出上限时等比缩放，方向跟随屏幕方向。</p>
          <h3>统计</h3><p>入队表示本机发送队列接纳，收帧只表示服务中继收件，不代表视觉推理节点已接收或完成推理；丢弃的两个数值依次为本机 / 服务。</p>
          <h3>前台与授权</h3><p>浏览器会暂停后台标签页的摄像头与网络，因此不做后台保活。摄像头授权、HTTPS 与系统权限必须在本机满足，控制台远程开始推流不能绕过这些前提。</p>
          <h3>当前状态</h3><p>{control?.status ?? '正在连接'} · 入队 {media?.sentFrames ?? 0} · 收帧 {media?.acknowledgedFrames ?? 0} · 未采样 {media?.sampledOutFrames ?? 0} · 媒体会话 {media?.sessions ?? 0}</p>
          <p className="subtle"><Info size={14} aria-hidden="true" /> 设备名称、关联与解绑在控制台完成；本机名称上限 64 个字符。</p>
        </section>
      )}
    </main>
  );
}

createRoot(document.getElementById('root')!).render(<App />);
