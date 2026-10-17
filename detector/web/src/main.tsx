import { useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { createRoot } from 'react-dom/client';
import { ThemeProvider } from '@/components/theme-provider';
import { CameraContextError, CameraLogin, CameraScreen } from './CameraInterface';
import './style.css';
import { browserDeviceModel, CameraSettingsStore, indexedDbIdentityPort, readOrCreateDeviceIdentity } from './device';
import { cameraContextIssue } from './protocol';
import type { CameraSettings, LoginResult } from './protocol';
import { ControlClient, loginCamera, type ControlPorts, type ControlSocket } from './control';
import { MediaPublisher, type MediaPorts, type MediaSocket } from './media';
import { CameraSource, listCameras, type CameraDevice } from './cameraSource';
import { setProtocolVersion, versionHeaders } from './session';
import { bootstrapProtocolVersion, debugLoginScreen, previewCredentials } from './previewSignIn';
import { installDefaultFullscreen, toggleFullscreen } from './fullscreen';

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

/** 登录并写入本页会话；登录页与本地预览内置登录共用。 */
async function loginAndStore(account: string, secret: string): Promise<LoginResult> {
  const identity = await readOrCreateDeviceIdentity(indexedDbIdentityPort());
  const next = await loginCamera(account.trim(), secret, identity, browserDeviceModel(navigator.platform), fetchJson);
  setProtocolVersion(next.version);
  sessionStorage.setItem(SESSION_KEY, JSON.stringify(next));
  return next;
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
  const [starting, setStarting] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [fullscreen, setFullscreen] = useState(Boolean(document.fullscreenElement));
  const [fullscreenError, setFullscreenError] = useState('');
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

  useEffect(() => installDefaultFullscreen(document, () => setFullscreen(Boolean(document.fullscreenElement))), []);
  async function changeFullscreen() {
    try { await toggleFullscreen(document); setFullscreenError(''); }
    catch (error) { setFullscreenError(error instanceof Error ? error.message : '浏览器未允许全屏，请重试'); }
  }

  useEffect(() => {
    let active = true;
    void (async () => {
      // 先把协议版本就位（取自 /health），否则部署实例上的登录会被 426 挡掉。
      await bootstrapProtocolVersion();
      if (!active || readStoredSession()) return;
      if (debugLoginScreen()) return; // ?login：停在登录界面调登录流程
      const preview = await previewCredentials();
      if (!active || !preview) return;
      try {
        const next = await loginAndStore(preview.username, preview.password);
        if (active) setSession(next);
      } catch (error) { if (active) setLoginError(`内置预览登录失败：${error instanceof Error ? error.message : '登录失败'}`); }
    })();
    return () => { active = false; };
  }, []);

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
      cameraRef.current = undefined;
    };
  }, [session, settingsStore]);

  async function submitLogin(event: FormEvent): Promise<void> {
    event.preventDefault();
    setLoginError('');
    setBusy(true);
    try {
      const next = await loginAndStore(username, password);
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

  if (contextIssue) return <CameraContextError message={contextIssue} />;
  if (!session) return <CameraLogin username={username} password={password} remember={remember} busy={busy} error={loginError}
    fullscreen={fullscreen} fullscreenError={fullscreenError} onFullscreen={() => void changeFullscreen()}
    onUsername={setUsername} onPassword={setPassword} onRemember={setRemember} onSubmit={event => void submitLogin(event)} />;

  function saveSetting(key: keyof CameraSettings, value: string) {
    const failure = settingsStore.save(key, value);
    setNotice(failure || ''); bump();
  }
  return <CameraScreen session={session} control={controlRef.current?.state} media={mediaRef.current?.current}
    fullscreen={fullscreen} fullscreenError={fullscreenError} onFullscreen={() => void changeFullscreen()}
    settings={settingsStore.current} streaming={streaming} starting={starting} notice={notice}
    devices={devices} deviceId={deviceId} videoRef={videoRef} onCamera={setDeviceId}
    onStart={() => { setNotice(''); setStarting(true); void startStreaming().then(reason => { if (reason) setNotice(reason); }).finally(() => setStarting(false)); }}
    onStop={() => stopStreaming('user')} onLogout={logout}
    onBind={async id => { const failure = await controlRef.current?.bind(id); setNotice(failure || ''); return failure; }}
    onRefresh={() => void controlRef.current?.refreshTargets()}
    onResolution={value => saveSetting('cameraResolution', value)}
    onPreview={value => saveSetting('cameraHidePreview', String(!value))} />;
}

createRoot(document.getElementById('root')!).render(<ThemeProvider storageKey="visionguard.camera.appearance"><App /></ThemeProvider>);
