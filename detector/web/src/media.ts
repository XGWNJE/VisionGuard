/** 媒体通道状态机：发布鉴权、帧节拍、在途额度与心跳重连。socket 与计时器由外部注入。 */

import { parseFrameAck, parseMediaReady, parseRate, websocketURL } from './protocol';
import { protocolVersion } from './session';
import { FramePacer, FrameWindow, MediaLiveness, encodePacket } from './mediaPacket';

export interface MediaSocket {
  readyState: number;
  send(data: string | Uint8Array): void;
  close(code?: number, reason?: string): void;
  onopen: ((event: unknown) => void) | null;
  onmessage: ((event: { data: unknown }) => void) | null;
  onclose: ((event: unknown) => void) | null;
  onerror: ((event: unknown) => void) | null;
}

export interface CameraFrame { jpeg: Uint8Array; width: number; height: number; rotation: number; capturedAt: number }

export interface MediaPorts {
  openSocket(url: string): MediaSocket;
  origin: string;
  now(): number;
  setTimer(callback: () => void, milliseconds: number): unknown;
  clearTimer(handle: unknown): void;
  onChange(): void;
  onError(status: string): void;
}

export interface MediaState {
  status: string; ready: boolean; framesPerSecond: number; sentFrames: number; acknowledgedFrames: number;
  droppedFrames: number; relayDroppedFrames: number; sampledOutFrames: number; sessions: number;
}

const HEARTBEAT_MS = 3000;
const RETRY_MS = 1000;

export class MediaPublisher {
  private socket?: MediaSocket;
  private generation = 0;
  private timer?: unknown;
  private closed = true;
  private wanted = false;
  private sessionId = '';
  private streamId = '';
  private ready = false;
  private lastHeartbeat = 0;
  private nextRetryAt = 0;
  private framesPerSecond = 5;
  private readonly window = new FrameWindow();
  private readonly pacer = new FramePacer();
  private readonly liveness = new MediaLiveness();
  private state: MediaState = { status: '等待画面通道', ready: false, framesPerSecond: 5, sentFrames: 0,
    acknowledgedFrames: 0, droppedFrames: 0, relayDroppedFrames: 0, sampledOutFrames: 0, sessions: 0 };

  constructor(private readonly token: () => string | undefined, private readonly ports: MediaPorts) {}

  get current(): MediaState { return { ...this.state }; }
  get targetSamplingRate(): number { return this.framesPerSecond; }

  start(): void {
    if (!this.closed && this.wanted) return;
    this.closed = false;
    this.wanted = true;
    this.pacer.reset();
    this.connect();
    if (this.timer === undefined) {
      const tick = () => {
        if (this.closed) return;
        const now = this.ports.now();
        if (this.wanted && !this.socket && now >= this.nextRetryAt) this.connect();
        if (this.wanted && this.socket) {
          if (this.window.stalled(now)) this.reconnect('画面发送超时，正在重连');
          else if (this.liveness.expired(now)) this.reconnect('媒体无响应，正在重连');
          else if (this.ready && now - this.lastHeartbeat >= HEARTBEAT_MS) {
            this.lastHeartbeat = now;
            if (this.socket.readyState === 1) this.socket.send(JSON.stringify({ type: 'media-heartbeat' }));
          }
        }
        this.scheduleTick(tick);
      };
      this.scheduleTick(tick);
    }
  }

  /** 每秒一次；重排前先清掉旧句柄，避免叠加出多条计时链。 */
  private scheduleTick(tick: () => void): void {
    if (this.timer !== undefined) this.ports.clearTimer(this.timer);
    this.timer = this.ports.setTimer(tick, 1000);
  }

  private stopInternal(reason: string): void {
    this.closed = true;
    this.wanted = false;
    if (this.timer !== undefined) { this.ports.clearTimer(this.timer); this.timer = undefined; }
    this.dropSocket(true);
    this.state = { ...this.state, status: reason === 'user' ? '推流已停止' : '离开前台，推流已停止', ready: false };
    this.ports.onChange();
  }

  stop(reason: 'user' | 'background' | 'locked'): void {
    if (this.closed && !this.wanted) return;
    if (this.socket && this.ready && this.socket.readyState === 1) this.socket.send(JSON.stringify({ type: 'stream-stop', reason }));
    this.stopInternal(reason);
  }

  private connect(): void {
    if (this.closed || this.socket || !this.wanted) return;
    const token = this.token();
    if (!token) { this.ports.onError('登录状态不可用'); return; }
    const own = ++this.generation;
    this.ready = false;
    this.window.reset('');
    let socket: MediaSocket;
    try { socket = this.ports.openSocket(websocketURL(this.ports.origin, '/media/ws')); }
    catch (error) { this.ports.onError(error instanceof Error ? error.message : '无法连接媒体通道'); return; }
    this.socket = socket;
    this.liveness.opened(this.ports.now());
    this.lastHeartbeat = this.ports.now();
    this.state = { ...this.state, status: '正在连接媒体通道', ready: false };
    this.ports.onChange();
    socket.onopen = () => {
      if (own !== this.generation || this.closed || !this.wanted) { socket.close(); return; }
      if (socket.readyState !== 1) return;
      const version = protocolVersion();
      socket.send(JSON.stringify({ type: 'media-auth', token, direction: 'publish', ...(version ? { version } : {}) }));
    };
    socket.onmessage = event => { if (own === this.generation && !this.closed && typeof event.data === 'string') this.receive(event.data); };
    socket.onclose = () => { if (own === this.generation && !this.closed) this.reconnect('视频连接中断，正在重连'); };
    socket.onerror = () => { if (own === this.generation && !this.closed) this.reconnect('视频连接中断，正在重连'); };
  }

