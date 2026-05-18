# Codex Index

这个目录是 VisionGuard 的“AI 协作说明集”。每份文档只覆盖一个主题，避免单页过长，也降低局部过时风险。

## 文档分工

- [10-project-overview.md](10-project-overview.md) - 项目全局地图、统一概念、跨端约束
- [20-server.md](20-server.md) - Server 职责、接口、环境变量、告警/截图/更新
- [30-windows-detector.md](30-windows-detector.md) - WinForms / WPF 检测端差异、推理链、设置与热更新
- [35-model-assets.md](35-model-assets.md) - ONNX 模型、COCO 类别、目标子集、资源维护约束
- [40-android-detector.md](40-android-detector.md) - Android 检测端、前台服务、模型、DataStore、WS
- [50-android-receiver.md](50-android-receiver.md) - Android 接收端、前台服务、列表/详情、WS
- [60-operations.md](60-operations.md) - 构建、验证、发布、版本边界、常见风险
- [90-verification-report.md](90-verification-report.md) - 已验真事实、旧说明冲突、谨慎表述点

## 维护原则

- 只写已从源码验证过的事实
- 写不死的内容时注明“当前实现”
- 版本号、发布脚本、模型文件名不要在未授权情况下改动
- 已迁移并废弃的旧解释文档不再作为事实来源
