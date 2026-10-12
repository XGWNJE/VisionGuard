/** 组件分组判断集中在此，避免各服务各自硬编码组件名单。 */
export type ComponentGroup = 'camera' | 'inference';

/** 相机推流节点：Android 与 Web 两个平台实现，只负责采集与发布，不做本机推理。 */
const CAMERAS = new Set(['android-camera', 'web-camera']);
/** 视觉推理节点：Windows 与 Android 两个平台实现，接收远程镜头画面。 */
const INFERENCE = new Set(['windows-inference', 'android-inference']);
/**
 * 也能发布视频的推理实现：Windows 节点在「推流」态发布本机采集的窗口 / 屏幕画面。
 * 发布放行按能力判断（`video-publish`），这里只回答某个组件实现是否**可以**声明该能力。
 */
const VIDEO_PUBLISHING_INFERENCE = new Set(['windows-inference']);

export function isCameraComponent(component: string | undefined): boolean {
  return !!component && CAMERAS.has(component);
}

export function isInferenceComponent(component: string | undefined): boolean {
  return !!component && INFERENCE.has(component);
}

/** 该组件实现是否允许发布视频：相机推流节点，或在推流态工作的 Windows 推理节点。 */
export function canPublishVideo(component: string | undefined): boolean {
  return isCameraComponent(component) || (!!component && VIDEO_PUBLISHING_INFERENCE.has(component));
}

/** 该组件实现是否允许订阅视频：两个推理实现都接收远程镜头画面。 */
export function canSubscribeVideo(component: string | undefined): boolean {
  return isInferenceComponent(component);
}
