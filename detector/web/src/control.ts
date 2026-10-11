/** 控制通道状态机：认证、心跳、目标列表、远程启停与配置。计时器与 socket 由外部注入，便于测试。 */

import type { CameraSettings, LoginResult, StreamInfo, TargetDevice, ControlState } from './protocol';
import { parseLogin, parseRemoteSettings, parseStreams, websocketURL } from './protocol';
import type { CameraSettingsStore } from './device';

export interface ControlSocket {
  readyState: number;
  send(data: string): void;
  close(code?: number, reason?: string): void;
  onopen: ((event: unknown) => void) | null;
  onmessage: ((event: { data: unknown }) => void) | null;
  onclose: ((event: unknown) => void) | null;
  onerror: ((event: unknown) => void) | null;
}

export interface ControlPorts {
  openSocket(url: string): ControlSocket;
  origin: string;
  now(): number;
  setTimer(callback: () => void, milliseconds: number): unknown;
  clearTimer(handle: unknown): void;
  fetchJson(path: string, init?: { method?: string; body?: string }): Promise<any>;
  /** 控制台的开始 / 停止推流；done 必须回真实执行结果。 */
  onStreamCommand(command: 'start-stream' | 'stop-stream', done: (success: boolean, reason: string) => void): void;
  /** 本机是否正在推流：心跳按此上报 isMonitoring，控制台据此判断能否远程改规格。 */
  isStreaming(): boolean;
  /** 保存本机配置，返回失败原因；成功返回 undefined。 */
  saveConfig(key: string, value: string): string | undefined;
  cameraState(): Record<string, string>;
  onState(state: ControlState): void;
  onUnauthorized(): void;
}

const RESPONSE_TIMEOUT_MS = 45_000;
const AUTH_TIMEOUT_MS = 12_000;
const RETRY_MS = 1000;
export const WEB_CAMERA_CAPABILITIES = ['video-publish', 'stream-control', 'camera-config', 'request-correlation'];

export class ControlClient {
  private socket?: ControlSocket;
  private authenticated = false;
  private openedAt = 0;
  private lastResponse = 0;
  private nextRetryAt = 0;
  private timer?: unknown;
  private closed = true;
  private stream?: StreamInfo;
  private targets: TargetDevice[] = [];
  private targetsLoading = false;
  private targetsLoadFailed = false;
  private status = '正在连接';
  private generation = 0;
  private readonly onChange: () => void;

  constructor(private readonly session: LoginResult, private readonly settings: CameraSettingsStore, private readonly ports: ControlPorts, onChange: () => void) {
    this.onChange = onChange;
  }

  get state(): ControlState {
    return { connected: this.authenticated, status: this.status, stream: this.stream,
      targets: this.targets, targetsLoading: this.targetsLoading, targetsLoadFailed: this.targetsLoadFailed,
      remoteSettings: this.settings.current, session: this.session };
  }

  get deviceName(): string { return this.session.device.deviceName; }

  start(): void {
    if (!this.closed) return;
    this.closed = false;
    this.connect();
    const tick = () => {
      if (this.closed) return;
      const now = this.ports.now();
      if (!this.socket && now >= this.nextRetryAt) this.connect();
      else if (this.socket && !this.authenticated && now - this.openedAt > AUTH_TIMEOUT_MS) this.drop('连接超时，正在重连');
      else if (this.socket && this.authenticated && now - this.lastResponse > RESPONSE_TIMEOUT_MS) this.drop('连接无响应，正在重连');
      if (this.socket && this.authenticated) this.socket.send(JSON.stringify({ type: 'heartbeat', isMonitoring: this.ports.isStreaming(), isReady: true,
        capabilities: WEB_CAMERA_CAPABILITIES, components: this.ports.cameraState(), remoteSettings: this.settings.current, sources: [] }));
      this.scheduleTick(tick);
    };
    this.scheduleTick(tick);
  }

  /** 每秒一次；重排前先清掉旧句柄，避免叠加出多条计时链。 */
  private scheduleTick(tick: () => void): void {
    if (this.timer !== undefined) this.ports.clearTimer(this.timer);
    this.timer = this.ports.setTimer(tick, 1000);
  }

  stop(reason: string): void {
    if (this.closed) return;
    this.closed = true;
    if (this.timer !== undefined) { this.ports.clearTimer(this.timer); this.timer = undefined; }
    this.generation++;
    this.socket?.close(1000, reason);
    this.socket = undefined;
    this.authenticated = false;
    this.onChange();
  }

