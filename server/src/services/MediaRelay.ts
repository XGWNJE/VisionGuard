import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { performance } from 'node:perf_hooks';
import WebSocket from 'ws';
import { accountStore, accountDirectory, AccountError, validDeviceName, type AccountSession } from './AccountStore';
import { isCameraComponent, isInferenceComponent, canPublishVideo, canSubscribeVideo } from './NodeComponents';
import { wsVersionGate } from './protocol';

export interface Stream {
  streamId: string; publisherDeviceId: string; publisherName: string; targetDeviceId?: string; sourceId?: string;
  sourceName: string; isStreaming: boolean; lastFrameAt?: string; stopReason?: string; stats?: FrameStats;
}
interface FrameStats { received: number; forwarded: number; confirmed: number; dropped: number; stale: number; replaced: number; unavailable: number; sendFailed: number }
export interface FrameHeader {
  streamId: string; sessionId: string; sequence: number; capturedAt: number; width: number; height: number; rotation: number;
  receivedAt?: number;
}
interface Publisher {
  key: string; ws: WebSocket; token: string; session: AccountSession; sessionId: string; sourceId: string; streamId: string;
  sequence: number; capturedAt: number; offset?: number; lastFrame: number; lastSeen: number; stopped: boolean;
}
interface BufferedFrame { packet: Buffer; receivedAt: number }
interface SentFrame { sessionId: string; sequence: number; sentAt: number; bytes: number }
interface Subscriber { ws: WebSocket; token: string; session: AccountSession; lastSeen: number; inflight: Map<string, SentFrame[]>; latest: Map<string, BufferedFrame> }
const FRAME_BYTES = 2 * 1024 * 1024;
const FRAME_AGE_MS = 2500;
const SUBSCRIBER_TIMEOUT_MS = 5000;
const BINDING_LIMIT = 16;
const WINDOW_FRAMES = 8;
const WINDOW_BYTES = FRAME_BYTES + 4100;
function mediaDiagnostic(message: () => string): void {
  if (process.env.VISIONGUARD_MEDIA_DIAGNOSTICS === '1') console.log(`[MediaPerf] ${message()}`);
}
function send(ws: WebSocket, value: object): void { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(value)); }
const SOURCE_ID = /^[A-Za-z0-9_-]{1,64}$/;
/** 推流端声明的来源身份：与心跳里的 sourceId 同一套字表；非法或缺失都算未声明。 */
export function declaredSourceId(message: any): string {
  const value = typeof message?.sourceId === 'string' ? message.sourceId.trim() : '';
  return SOURCE_ID.test(value) ? value : '';
}
function sourceNameOf(message: any, fallback: string): string {
  const value = typeof message?.sourceName === 'string' ? message.sourceName.trim() : '';
  return validDeviceName(value) ? value : fallback;
}

/** Read JPEG SOF dimensions, reject truncated headers and non-JPEG payloads before relay. */
export function jpegDimensions(bytes: Buffer): { width: number; height: number } | undefined {
  if (bytes.length < 12 || bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes[bytes.length - 2] !== 0xff || bytes[bytes.length - 1] !== 0xd9) return undefined;
  let position = 2;
  while (position + 3 < bytes.length) {
    if (bytes[position++] !== 0xff) return undefined;
    while (bytes[position] === 0xff) position++;
    const marker = bytes[position++];
    if (marker === 0xda || marker === 0xd9) return undefined;
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
    if (position + 2 > bytes.length) return undefined;
    const length = bytes.readUInt16BE(position);
    if (length < 2 || position + length > bytes.length) return undefined;
    if ([0xc0, 0xc1, 0xc2].includes(marker)) {
      if (length < 8) return undefined;
      const height = bytes.readUInt16BE(position + 3), width = bytes.readUInt16BE(position + 5);
      return width > 0 && height > 0 ? { width, height } : undefined;
    }
    position += length;
  }
  return undefined;
}
export function parseFrame(packet: Buffer): { header: FrameHeader; image: Buffer } | undefined {
  if (packet.length < 5 || packet.length > FRAME_BYTES + 4100) return undefined;
  const length = packet.readUInt32BE(0);
  if (length < 2 || length > 4096 || 4 + length >= packet.length) return undefined;
  let header: any;
  try { header = JSON.parse(packet.subarray(4, 4 + length).toString('utf8')); } catch { return undefined; }
  if (!header || typeof header !== 'object' || Array.isArray(header) || typeof header.streamId !== 'string' || typeof header.sessionId !== 'string'
    || !Number.isSafeInteger(header.sequence) || header.sequence < 0 || !Number.isSafeInteger(header.capturedAt) || header.capturedAt <= 0
    || !Number.isInteger(header.width) || !Number.isInteger(header.height) || ![0, 90, 180, 270].includes(header.rotation)
    || Math.max(header.width, header.height) > 1280 || Math.min(header.width, header.height) > 720) return undefined;
  const image = packet.subarray(4 + length);
  if (image.length > FRAME_BYTES) return undefined;
  const dimensions = jpegDimensions(image);
  if (!dimensions || dimensions.width !== header.width || dimensions.height !== header.height) return undefined;
  return { header, image };
}
export function framePacket(header: FrameHeader, image: Buffer): Buffer {
  const json = Buffer.from(JSON.stringify(header));
  const length = Buffer.alloc(4); length.writeUInt32BE(json.length);
  return Buffer.concat([length, json, image]);
}

