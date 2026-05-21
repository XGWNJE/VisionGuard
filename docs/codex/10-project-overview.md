# Project Overview

VisionGuard 是一个三端联动的 AI 实时监控系统。

## 架构

- 检测端：Windows WinForms、Windows WPF、Android Detector
- 中继端：`server/`
- 接收端：`receiver/android/`

## 仓库定位

- `AGENTS.md` 是顶层约束，不属于待清理的旧说明
- `docs/codex/` 是当前唯一维护的解释性文档集合
- 历史 `README.md`、`CLAUDE.md`、模块内说明文档已迁移后清理，不再作为事实来源

## 核心链路

`Capture -> MaskApply -> Preprocess -> ONNX Inference -> Parse -> AlertDecision -> Push`

## 统一概念

- 遮罩使用相对坐标 `[0,1]`
- 遮罩在推理前涂黑，同时影响识别结果与告警截图
- WS 角色固定为 `windows`、`android`、`android-detector`
- VisionGuard 正式服务域名固定为 `https://visionguard.xgwnje.cn`
- 根域 `https://xgwnje.cn` 属于个人主页，不再作为新客户端的 VisionGuard 服务地址

## 不变边界

- `VERSION` 是权威版本源，不能自动 bump
- `server/` 和 Android 端是强耦合，协议变更必须联动核对
- 心跳策略按当前实现固定：检测端 3s、接收端 30s、幽灵阈值 45s
- 客户端 `SERVER_URL`、自动更新地址和 Nginx 部署目标必须保持同一项目子域名

## 文档可信度规则

- 先信源码，再信 `docs/codex/`
- 不信历史说明里的分支名、时间戳、手工维护版本号
- 涉及模型、资源、类目映射时，以项目文件和源码内静态表为准
