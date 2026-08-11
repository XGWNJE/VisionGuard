# VisionGuard Codex Guide

这是给 Codex/AI agent 看的仓库入口说明。详细事实拆分在 `docs/codex/`，避免单文件过长。

## 先读顺序

1. [docs/codex/00-index.md](docs/codex/00-index.md)
2. [AGENTS.md](AGENTS.md)
3. 按任务进入对应专题文档

## 专题文档

- [docs/codex/10-project-overview.md](docs/codex/10-project-overview.md)
- [docs/codex/15-product-roadmap.md](docs/codex/15-product-roadmap.md)
- [docs/codex/20-server.md](docs/codex/20-server.md)
- [docs/codex/30-windows-detector.md](docs/codex/30-windows-detector.md)
- [docs/codex/35-model-assets.md](docs/codex/35-model-assets.md)
- [docs/codex/40-android-detector.md](docs/codex/40-android-detector.md)
- [docs/codex/50-android-receiver.md](docs/codex/50-android-receiver.md)
- [docs/codex/60-operations.md](docs/codex/60-operations.md)
- [docs/codex/90-verification-report.md](docs/codex/90-verification-report.md)

## 稳定约束

- 禁止自动改 `VERSION`
- 修改 `server/` 或 Android 端前，先评估协议兼容性
- 遮罩语义以相对坐标 `[0,1]` 为准
- 以源码和专题文档为准，不再依赖已清理的旧解释文档
- 修改权威版本、域名、文档导航或产品边界后运行 `node scripts/check-docs.js`
- 当前主线许可证为 `VGSAL-1.0`；不要把项目描述成开源 MIT，也不要擅自修改 `LICENSE`、历史 MIT 边界或商业授权范围