  private connect(): void {
    if (this.closed || this.socket) return;
    const own = ++this.generation;
    this.openedAt = this.ports.now();
    this.authenticated = false;
    let socket: ControlSocket;
    try { socket = this.ports.openSocket(websocketURL(this.ports.origin, '/ws')); }
    catch (error) { this.status = error instanceof Error ? error.message : '无法连接服务'; this.nextRetryAt = this.ports.now() + RETRY_MS; this.onChange(); return; }
    this.socket = socket;
    this.status = '正在连接';
    this.onChange();
    socket.onopen = () => { if (own !== this.generation || this.closed) { socket.close(); return; } socket.send(JSON.stringify({ type: 'auth', token: this.session.token, version: this.session.version })); };
    socket.onmessage = event => { if (own === this.generation && !this.closed && typeof event.data === 'string') this.receive(event.data, socket); };
    socket.onclose = () => { if (own === this.generation) this.drop('连接中断，正在重连'); };
    socket.onerror = () => { if (own === this.generation) this.drop('连接中断，正在重连'); };
  }

  private receive(text: string, socket: ControlSocket): void {
    if (text.length > 1_000_000) return;
    let body: any;
    try { body = JSON.parse(text); } catch { return; }
    if (!body || typeof body !== 'object') return;
    this.lastResponse = this.ports.now();
    switch (body.type) {
      case 'auth-result':
        if (body.success !== true) { this.ports.onUnauthorized(); return; }
        this.authenticated = true;
        this.status = '已连接';
        void this.refreshTargets();
        break;
      case 'stream-list': {
        const streams = parseStreams(body.streams, this.session.device.deviceId);
        // 无效或暂时缺失的列表按“尚未上报”处理，不能清掉已知的绑定与流。
        if (streams.length) this.stream = streams[0];
        break;
      }
      case 'command': {
        const command = body.command;
        if (command !== 'start-stream' && command !== 'stop-stream') return;
        const requestId = body.requestId;
        this.ports.onStreamCommand(command, (success, reason) => {
          if (!this.authenticated || this.socket !== socket) return;
          socket.send(JSON.stringify({ type: 'command-ack', requestId, targetDeviceId: this.session.device.deviceId,
            command, phase: 'completed', success, reason: reason.slice(0, 256) }));
        });
        break;
      }
      case 'set-config': {
        const key = typeof body.key === 'string' ? body.key : '';
        const value = typeof body.value === 'string' ? body.value : '';
        const failure = this.ports.saveConfig(key, value);
        socket.send(JSON.stringify({ type: 'command-ack', requestId: body.requestId, targetDeviceId: this.session.device.deviceId,
          command: `set-config:${key}`, phase: 'completed', success: !failure, reason: (failure ?? '配置已保存').slice(0, 256) }));
        break;
      }
      case 'device-updated':
        if (body.device && typeof body.device.deviceName === 'string' && body.device.deviceName.trim()) this.session.device.deviceName = body.device.deviceName;
        break;
      case 'kicked':
        this.ports.onUnauthorized();
        return;
    }
    this.onChange();
  }

  async refreshTargets(): Promise<void> {
    this.targetsLoading = true;
    this.targetsLoadFailed = false;
    this.onChange();
    try {
      const devices = await this.ports.fetchJson('/api/devices');
      const list: TargetDevice[] = [];
      for (const item of Array.isArray(devices?.devices) ? devices.devices : []) {
        if (!item || typeof item.deviceId !== 'string' || typeof item.deviceName !== 'string') continue;
        if (item.component !== 'windows-inference' && item.component !== 'android-inference') continue;
        list.push({ deviceId: item.deviceId, deviceName: item.deviceName, component: item.component,
          platform: String(item.platform ?? ''), capabilities: Array.isArray(item.capabilities) ? item.capabilities : [], online: item.online !== false });
      }
      const streams = await this.ports.fetchJson('/api/streams');
      const own = parseStreams(streams?.streams, this.session.device.deviceId);
      if (own.length) this.stream = own[0];
      this.targets = list;
      this.targetsLoading = false;
    } catch {
      this.targetsLoading = false;
      this.targetsLoadFailed = true;
    }
    this.onChange();
  }

  async bind(targetDeviceId: string): Promise<string | undefined> {
    try {
      await this.ports.fetchJson('/api/streams/bind', { method: 'POST', body: JSON.stringify({ publisherDeviceId: this.session.device.deviceId, targetDeviceId }) });
    } catch (error) {
      return error instanceof Error ? error.message : '关联失败，请稍后重试';
    }
    await this.refreshTargets();
    return undefined;
  }

  private drop(status: string): void {
    this.generation++;
    this.socket?.close(1000, 'client reload');
    this.socket = undefined;
    this.authenticated = false;
    this.status = status;
    this.nextRetryAt = this.ports.now() + RETRY_MS;
    this.onChange();
  }
}

export function loginCamera(username: string, password: string, deviceIdentity: string, deviceModel: string, fetchJson: ControlPorts['fetchJson']): Promise<LoginResult> {
  return fetchJson('/api/account/login', { method: 'POST', body: JSON.stringify({ username, password, component: 'web-camera', deviceIdentity, deviceModel }) })
    .then(body => { const session = parseLogin(body); if (!session) throw new Error('登录响应无效'); return session; });
}

export function parseSessionSettings(value: unknown): CameraSettings | undefined { return parseRemoteSettings(value); }
