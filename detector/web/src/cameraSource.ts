/** 浏览器采集与 JPEG 编码：分辨率上限与服务端校验一致，编码结果先自检再交给媒体通道。 */

import { boundedSize, jpegDimensions, trimJpeg } from './protocol';
import type { CameraFrame } from './media';

export interface CameraDevice { deviceId: string; label: string }

/** 规格上限：长边 1280、短边 720，与 Android 相机节点和服务端一致。 */
export function targetSize(width: number, height: number, resolution: '480p' | '720p'): { width: number; height: number } {
  return resolution === '720p' ? boundedSize(width, height, 1280, 720) : boundedSize(width, height, 640, 480);
}

export function rotationFromOrientation(angle: number): number {
  const normalized = ((angle % 360) + 360) % 360;
  return normalized === 90 || normalized === 180 || normalized === 270 ? normalized : 0;
}

/** 画布内容已被浏览器转正，方向字段固定为 0，避免消费端重复旋转。 */
export const FRAME_ROTATION = 0;

export function preferredConstraints(deviceId: string | undefined, resolution: '480p' | '720p'): MediaTrackConstraints {
  const ideal = resolution === '720p' ? { width: 1280, height: 720 } : { width: 640, height: 480 };
  return { ...(deviceId ? { deviceId: { exact: deviceId } } : { facingMode: { ideal: 'environment' } }), ...{ width: { ideal: ideal.width }, height: { ideal: ideal.height } } };
}

export async function listCameras(): Promise<CameraDevice[]> {
  if (!navigator.mediaDevices?.enumerateDevices) return [];
  const devices = await navigator.mediaDevices.enumerateDevices();
  return devices.filter(device => device.kind === 'videoinput').map((device, index) => ({ deviceId: device.deviceId, label: device.label || `摄像头 ${index + 1}` }));
}

/** 理想尺寸不可用时按同样语义回退；用户明确选定的摄像头仍然保持精确指定。 */
export function fallbackConstraints(deviceId: string | undefined): MediaTrackConstraints[] {
  const exact = deviceId ? { deviceId: { exact: deviceId } } : {};
  return [
    { ...exact, width: { ideal: 640 }, height: { ideal: 480 } },
    { ...exact },
    deviceId ? { deviceId: { ideal: deviceId } } : {},
    { facingMode: { ideal: 'environment' } },
    {},
  ];
}

/** 等视频元数据就绪；已有尺寸时立即返回，超时也不阻塞推流。 */
export function waitForMetadata(video: HTMLVideoElement, timeout = 3000): Promise<void> {
  if (video.videoWidth > 0 && video.videoHeight > 0) return Promise.resolve();
  return new Promise(resolve => {
    const done = () => { clearTimeout(timer); video.removeEventListener('loadedmetadata', done); resolve(); };
    const timer = setTimeout(done, timeout);
    video.addEventListener('loadedmetadata', done);
  });
}

export class CameraSource {  private stream?: MediaStream;
  private canvas?: HTMLCanvasElement;
  private context?: CanvasRenderingContext2D;

  constructor(private readonly video: HTMLVideoElement) {}

  get active(): boolean { return !!this.stream; }

  /** 依次尝试理想约束与回退约束，并对刚释放设备的瞬态失败重试；只有全部失败才算不可用。 */
  async open(deviceId: string | undefined, resolution: '480p' | '720p'): Promise<string | undefined> {
    if (!navigator.mediaDevices?.getUserMedia) return '当前浏览器不支持摄像头采集，请改用现代浏览器';
    this.close();
    const attempts = [preferredConstraints(deviceId, resolution), ...fallbackConstraints(deviceId)];
    let lastError: unknown;
    for (let round = 0; round < 3 && !this.stream; round++) {
      for (const video of attempts) {
        try {
          this.stream = await navigator.mediaDevices.getUserMedia({ video, audio: false });
          lastError = undefined;
          break;
        } catch (error) {
          lastError = error;
          const name = error instanceof Error ? error.name : '';
          // 权限被拒、页面不安全，或用户明确选定的摄像头不存在，重试没有意义。
          if (name === 'NotAllowedError' || name === 'SecurityError') break;
        }
      }
      // 刚停止的轨道可能还没释放，等一拍再试；真机上快速“停止 → 开始”也会遇到。
      if (!this.stream && lastError) await new Promise(resolve => setTimeout(resolve, 300 * (round + 1)));
    }
    if (lastError) {
      const name = lastError instanceof Error ? lastError.name : '';
      if (name === 'NotAllowedError') return '摄像头未授权：请在浏览器地址栏允许摄像头后重试';
      if (name === 'NotFoundError' || name === 'OverconstrainedError') return '没有可用的摄像头，或所选摄像头不可用';
      if (name === 'NotReadableError') return '摄像头被其他程序占用，请关闭后重试';
      return '无法启动摄像头，请检查系统权限';
    }
    this.video.srcObject = this.stream!;
    this.video.muted = true;
    this.video.playsInline = true;
    // 画面尺寸要等元数据就绪，否则第一帧可能取不到采集尺寸。
    await waitForMetadata(this.video);
    try { await this.video.play(); } catch { /* 自动播放被拒时采集仍可继续，画面由预览承担 */ }
    return undefined;
  }

  /** 采集一帧并编码；尺寸或 JPEG 结构不符合服务端契约时返回 undefined。 */
  async capture(resolution: '480p' | '720p', capturedAt: number): Promise<CameraFrame | undefined> {
    const sourceWidth = this.video.videoWidth, sourceHeight = this.video.videoHeight;
    if (!sourceWidth || !sourceHeight) return undefined;
    const size = targetSize(sourceWidth, sourceHeight, resolution);
    if (!this.canvas) { this.canvas = document.createElement('canvas'); this.canvas.width = size.width; this.canvas.height = size.height; }
    if (this.canvas.width !== size.width || this.canvas.height !== size.height) { this.canvas.width = size.width; this.canvas.height = size.height; this.context = undefined; }
    const context = this.context ?? this.canvas.getContext('2d', { alpha: false });
    if (!context) return undefined;
    this.context = context;
    context.drawImage(this.video, 0, 0, size.width, size.height);
    const blob = await new Promise<Blob | null>(resolve => this.canvas!.toBlob(resolve, 'image/jpeg', 0.55));
    if (!blob) return undefined;
    const jpeg = trimJpeg(new Uint8Array(await blob.arrayBuffer()));
    const dimensions = jpeg && jpegDimensions(jpeg);
    if (!jpeg || !dimensions || dimensions.width !== size.width || dimensions.height !== size.height) return undefined;
    // 浏览器已按设备方向把视频帧转正，画布画出来的就是当前方向的正常画面；
    // 再上报屏幕角度会让消费端重复旋转，因此方向固定为 0，横竖屏由宽高表达。
    return { jpeg, width: dimensions.width, height: dimensions.height, rotation: 0, capturedAt };
  }

  close(): void {
    this.stream?.getTracks().forEach(track => track.stop());
    this.stream = undefined;
    this.video.srcObject = null;
  }
}
