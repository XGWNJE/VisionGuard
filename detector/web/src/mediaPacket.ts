/** 帧打包与在途额度：字段与上限必须与服务端 parseFrame 的校验一致。 */

export const MAX_JPEG_BYTES = 2 * 1024 * 1024;
export const MAX_HEADER_BYTES = 4096;
export const FRAME_LIMIT = 8;
export const BYTE_LIMIT = MAX_JPEG_BYTES + 4100;
const encoder = new TextEncoder();

export interface FrameHeader {
  streamId: string; sessionId: string; sequence: number; capturedAt: number;
  width: number; height: number; rotation: number;
}

export function encodePacket(header: FrameHeader, jpeg: Uint8Array): Uint8Array {
  const json = encoder.encode(JSON.stringify(header));
  if (!json.length || json.length > MAX_HEADER_BYTES) throw new Error('帧头超限');
  if (!jpeg.length || jpeg.length > MAX_JPEG_BYTES) throw new Error('单帧画面上限 2 MiB');
  const packet = new Uint8Array(4 + json.length + jpeg.length);
  new DataView(packet.buffer).setUint32(0, json.length);
  packet.set(json, 4);
  packet.set(jpeg, 4 + json.length);
  return packet;
}

/** 回执报告的是累计进度，可跳号；只清理已确认的序号。 */
export class FrameWindow {
  private session = '';
  private initialized = false;
  private pending = new Map<number, { sentAt: number; bytes: number }>();
  private next = 0;
  private acknowledged = 0;
  private bytes = 0;
  constructor(private readonly frameLimit = FRAME_LIMIT, private readonly byteLimit = BYTE_LIMIT) {}
  /** 同一会话的重复调用保持原状；新会话（或空串）清空在途与序号。 */
  reset(sessionId: string): void {
    if (this.initialized && sessionId && sessionId === this.session) return;
    this.session = sessionId; this.initialized = true;
    this.pending.clear(); this.next = 0; this.acknowledged = 0; this.bytes = 0;
  }
  get open(): boolean { return this.session.length > 0; }
  get inFlight(): number { return this.pending.size; }
  available(frameBytes: number): boolean {
    return this.open && this.pending.size < this.frameLimit && frameBytes > 0 && frameBytes <= this.byteLimit - this.bytes;
  }
  take(now: number, frameBytes: number): number | undefined {
    if (!this.available(frameBytes)) return undefined;
    const sequence = ++this.next;
    this.pending.set(sequence, { sentAt: now, bytes: frameBytes });
    this.bytes += frameBytes;
    return sequence;
  }
  acknowledge(sessionId: string, sequence: number): boolean {
    if (sessionId !== this.session || !Number.isSafeInteger(sequence) || sequence <= this.acknowledged || sequence > this.next) return false;
    for (const key of [...this.pending.keys()]) {
      if (key > sequence) continue;
      this.bytes -= this.pending.get(key)!.bytes;
      this.pending.delete(key);
    }
    this.acknowledged = sequence;
    return true;
  }
  acknowledgementAge(sessionId: string, sequence: number, now: number): number | undefined {
    if (sessionId !== this.session) return undefined;
    const entry = this.pending.get(sequence);
    return entry ? Math.max(0, now - entry.sentAt) : undefined;
  }
  stalled(now: number): boolean {
    for (const entry of this.pending.values()) return now - entry.sentAt > 3000;
    return false;
  }
}

/** 固定节拍吸收回调抖动，长时间停顿后不突发补帧。 */
export class FramePacer {
  private rate = 0;
  private nextAt = 0;
  reset(): void { this.rate = 0; this.nextAt = 0; }
  due(now: number, framesPerSecond: number): boolean {
    if (framesPerSecond < 1 || framesPerSecond > 5) throw new Error('帧率必须在 1–5 之间');
    if (this.rate !== framesPerSecond) { this.rate = framesPerSecond; this.nextAt = now; }
    return now >= this.nextAt;
  }
  sampled(now: number): void {
    const interval = 1000 / this.rate;
    this.nextAt += interval;
    if (this.nextAt <= now) this.nextAt = now + interval;
  }
}

/** 媒体通道在无帧在途时也需要响应，12 秒没有任何响应即重连。 */
export class MediaLiveness {
  private openedAt = 0;
  private lastResponse = 0;
  private ready = false;
  opened(now: number): void { this.openedAt = now; this.lastResponse = now; this.ready = false; }
  responded(now: number): void { this.ready = true; this.lastResponse = now; }
  expired(now: number): boolean { return now - (this.ready ? this.lastResponse : this.openedAt) >= 12_000; }
}