  private receive(text: string): void {
    if (text.length > 16_384) return;
    let body: any;
    try { body = JSON.parse(text); } catch { return; }
    if (!body || typeof body !== 'object') return;
    switch (body.type) {
      case 'media-ready': {
        const ready = parseMediaReady(body);
        if (!ready) return;
        this.sessionId = ready.sessionId;
        this.streamId = ready.streamId;
        this.ready = true;
        this.window.reset(ready.sessionId);
        this.pacer.reset();
        this.framesPerSecond = ready.targetSamplingRate;
        this.liveness.responded(this.ports.now());
        this.state = { ...this.state, status: '正在推流', ready: true, framesPerSecond: this.framesPerSecond, sessions: this.state.sessions + 1 };
        break;
      }
      case 'media-rate': {
        const rate = parseRate(body, this.sessionId, this.streamId);
        if (rate === undefined) return;
        this.framesPerSecond = rate;
        this.state = { ...this.state, framesPerSecond: rate };
        break;
      }
      case 'frame-ack': {
        const ack = parseFrameAck(body, this.sessionId, this.streamId);
        if (!ack) return;
        if (this.window.acknowledge(this.sessionId, ack.sequence)) {
          this.liveness.responded(this.ports.now());
          this.state = { ...this.state, acknowledgedFrames: this.state.acknowledgedFrames + 1,
            relayDroppedFrames: ack.relayDropped ?? this.state.relayDroppedFrames };
        }
        break;
      }
      case 'media-heartbeat-ack':
        this.liveness.responded(this.ports.now());
        break;
      case 'media-error':
        this.reconnect('媒体请求失败，正在重连');
        return;
    }
    this.ports.onChange();
  }

  private reconnect(status: string): void {
    this.dropSocket(false);
    this.state = { ...this.state, status, ready: false };
    this.nextRetryAt = this.ports.now() + RETRY_MS;
    this.ports.onChange();
  }

  private dropSocket(final: boolean): void {
    this.generation++;
    const socket = this.socket;
    this.socket = undefined;
    this.ready = false;
    this.sessionId = '';
    this.streamId = '';
    this.window.reset('');
    if (socket) {
      if (final) socket.close(1000, 'stream stop');
      else socket.close(1000, 'reconnect');
    }
  }

  canPublish(): boolean {
    return this.wanted && this.ready && this.window.available(this.estimateBytes());
  }

  /** 额度检查需要画面体积，使用最近一帧的大小作为估计值，避免为检查先编码。 */
  private lastFrameBytes = 0;
  private estimateBytes(): number { return this.lastFrameBytes || 200_000; }

  /** 只做节拍与额度判断；编码由调用方在通过后执行。 */
  due(now: number): boolean {
    if (!this.wanted || !this.ready) return false;
    if (!this.pacer.due(now, this.framesPerSecond)) { this.noteSampledOut(); return false; }
    if (!this.window.available(this.estimateBytes())) { this.noteDropped(); return false; }
    this.pacer.sampled(now);
    return true;
  }

  publish(frame: CameraFrame): boolean {
    if (!this.wanted || !this.ready || !this.socket || this.socket.readyState !== 1) { this.noteDropped(); return false; }
    if (this.ports.now() - frame.capturedAt > 2500) { this.noteDropped(); return false; }
    const packetBytes = frame.jpeg.length + 4096 + 4;
    const sequence = this.window.take(this.ports.now(), packetBytes);
    if (sequence === undefined) { this.noteDropped(); return false; }
    let packet: Uint8Array;
    try {
      packet = encodePacket({ streamId: this.streamId, sessionId: this.sessionId, sequence, capturedAt: frame.capturedAt,
        width: frame.width, height: frame.height, rotation: frame.rotation }, frame.jpeg);
    } catch {
      this.noteDropped();
      return false;
    }
    try { this.socket.send(packet); }
    catch { this.reconnect('画面发送失败，正在重连'); return false; }
    this.lastFrameBytes = frame.jpeg.length;
    this.state = { ...this.state, sentFrames: this.state.sentFrames + 1 };
    this.ports.onChange();
    return true;
  }

  private noteDropped(): void {
    this.state = { ...this.state, droppedFrames: this.state.droppedFrames + 1 };
    this.ports.onChange();
  }

  private noteSampledOut(): void {
    this.state = { ...this.state, sampledOutFrames: this.state.sampledOutFrames + 1 };
    this.ports.onChange();
  }
}
