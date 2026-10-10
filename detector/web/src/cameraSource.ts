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

export function preferredConstraints(deviceId: string | undefined, resolution: '480p' | '720p'): MediaTrackConstraints {
  const ideal = resolution === '720p' ? { width: 1280, height: 720 } : { width: 640, height: 480 };
  return { ...(deviceId ? { deviceId: { exact: deviceId } } : { facingMode: { ideal: 'environment' } }), ...{ width: { ideal: ideal.width }, height: { ideal: ideal.height } } };
}

export async function listCameras(): Promise<CameraDevice[]> {
  if (!navigator.mediaDevices?.enumerateDevices) return [];
  const devices = await navigator.mediaDevices.enumerateDevices();
  return devices.filter(device => device.kind === 'videoinput').map((device, index) => ({ deviceId: device.deviceId, label: device.label || `摄像头 ${index + 1}` }));
}

export class CameraSource {
  private stream?: MediaStream;
  private canvas?: HTMLCanvasElement;
  private context?: CanvasRenderingContext2D;

  constructor(private readonly video: HTMLVideoElement) {}

  get active(): boolean { return !!this.stream; }

  async open(deviceId: string | undefined, resolution: '480p' | '720p'): Promise<string | undefined> {
    if (!navigator.mediaDevices?.getUserMedia) return '当前浏览器不支持摄像头采集，请改用现代浏览器';
    try {
      this.close();
      this.stream = await navigator.mediaDevices.getUserMedia({ video: preferredConstraints(deviceId, resolution), audio: false });
    } catch (error) {
      const name = error instanceof Error ? error.name : '';
      if (name === 'NotAllowedError') return '摄像头未授权：请在浏览器地址栏允许摄像头后重试';
      if (name === 'NotFoundError' || name === 'OverconstrainedError') return '没有可用的摄像头，或所选摄像头不可用';
      if (name === 'NotReadableError') return '摄像头被其他程序占用，请关闭后重试';
      return '无法启动摄像头，请检查系统权限';
    }
    this.video.srcObject = this.stream;
    this.video.muted = true;
    this.video.playsInline = true;
    try { await this.video.play(); } catch { return '摄像头画面无法播放，请重试'; }
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
    return { jpeg, width: dimensions.width, height: dimensions.height,
      rotation: rotationFromOrientation(window.screen?.orientation?.angle ?? 0), capturedAt };
  }

  close(): void {
    this.stream?.getTracks().forEach(track => track.stop());
    this.stream = undefined;
    this.video.srcObject = null;
  }
}
