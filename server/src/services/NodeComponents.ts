/** 组件分组判断集中在此，避免各服务各自硬编码组件名单。 */
export type ComponentGroup = 'camera' | 'inference';

/** 相机推流节点：Android 与 Web 两个平台实现，只负责采集与发布，不做本机推理。 */
const CAMERAS = new Set(['android-camera', 'web-camera']);
/** 视觉推理节点：Windows 与 Android 两个平台实现，接收远程镜头画面。 */
const INFERENCE = new Set(['windows-inference', 'android-inference']);

export function isCameraComponent(component: string | undefined): boolean {
  return !!component && CAMERAS.has(component);
}

export function isInferenceComponent(component: string | undefined): boolean {
  return !!component && INFERENCE.has(component);
}