export class MediaRelay {
  private readonly accounts = new Map<string, Stream[]>();
  /** 账号 → 发布端设备 → 目标推理设备。绑定与流分开：一台发布端可以推多个来源。 */
  private readonly bindingTargets = new Map<string, Map<string, string>>();
  private readonly publishers = new Map<string, Publisher>();
  private readonly subscribers = new Map<string, Subscriber>();
  private readonly listeners = new Set<(accountId: string) => void>();
  private readonly statsDirty = new Set<string>();
  private readonly samplingRates = new Map<string, Map<string, number>>();
  private samplingRate(stream: Stream): number {
    const rates = stream.targetDeviceId && this.samplingRates.get(stream.targetDeviceId);
    return rates ? rates.get(stream.sourceId ?? '') ?? 1 : 5;
  }
  updateSamplingRates(accountId: string, deviceId: string, sources: { sourceId: string; isMonitoring: boolean; targetSamplingRate?: number }[]): void {
    if (!isInferenceComponent(accountStore.device(accountId, deviceId)?.component)) return;
    const rates = new Map(sources.map(source => [source.sourceId, source.isMonitoring ? Math.max(1, Math.min(5, source.targetSamplingRate ?? 3)) : 1]));
    const streams = this.streams(accountId).filter(stream => stream.targetDeviceId === deviceId);
    const previous = new Map(streams.map(stream => [stream.streamId, this.samplingRate(stream)]));
    this.samplingRates.set(deviceId, rates);
    for (const stream of streams) {
      const rate = this.samplingRate(stream), publisher = [...this.publishers.values()].find(item => item.streamId === stream.streamId);
      if (publisher && rate !== previous.get(stream.streamId)) send(publisher.ws, { type: 'media-rate', streamId: stream.streamId, sessionId: publisher.sessionId, targetSamplingRate: rate });
    }
  }
  private stats(accountId: string, streamId: string): FrameStats | undefined {
    const stream = this.accounts.get(accountId)?.find(item => item.streamId === streamId);
    if (!stream) return;
    this.statsDirty.add(accountId);
    return stream.stats ??= { received: 0, forwarded: 0, confirmed: 0, dropped: 0, stale: 0, replaced: 0, unavailable: 0, sendFailed: 0 };
  }
  private dropped(accountId: string, streamId: string, reason: 'stale' | 'replaced' | 'unavailable' | 'sendFailed'): void {
    const stats = this.stats(accountId, streamId); if (stats) { stats.dropped++; stats[reason]++; }
  }
  constructor() {
    accountStore.onChange(() => this.credentialsChanged());
    const timer = setInterval(() => this.maintain(), 1000); timer.unref();
  }
  onChange(listener: (accountId: string) => void): void { this.listeners.add(listener); }
  private changed(accountId: string): void {
    for (const listener of this.listeners) listener(accountId);
    const message = { type: 'stream-list', streams: this.streams(accountId), bindings: this.bindings(accountId) };
    for (const subscriber of this.subscribers.values()) if (subscriber.session.account.accountId === accountId) send(subscriber.ws, message);
  }
  private save(accountId: string, streams: Stream[]): void {
    const file = path.join(accountDirectory(accountId), 'streams.json');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const temporary = `${file}.tmp`;
    const bindings = [...(this.bindingTargets.get(accountId) ?? new Map()).entries()].map(([publisherDeviceId, targetDeviceId]) => ({ publisherDeviceId, targetDeviceId }));
    const fd = fs.openSync(temporary, 'w', 0o600);
    try { fs.writeFileSync(fd, JSON.stringify({ bindings, streams: streams.map(({ isStreaming: _running, lastFrameAt: _frame, stopReason: _reason, stats: _stats, ...binding }) => binding) }), 'utf8'); fs.fsyncSync(fd); }
    finally { fs.closeSync(fd); }
    fs.renameSync(temporary, file);
    this.accounts.set(accountId, streams);
  }
  /** 绑定与流分开存：绑定是「发布端 → 一个推理节点」，流是「发布端的一个来源」。 */
  private bindingsOf(accountId: string): Map<string, string> {
    let bindings = this.bindingTargets.get(accountId);
    if (!bindings) { bindings = new Map(); this.bindingTargets.set(accountId, bindings); }
    return bindings;
  }
  private load(accountId: string): Stream[] {
    const cached = this.accounts.get(accountId);
    if (cached) return cached;
    const file = path.join(accountDirectory(accountId), 'streams.json');
    const bindings = this.bindingsOf(accountId);
    let loaded: Stream[] = [];
    if (fs.existsSync(file)) {
      const saved = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (Array.isArray(saved)) {
        // 旧形状：一台发布端一条流，行里的 targetDeviceId 就是当时的绑定，来源身份由中继生成。
        loaded = saved.map(stream => ({ ...stream, isStreaming: false }));
        for (const stream of loaded) if (stream.targetDeviceId) bindings.set(stream.publisherDeviceId, stream.targetDeviceId);
      } else if (saved && Array.isArray(saved.streams) && Array.isArray(saved.bindings)) {
        loaded = saved.streams.map((stream: Stream) => ({ ...stream, isStreaming: false }));
        for (const binding of saved.bindings) {
          if (typeof binding?.publisherDeviceId === 'string' && typeof binding?.targetDeviceId === 'string') bindings.set(binding.publisherDeviceId, binding.targetDeviceId);
        }
      } else throw new Error('Invalid streams store');
    }
    this.accounts.set(accountId, loaded);
    return loaded;
  }
  ensureAccount(accountId: string): void {
    const previous = this.load(accountId);
    // 推流视图只认按本版协议声明过版本的设备：未声明或旧版本的端点既不推流也不占绑定。
    const devices = accountStore.directoryDevices(accountId);
    // 发布端按能力收集：相机组件，以及在推流态工作的 Windows 推理节点。
    const publishers = devices.filter(device => canPublishVideo(device.component));
    const inference = devices.filter(device => isInferenceComponent(device.component));
    const bindings = this.bindingsOf(accountId);
    // 发布端消失（解绑设备、版本出局）或目标不再可订阅时，绑定与流一起作废，不留悬空关联。
    for (const [publisherDeviceId, targetDeviceId] of [...bindings]) {
      if (!publishers.some(device => device.deviceId === publisherDeviceId) || !inference.some(device => device.deviceId === targetDeviceId)) bindings.delete(publisherDeviceId);
    }
    const next = previous.filter(stream => publishers.some(device => device.deviceId === stream.publisherDeviceId)).map(stream => ({ ...stream }));
    for (const device of publishers) {
      const camera = isCameraComponent(device.component);
      const bound = bindings.get(device.deviceId);
      // 相机只有一路来源，行由中继建立并沿用「唯一推理节点自动关联」；推理节点的流按来源在媒体认证时建。
      if (camera) {
        let stream = next.find(item => item.publisherDeviceId === device.deviceId);
        if (!stream) {
          stream = { streamId: crypto.randomUUID(), publisherDeviceId: device.deviceId, publisherName: device.deviceName, sourceName: device.deviceName, isStreaming: false };
          if (inference.length === 1) bindings.set(device.deviceId, inference[0].deviceId);
          next.push(stream);
        }
        stream.publisherName = device.deviceName; stream.sourceName = device.deviceName;
        if (inference.length === 1 && !bindings.has(device.deviceId) && next.filter(item => item.targetDeviceId === inference[0].deviceId && item.sourceId).length < BINDING_LIMIT) bindings.set(device.deviceId, inference[0].deviceId);
        this.applyBinding(stream, bindings.get(device.deviceId), true);
      } else {
        for (const stream of next.filter(item => item.publisherDeviceId === device.deviceId)) {
          stream.publisherName = device.deviceName;
          this.applyBinding(stream, bound, false);
        }
      }
    }
    if (JSON.stringify(next) !== JSON.stringify(previous)) { this.save(accountId, next); this.clearChangedBindings(previous, next); this.changed(accountId); }
  }
  /** 绑定落到流上：相机由中继生成来源身份，推理节点的来源身份由发布端自己声明。 */
  private applyBinding(stream: Stream, targetDeviceId: string | undefined, camera: boolean): void {
    if (targetDeviceId) {
      stream.targetDeviceId = targetDeviceId;
      if (camera && !stream.sourceId) stream.sourceId = crypto.randomUUID();
    } else {
      delete stream.targetDeviceId;
      if (camera) delete stream.sourceId;
    }
  }
  streams(accountId: string): Stream[] { this.ensureAccount(accountId); return this.accounts.get(accountId)!.map(stream => ({ ...stream })); }
  /** 绑定视图：发布端 → 目标推理节点；没有来源的发布端也能先绑定，等来源上线再推。 */
  bindings(accountId: string): { publisherDeviceId: string; targetDeviceId: string }[] {
    this.ensureAccount(accountId);
    return [...this.bindingsOf(accountId).entries()].map(([publisherDeviceId, targetDeviceId]) => ({ publisherDeviceId, targetDeviceId }));
  }
  bind(session: AccountSession, publisherDeviceId: unknown, targetDeviceId: unknown): Stream | undefined {
    const accountId = session.account.accountId;
    if (typeof publisherDeviceId !== 'string' || typeof targetDeviceId !== 'string') throw new AccountError(400, 'Invalid binding');
    const publisher = accountStore.device(accountId, publisherDeviceId), target = accountStore.device(accountId, targetDeviceId);
    // 目标必须是另一个可订阅的推理设备：推流节点不能绑定自己，避免自环。
    if (!canPublishVideo(publisher?.component) || !canSubscribeVideo(target?.component) || publisherDeviceId === targetDeviceId) throw new AccountError(404, 'Compatible devices not found');
    if (session.device.role !== 'console' && !(canPublishVideo(session.device.component) && session.device.deviceId === publisherDeviceId)) throw new AccountError(403, 'Binding permission required');
    const previous = this.streams(accountId);
    const bindings = this.bindingsOf(accountId);
    // 绑定没变：直接返回代表流，不重复通知（与原行为一致）。
    if (bindings.get(publisherDeviceId) === targetDeviceId) {
      const current = this.publisherStream(accountId, publisherDeviceId);
      if (current) return { ...current };
    }
    // 目标侧按来源计数：一台发布端推几个来源就占几个额度。
    const own = previous.filter(stream => stream.publisherDeviceId === publisherDeviceId && stream.sourceId).length;
    const others = previous.filter(stream => stream.targetDeviceId === targetDeviceId && stream.publisherDeviceId !== publisherDeviceId && stream.sourceId).length;
    if (others + Math.max(1, own) > BINDING_LIMIT) throw new AccountError(409, 'Inference source limit reached');
    bindings.set(publisherDeviceId, targetDeviceId);
    const next = previous.map(stream => {
      if (stream.publisherDeviceId !== publisherDeviceId) return stream;
      const updated = { ...stream, targetDeviceId };
      if (!updated.sourceId) updated.sourceId = crypto.randomUUID();
      return updated;
    });
    this.save(accountId, next); this.clearChangedBindings(previous, next); this.changed(accountId);
    this.notifyPublisher(accountId, publisherDeviceId);
    // 发布端此刻可能还没声明来源（没有流行），绑定已经存下，等来源上线即生效。
    const stream = this.publisherStream(accountId, publisherDeviceId);
    return stream ? { ...stream } : undefined;
  }
  /**
   * 解除绑定：控制台、发布端自己，或正在被推的被动方都能解。
   * 绑定去掉后流还在（来源身份与参数保留），被动方列表不再显示它，发布端收到未绑定的回执。
   */
  unbind(session: AccountSession, publisherDeviceId: unknown): void {
    const accountId = session.account.accountId;
    if (typeof publisherDeviceId !== 'string') throw new AccountError(400, 'Invalid binding');
    const bindings = this.bindingsOf(accountId);
    const targetDeviceId = bindings.get(publisherDeviceId);
    if (!targetDeviceId) throw new AccountError(404, 'Binding not found');
    const allowed = session.device.role === 'console'
      || (session.device.deviceId === publisherDeviceId && canPublishVideo(session.device.component))
      || session.device.deviceId === targetDeviceId;
    if (!allowed) throw new AccountError(403, 'Binding permission required');
    bindings.delete(publisherDeviceId);
    const previous = this.streams(accountId);
    const next = previous.map(stream => {
      if (stream.publisherDeviceId !== publisherDeviceId) return stream;
      const updated = { ...stream };
      delete updated.targetDeviceId;
      if (isCameraComponent(accountStore.device(accountId, publisherDeviceId)?.component ?? '')) delete updated.sourceId;
      return updated;
    });
    this.save(accountId, next); this.clearChangedBindings(previous, next); this.changed(accountId);
    this.notifyPublisher(accountId, publisherDeviceId);
  }
  /** 发布端的代表流：优先推流中的来源，其次任意已声明的来源；只用于返回给调用方。 */
  private publisherStream(accountId: string, publisherDeviceId: string): Stream | undefined {
    const streams = this.accounts.get(accountId)?.filter(stream => stream.publisherDeviceId === publisherDeviceId);
    return streams?.find(stream => stream.isStreaming) ?? streams?.[0];
  }
  /**
   * 认领一路来源的流：推流端声明来源身份后按（发布端 + 来源）建行，目标是当前绑定。
   * 新来源会在被动方列表里即时出现（changed 广播 stream-list）。
   */
  private claimStream(accountId: string, device: { deviceId: string; deviceName: string }, sourceId: string, sourceName: string, camera: boolean): Stream | undefined {
    const streams = this.accounts.get(accountId)!;
    if (camera) return streams.find(item => item.publisherDeviceId === device.deviceId);
    const targetDeviceId = this.bindingsOf(accountId).get(device.deviceId);
    const existing = streams.find(item => item.publisherDeviceId === device.deviceId && item.sourceId === sourceId);
    if (existing) {
      let changed = false;
      if (existing.publisherName !== device.deviceName) { existing.publisherName = device.deviceName; changed = true; }
      if (sourceName && existing.sourceName !== sourceName) { existing.sourceName = sourceName; changed = true; }
      if (targetDeviceId && existing.targetDeviceId !== targetDeviceId) { existing.targetDeviceId = targetDeviceId; changed = true; }
      if (changed) { this.save(accountId, streams); this.changed(accountId); }
      return existing;
    }
    const stream: Stream = { streamId: crypto.randomUUID(), publisherDeviceId: device.deviceId, publisherName: device.deviceName,
      sourceId, sourceName, isStreaming: false, ...(targetDeviceId ? { targetDeviceId } : {}) };
    streams.push(stream);
    this.save(accountId, streams);
    this.changed(accountId);
    return stream;
  }
  /** 绑定变化后逐个来源重发 media-ready：一台发布端可能有多个来源连接。 */
  private notifyPublisher(accountId: string, publisherDeviceId: string): void {
    for (const publisher of this.publishers.values()) {
      if (publisher.session.account.accountId !== accountId || publisher.session.device.deviceId !== publisherDeviceId) continue;
      const stream = this.accounts.get(accountId)?.find(item => item.streamId === publisher.streamId);
      send(publisher.ws, { type: 'media-ready', sessionId: publisher.sessionId, streamId: publisher.streamId,
        sourceId: stream?.sourceId, sourceName: stream?.sourceName, targetDeviceId: stream?.targetDeviceId,
        targetSamplingRate: stream ? this.samplingRate(stream) : 1 });
    }
  }
  private clearChangedBindings(previous: Stream[], next: Stream[]): void {
    for (const stream of previous) {
      const updated = next.find(item => item.streamId === stream.streamId);
      if (updated?.targetDeviceId === stream.targetDeviceId && updated?.sourceId === stream.sourceId) continue;
      for (const subscriber of this.subscribers.values()) {
        if (subscriber.latest.delete(stream.streamId)) this.dropped(subscriber.session.account.accountId, stream.streamId, 'unavailable');
        subscriber.inflight.delete(stream.streamId);
      }
    }
  }
  handleConnection(ws: WebSocket): void {
    let publisher: Publisher | undefined, subscriber: Subscriber | undefined;
    const timer = setTimeout(() => ws.close(4001, 'media auth timeout'), 5000); timer.unref();
    ws.on('error', () => ws.terminate());
    ws.on('message', (raw, binary) => {
      try {
        if (!publisher && !subscriber) {
          if (binary) { ws.close(4001, 'media auth required'); return; }
          const message = JSON.parse(raw.toString());
          // 媒体通道与业务通道同一道版本闸门：旧版本端点连发布/订阅都进不来。
          // 出局前记下声明的版本，节点目录与推流视图随即不再显示该端点。
          if (!wsVersionGate(message)) {
            if (message?.type === 'media-auth') accountStore.recordDeclaredVersion(message.token, message.version);
            ws.close(4001, 'protocol mismatch'); return;
          }
          const session = message?.type === 'media-auth' ? accountStore.authenticate(message.token) : undefined;
          // 发布按能力放行（相机组件与推流态的 Windows 推理节点），订阅按推理实现放行。
          if (!session || !((message.direction === 'publish' && canPublishVideo(session.device.component)) || (message.direction === 'subscribe' && canSubscribeVideo(session.device.component)))) { ws.close(4001, 'media permission required'); return; }
          // 同一节点在同一时间只能是一个方向：已订阅的设备不能发布，已发布的设备不能订阅。
          // 客户端的两态开关保证正常路径不会同时来，这里是服务端侧的强制，防止绕过。
          const publisherKey = message.direction === 'publish' ? `${session.device.deviceId}|${declaredSourceId(message)}` : '';
          const oppositeLive = message.direction === 'publish'
            ? this.subscribers.has(session.device.deviceId)
            : [...this.publishers.values()].some(item => item.session.device.deviceId === session.device.deviceId);
          if (oppositeLive) { ws.close(4001, 'media direction already in use'); return; }
          // 媒体认证也是版本声明通道：先落版本，推流视图才会把该端点算进来。
          accountStore.recordVersion(session.account.accountId, session.device.deviceId, session.device.component, message.version);
          clearTimeout(timer); this.ensureAccount(session.account.accountId);
          if (message.direction === 'publish') {
            // 推流态的推理节点必须声明来源：一台发布端每个来源一条连接、一条流。
            const camera = isCameraComponent(session.device.component);
            const sourceId = declaredSourceId(message);
            if (!camera && !sourceId) { ws.close(4001, 'media source required'); return; }
            const stream = this.claimStream(session.account.accountId, session.device, camera ? '' : sourceId, sourceNameOf(message, session.device.deviceName), camera);
            if (!stream) { ws.close(4001, 'media stream unavailable'); return; }
            const replaced = this.publishers.get(publisherKey);
            if (replaced) { replaced.ws.close(4000, 'publisher replaced'); this.publishers.delete(publisherKey); }
            publisher = { key: publisherKey, ws, session, token: message.token, sessionId: crypto.randomUUID(), sourceId: stream.sourceId ?? '', streamId: stream.streamId,
              sequence: -1, capturedAt: -1, lastFrame: 0, lastSeen: performance.now(), stopped: false };
            this.publishers.set(publisherKey, publisher);
            send(ws, { type: 'media-ready', sessionId: publisher.sessionId, streamId: stream.streamId, sourceId: stream.sourceId, sourceName: stream.sourceName,
              targetDeviceId: stream.targetDeviceId, targetSamplingRate: this.samplingRate(stream) });
          } else {
            const old = this.subscribers.get(session.device.deviceId);
            if (old) { this.discardConsumer(old); old.ws.close(4000, 'subscriber replaced'); }
            subscriber = { ws, session, token: message.token, lastSeen: performance.now(), inflight: new Map(), latest: new Map() };
            this.subscribers.set(session.device.deviceId, subscriber);
            send(ws, { type: 'media-ready', sessionId: crypto.randomUUID() });
            send(ws, { type: 'stream-list', streams: this.streams(session.account.accountId), bindings: this.bindings(session.account.accountId) });
          }
          return;
        }
        const active = publisher ?? subscriber!;
        if (!accountStore.authenticate(active.token)) { ws.close(4001, 'session revoked'); return; }
        const owner = publisher ? this.publishers.get(publisher.key) : this.subscribers.get(subscriber!.session.device.deviceId);
        if (owner !== active) return;
        active.lastSeen = performance.now();
        if (!binary) {
          const heartbeat = JSON.parse(raw.toString());
          if (heartbeat.type === 'media-heartbeat') { send(ws, { type: 'media-heartbeat-ack' }); return; }
        }
        if (publisher) {
          if (this.publishers.get(publisher.key) !== publisher) return;
          if (!binary) {
            const message = JSON.parse(raw.toString());
            if (message.type === 'stream-stop' && ['user', 'background', 'locked'].includes(message.reason)) { publisher.stopped = true; this.stopPublisher(publisher, message.reason); ws.close(1000, 'expected stream stop'); }
            return;
          }
          if (publisher.stopped) { ws.close(4002, 'stream stopped'); return; }
          if (ws.bufferedAmount > 64 * 1024) { ws.terminate(); return; }
          const packet = Buffer.isBuffer(raw) ? raw : Buffer.concat(Array.isArray(raw) ? raw : [Buffer.from(raw as ArrayBuffer)]);
          const parsingAt = performance.now();
          const frame = parseFrame(packet);
          const stream = this.accounts.get(publisher.session.account.accountId)!.find(item => item.streamId === publisher!.streamId);
          if (!stream || !frame || frame.header.streamId !== publisher.streamId || frame.header.sessionId !== publisher.sessionId || frame.header.sequence <= publisher.sequence || frame.header.capturedAt < publisher.capturedAt) { ws.close(4002, 'invalid frame'); return; }
          const now = Date.now(), monotonicNow = performance.now();
          mediaDiagnostic(() => `event=publish sequence=${frame.header.sequence} packetBytes=${packet.length} parseMs=${(monotonicNow-parsingAt).toFixed(3)} socketQueueBytes=${publisher!.ws.bufferedAmount}`);
          publisher.offset ??= monotonicNow - frame.header.capturedAt;
          const age = monotonicNow - (frame.header.capturedAt + publisher.offset);
          if (age < -FRAME_AGE_MS) { ws.close(4002, 'capture clock changed'); return; }
          publisher.sequence = frame.header.sequence; publisher.capturedAt = frame.header.capturedAt;
          this.stats(publisher.session.account.accountId, stream.streamId)!.received++;
          const fresh = age <= FRAME_AGE_MS && age >= -FRAME_AGE_MS;
          const acknowledge = (accepted: boolean, dropReason?: string) => ws.send(JSON.stringify({ type: 'frame-ack', streamId: stream.streamId,
            sessionId: publisher!.sessionId, sequence: frame.header.sequence, accepted, dropReason, stats: stream.stats }), error => {
              if (this.publishers.get(publisher!.key) !== publisher) return;
              if (error) ws.terminate();
            });
          // Offset is established per connection, never treated as a synchronized capture clock.
          if (!fresh) { this.dropped(publisher.session.account.accountId, stream.streamId, 'stale'); acknowledge(false, 'stale'); return; }
          publisher.lastFrame = monotonicNow;
          const changed = !stream.isStreaming || !!stream.stopReason;
          stream.isStreaming = true; stream.lastFrameAt = new Date(now).toISOString(); delete stream.stopReason;
          if (changed) this.changed(publisher.session.account.accountId);
          if (!stream.targetDeviceId) { this.dropped(publisher.session.account.accountId, stream.streamId, 'unavailable'); acknowledge(false, 'unbound'); return; }
          const consumer = this.subscribers.get(stream.targetDeviceId);
          if (!consumer || consumer.session.account.accountId !== publisher.session.account.accountId) { this.dropped(publisher.session.account.accountId, stream.streamId, 'unavailable'); acknowledge(false, 'consumer-unavailable'); return; }
          const forwarded = { packet: framePacket({ ...frame.header, receivedAt: now }, frame.image), receivedAt: monotonicNow };
          if (consumer.latest.delete(stream.streamId)) this.dropped(publisher.session.account.accountId, stream.streamId, 'replaced');
          if (!this.canDeliver(consumer, stream.streamId, forwarded)) {
            consumer.latest.set(stream.streamId, forwarded);
          }
          else this.deliver(consumer, stream.streamId, forwarded);
          acknowledge(true);
        } else if (subscriber) {
          if (binary || this.subscribers.get(subscriber.session.device.deviceId) !== subscriber) { if (binary) ws.close(4002, 'consumer cannot publish'); return; }
          const message = JSON.parse(raw.toString());
          if (message.type !== 'frame-received') return;
          const pending = subscriber.inflight.get(message.streamId);
          const index = pending?.findIndex(frame => frame.sessionId === message.sessionId && frame.sequence === message.sequence) ?? -1;
          if (!pending || index < 0) return;
          const sent = pending[index];
          mediaDiagnostic(() => `event=consumerAck sequence=${message.sequence} roundTripMs=${(performance.now()-sent.sentAt).toFixed(3)} socketQueueBytes=${subscriber!.ws.bufferedAmount}`);
          const stats = this.stats(subscriber.session.account.accountId, message.streamId); if (stats) stats.confirmed += index + 1;
          pending.splice(0, index + 1);
          if (!pending.length) subscriber.inflight.delete(message.streamId);
          // Receipts can lag or skip sequence numbers. Flush fair, bounded latest slots after progress.
          for (const [streamId, latest] of subscriber.latest) {
            if (!this.canDeliver(subscriber, streamId, latest)) continue;
            subscriber.latest.delete(streamId); this.deliver(subscriber, streamId, latest);
          }
        }
      } catch { ws.close(4002, 'invalid media message'); }
    });
    ws.on('close', () => {
      clearTimeout(timer);
      if (publisher && this.publishers.get(publisher.key) === publisher) { this.publishers.delete(publisher.key); this.stopPublisher(publisher, publisher.stopped ? undefined : 'connection-lost'); }
      if (subscriber && this.subscribers.get(subscriber.session.device.deviceId) === subscriber) { this.discardConsumer(subscriber); this.subscribers.delete(subscriber.session.device.deviceId); }
    });
  }
  private discardConsumer(consumer: Subscriber): void {
    for (const streamId of consumer.latest.keys()) this.dropped(consumer.session.account.accountId, streamId, 'unavailable');
    consumer.latest.clear(); consumer.inflight.clear();
  }
  /** 停止一路来源：只影响这条流，同一发布端的其它来源继续推。 */
  private stopPublisher(publisher: Publisher, reason?: string): void {
    const accountId = publisher.session.account.accountId;
    const stream = this.accounts.get(accountId)?.find(item => item.streamId === publisher.streamId);
    if (!stream) return;
    stream.isStreaming = false; if (reason) stream.stopReason = reason;
    for (const consumer of this.subscribers.values()) {
      if (consumer.latest.delete(stream.streamId)) this.dropped(accountId, stream.streamId, 'unavailable');
      consumer.inflight.delete(stream.streamId);
    }
    this.changed(accountId);
  }
  private deliver(consumer: Subscriber, streamId: string, frame: BufferedFrame): void {
    const parsed = parseFrame(frame.packet); if (!parsed) return;
    const stream = this.accounts.get(consumer.session.account.accountId)?.find(item => item.streamId === streamId && item.targetDeviceId === consumer.session.device.deviceId);
    const producer = stream && [...this.publishers.values()].find(item => item.streamId === streamId);
    if (!stream || !producer || producer.sessionId !== parsed.header.sessionId) return;
    if (performance.now() - frame.receivedAt > FRAME_AGE_MS) { this.dropped(consumer.session.account.accountId, streamId, 'stale'); return; }
    if (consumer.ws.readyState !== WebSocket.OPEN || consumer.ws.bufferedAmount > FRAME_BYTES) { this.dropped(consumer.session.account.accountId, streamId, 'sendFailed'); consumer.ws.terminate(); return; }
    const pending = consumer.inflight.get(streamId) ?? [];
    pending.push({ sessionId: parsed.header.sessionId, sequence: parsed.header.sequence, sentAt: performance.now(), bytes: frame.packet.length });
    consumer.inflight.set(streamId, pending);
    mediaDiagnostic(() => `event=deliver sequence=${parsed.header.sequence} queueAgeMs=${(performance.now()-frame.receivedAt).toFixed(3)} packetBytes=${frame.packet.length} socketQueueBytes=${consumer.ws.bufferedAmount}`);
    consumer.ws.send(frame.packet, { binary: true }, error => {
      if (error) { this.dropped(consumer.session.account.accountId, streamId, 'sendFailed'); consumer.ws.terminate(); }
      else { const stats = this.stats(consumer.session.account.accountId, streamId); if (stats) stats.forwarded++; }
    });
  }
  /** 每条流各自算在途窗口：多来源并行时不会互相抢同一个字节额度而互相饿死。 */
  private canDeliver(consumer: Subscriber, streamId: string, frame: BufferedFrame): boolean {
    const pending = consumer.inflight.get(streamId) ?? [];
    const bytes = pending.reduce((sum, sent) => sum + sent.bytes, 0);
    return pending.length < WINDOW_FRAMES && bytes + frame.packet.length <= WINDOW_BYTES && consumer.ws.bufferedAmount + frame.packet.length <= WINDOW_BYTES;
  }
  private credentialsChanged(): void {
    for (const producer of this.publishers.values()) if (!accountStore.authenticate(producer.token)) { producer.stopped = true; this.stopPublisher(producer, 'session-revoked'); producer.ws.close(4001, 'session revoked'); }
    for (const consumer of this.subscribers.values()) if (!accountStore.authenticate(consumer.token)) consumer.ws.close(4001, 'session revoked');
    for (const accountId of this.accounts.keys()) this.ensureAccount(accountId);
  }
  maintain(now = performance.now()): void {
    for (const producer of this.publishers.values()) {
      if (!accountStore.authenticate(producer.token)) { producer.ws.close(4001, 'session expired'); continue; }
      if (now - producer.lastSeen > 45_000) { producer.ws.terminate(); continue; }
      if (producer.lastFrame && now - producer.lastFrame > FRAME_AGE_MS) { const stream = this.accounts.get(producer.session.account.accountId)?.find(item => item.streamId === producer.streamId); if (stream?.isStreaming) this.stopPublisher(producer, 'frame-stalled'); }
    }
    for (const consumer of this.subscribers.values()) {
      if (!accountStore.authenticate(consumer.token) || now - consumer.lastSeen > 45_000 || [...consumer.inflight.values()].some(frames => frames.some(frame => now - frame.sentAt > SUBSCRIBER_TIMEOUT_MS))) consumer.ws.close(4002, 'consumer stalled or session expired');
    }
    for (const accountId of this.statsDirty) { this.statsDirty.delete(accountId); this.changed(accountId); }
  }
}
export const mediaRelay = new MediaRelay();
