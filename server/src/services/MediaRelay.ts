import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { performance } from 'node:perf_hooks';
import WebSocket from 'ws';
import { accountStore, accountDirectory, AccountError, type AccountSession } from './AccountStore';

export interface Stream {
  streamId: string; publisherDeviceId: string; publisherName: string; targetDeviceId?: string; sourceId?: string;
  sourceName: string; isStreaming: boolean; lastFrameAt?: string; stopReason?: string; stats?: FrameStats;
}
interface FrameStats { received: number; forwarded: number; confirmed: number; dropped: number; stale: number; replaced: number; unavailable: number; sendFailed: number }
export interface FrameHeader {
  streamId: string; sessionId: string; sequence: number; capturedAt: number; width: number; height: number; rotation: number;
  receivedAt?: number;
}
interface Publisher { ws: WebSocket; token: string; session: AccountSession; sessionId: string; sequence: number; capturedAt: number; offset?: number; lastFrame: number; lastSeen: number; stopped: boolean }
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
    if (accountStore.device(accountId, deviceId)?.component !== 'windows-inference') return;
    const rates = new Map(sources.map(source => [source.sourceId, source.isMonitoring ? Math.max(1, Math.min(5, source.targetSamplingRate ?? 3)) : 1]));
    const streams = this.streams(accountId).filter(stream => stream.targetDeviceId === deviceId);
    const previous = new Map(streams.map(stream => [stream.streamId, this.samplingRate(stream)]));
    this.samplingRates.set(deviceId, rates);
    for (const stream of streams) {
      const rate = this.samplingRate(stream), publisher = this.publishers.get(stream.publisherDeviceId);
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
    const message = { type: 'stream-list', streams: this.streams(accountId) };
    for (const subscriber of this.subscribers.values()) if (subscriber.session.account.accountId === accountId) send(subscriber.ws, message);
  }
  private save(accountId: string, streams: Stream[]): void {
    const file = path.join(accountDirectory(accountId), 'streams.json');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const temporary = `${file}.tmp`;
    const fd = fs.openSync(temporary, 'w', 0o600);
    try { fs.writeFileSync(fd, JSON.stringify(streams.map(({ isStreaming: _running, lastFrameAt: _frame, stopReason: _reason, stats: _stats, ...binding }) => binding)), 'utf8'); fs.fsyncSync(fd); }
    finally { fs.closeSync(fd); }
    fs.renameSync(temporary, file);
    this.accounts.set(accountId, streams);
  }
  ensureAccount(accountId: string): void {
    let previous = this.accounts.get(accountId);
    if (!previous) {
      const file = path.join(accountDirectory(accountId), 'streams.json');
      const saved = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : [];
      if (!Array.isArray(saved)) throw new Error('Invalid streams store');
      previous = saved.map(stream => ({ ...stream, isStreaming: false })); this.accounts.set(accountId, previous!);
    }
    const devices = accountStore.devices(accountId);
    const cameras = devices.filter(device => device.component === 'android-camera');
    const inference = devices.filter(device => device.component === 'windows-inference');
    const next = previous!.filter(stream => cameras.some(device => device.deviceId === stream.publisherDeviceId)).map(stream => ({ ...stream }));
    for (const camera of cameras) {
      let stream = next.find(item => item.publisherDeviceId === camera.deviceId);
      if (!stream) { stream = { streamId: crypto.randomUUID(), publisherDeviceId: camera.deviceId, publisherName: camera.deviceName, sourceName: camera.deviceName, isStreaming: false }; next.push(stream); }
      stream.publisherName = camera.deviceName; stream.sourceName = camera.deviceName;
      if (stream.targetDeviceId && !inference.some(device => device.deviceId === stream!.targetDeviceId)) { delete stream.targetDeviceId; delete stream.sourceId; }
      if (!stream.targetDeviceId && inference.length === 1 && next.filter(item => item.targetDeviceId === inference[0].deviceId).length < BINDING_LIMIT) { stream.targetDeviceId = inference[0].deviceId; stream.sourceId = crypto.randomUUID(); }
    }
    if (JSON.stringify(next) !== JSON.stringify(previous)) { this.save(accountId, next); this.clearChangedBindings(previous!, next); this.changed(accountId); }
  }
  streams(accountId: string): Stream[] { this.ensureAccount(accountId); return this.accounts.get(accountId)!.map(stream => ({ ...stream })); }
  bind(session: AccountSession, publisherDeviceId: unknown, targetDeviceId: unknown): Stream {
    const accountId = session.account.accountId;
    if (typeof publisherDeviceId !== 'string' || typeof targetDeviceId !== 'string') throw new AccountError(400, 'Invalid binding');
    const publisher = accountStore.device(accountId, publisherDeviceId), target = accountStore.device(accountId, targetDeviceId);
    if (publisher?.component !== 'android-camera' || target?.component !== 'windows-inference') throw new AccountError(404, 'Compatible devices not found');
    if (session.device.role !== 'console' && !(session.device.component === 'android-camera' && session.device.deviceId === publisherDeviceId)) throw new AccountError(403, 'Binding permission required');
    const previous = this.streams(accountId);
    const current = previous.find(stream => stream.publisherDeviceId === publisherDeviceId);
    if (current?.targetDeviceId === targetDeviceId && current.sourceId) return { ...current };
    if (previous.filter(stream => stream.targetDeviceId === targetDeviceId && stream.publisherDeviceId !== publisherDeviceId).length >= BINDING_LIMIT) throw new AccountError(409, 'Inference source limit reached');
    const next = previous.map(stream => stream.publisherDeviceId === publisherDeviceId ? { ...stream, targetDeviceId, sourceId: stream.sourceId || crypto.randomUUID() } : stream);
    this.save(accountId, next); this.clearChangedBindings(previous, next); this.changed(accountId);
    const stream = next.find(item => item.publisherDeviceId === publisherDeviceId)!;
    const live = this.publishers.get(publisherDeviceId);
    if (live) send(live.ws, { type: 'media-ready', sessionId: live.sessionId, streamId: stream.streamId, targetDeviceId, sourceId: stream.sourceId, targetSamplingRate: this.samplingRate(stream) });
    return { ...stream };
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
          const session = message?.type === 'media-auth' ? accountStore.authenticate(message.token) : undefined;
          if (!session || !((message.direction === 'publish' && session.device.component === 'android-camera') || (message.direction === 'subscribe' && session.device.component === 'windows-inference'))) { ws.close(4001, 'media permission required'); return; }
          clearTimeout(timer); this.ensureAccount(session.account.accountId);
          if (message.direction === 'publish') {
            this.publishers.get(session.device.deviceId)?.ws.close(4000, 'publisher replaced');
            publisher = { ws, session, token: message.token, sessionId: crypto.randomUUID(), sequence: -1, capturedAt: -1, lastFrame: 0, lastSeen: performance.now(), stopped: false };
            this.publishers.set(session.device.deviceId, publisher);
            const stream = this.streams(session.account.accountId).find(item => item.publisherDeviceId === session.device.deviceId)!;
            send(ws, { type: 'media-ready', sessionId: publisher.sessionId, streamId: stream.streamId, targetDeviceId: stream.targetDeviceId, sourceId: stream.sourceId, targetSamplingRate: this.samplingRate(stream) });
          } else {
            const old = this.subscribers.get(session.device.deviceId);
            if (old) { this.discardConsumer(old); old.ws.close(4000, 'subscriber replaced'); }
            subscriber = { ws, session, token: message.token, lastSeen: performance.now(), inflight: new Map(), latest: new Map() };
            this.subscribers.set(session.device.deviceId, subscriber);
            send(ws, { type: 'media-ready', sessionId: crypto.randomUUID() });
            send(ws, { type: 'stream-list', streams: this.streams(session.account.accountId) });
          }
          return;
        }
        const active = publisher ?? subscriber!;
        if (!accountStore.authenticate(active.token)) { ws.close(4001, 'session revoked'); return; }
        const owner = publisher ? this.publishers.get(publisher.session.device.deviceId) : this.subscribers.get(subscriber!.session.device.deviceId);
        if (owner !== active) return;
        active.lastSeen = performance.now();
        if (!binary) {
          const heartbeat = JSON.parse(raw.toString());
          if (heartbeat.type === 'media-heartbeat') { send(ws, { type: 'media-heartbeat-ack' }); return; }
        }
        if (publisher) {
          if (this.publishers.get(publisher.session.device.deviceId) !== publisher) return;
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
          const stream = this.accounts.get(publisher.session.account.accountId)!.find(item => item.publisherDeviceId === publisher!.session.device.deviceId)!;
          if (!frame || frame.header.streamId !== stream.streamId || frame.header.sessionId !== publisher.sessionId || frame.header.sequence <= publisher.sequence || frame.header.capturedAt < publisher.capturedAt) { ws.close(4002, 'invalid frame'); return; }
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
              if (this.publishers.get(publisher!.session.device.deviceId) !== publisher) return;
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
      if (publisher && this.publishers.get(publisher.session.device.deviceId) === publisher) { this.publishers.delete(publisher.session.device.deviceId); this.stopPublisher(publisher, publisher.stopped ? undefined : 'connection-lost'); }
      if (subscriber && this.subscribers.get(subscriber.session.device.deviceId) === subscriber) { this.discardConsumer(subscriber); this.subscribers.delete(subscriber.session.device.deviceId); }
    });
  }
  private discardConsumer(consumer: Subscriber): void {
    for (const streamId of consumer.latest.keys()) this.dropped(consumer.session.account.accountId, streamId, 'unavailable');
    consumer.latest.clear(); consumer.inflight.clear();
  }
  private stopPublisher(publisher: Publisher, reason?: string): void {
    const accountId = publisher.session.account.accountId;
    const stream = this.accounts.get(accountId)?.find(item => item.publisherDeviceId === publisher.session.device.deviceId);
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
    const producer = stream && this.publishers.get(stream.publisherDeviceId);
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
  private canDeliver(consumer: Subscriber, streamId: string, frame: BufferedFrame): boolean {
    const pending = consumer.inflight.get(streamId) ?? [];
    const bytes = [...consumer.inflight.values()].reduce((sum, frames) => sum + frames.reduce((n, sent) => n + sent.bytes, 0), 0);
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
      if (producer.lastFrame && now - producer.lastFrame > FRAME_AGE_MS) { const stream = this.accounts.get(producer.session.account.accountId)?.find(item => item.publisherDeviceId === producer.session.device.deviceId); if (stream?.isStreaming) this.stopPublisher(producer, 'frame-stalled'); }
    }
    for (const consumer of this.subscribers.values()) {
      if (!accountStore.authenticate(consumer.token) || now - consumer.lastSeen > 45_000 || [...consumer.inflight.values()].some(frames => frames.some(frame => now - frame.sentAt > SUBSCRIBER_TIMEOUT_MS))) consumer.ws.close(4002, 'consumer stalled or session expired');
    }
    for (const accountId of this.statsDirty) { this.statsDirty.delete(accountId); this.changed(accountId); }
  }
}
export const mediaRelay = new MediaRelay();
