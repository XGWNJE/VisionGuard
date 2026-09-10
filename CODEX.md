# VisionGuard Codex Guide

这是给 Codex/AI agent 的仓库导航，不维护第二套项目事实。先读[文档索引](docs/codex/00-index.md)和[项目规则](AGENTS.md)，再进入对应专题。

## 专题文档

- [项目概览](docs/codex/10-project-overview.md)：六个实际组件、规范名称和当前实现状态
- [产品路线图](docs/codex/15-product-roadmap.md)：产品方向、阶段顺序和验收闸门
- [Server](docs/codex/20-server.md)：Server 当前职责、接口和协议角色
- [Windows](docs/codex/30-windows-detector.md)：WinForms、WPF 和驻留程序
- [模型资源](docs/codex/35-model-assets.md)：模型、类别映射和打包边界
- [Android 检测端](docs/codex/40-android-detector.md)
- [Android 接收端](docs/codex/50-android-receiver.md)
- [运维](docs/codex/60-operations.md)：构建、验证和发布授权边界
- [验证报告](docs/codex/90-verification-report.md)：证据、状态和未覆盖项

## 稳定协作边界

- 不自动修改 `VERSION`，不自动同步版本，不发布、部署、上传、打 tag 或提交。
- 修改 `server/` 或 Android 端前先评估协议耦合；修改构建配置或删除文件前先核对引用。
- 遮罩语义以相对坐标 `[0,1]` 为准；当前项目级自动审核入口为 `node scripts/check-docs.js`。
- 当前主线许可证为 `VGSAL-1.0`；不要将项目描述成开源 MIT，也不要修改许可证、商业授权边界或外部贡献政策。
