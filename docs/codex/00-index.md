# Codex Index

这个目录是 VisionGuard 的当前事实、操作和验证入口。每份文档只覆盖一个主题；源码、脚本和测试结果优先于解释性文字。

## 文档分工

- [10-project-overview.md](10-project-overview.md) - 检测节点、统一服务与控制台的职责，目录地图和当前实现边界
- [15-component-naming.md](15-component-naming.md) - 组件中文名、英文名、简称及工程、安装、协议、构建更新标识的唯一对照
- [20-server.md](20-server.md) - 统一服务当前职责、接口、运行参数和协议角色
- [30-windows-detector.md](30-windows-detector.md) - 统一启动器、WPF 两套内部运行时和驻留程序的实现事实
- [35-model-assets.md](35-model-assets.md) - ONNX 模型、COCO 类别、目标子集和资源维护约束
- [40-android-detector.md](40-android-detector.md) - VisionGuard 镜头推流的前台摄像头采集、账号与实时媒体 WS
- [50-android-receiver.md](50-android-receiver.md) - Web 管理、通知节点接入及保留的 Android 控制台
- [60-operations.md](60-operations.md) - 构建、验证、发布授权边界和常见风险
- [90-verification-report.md](90-verification-report.md) - 可追溯验证证据、状态判定和未覆盖项
- [../design/README.md](../design/README.md) - 当前设计规范入口

## 维护原则

- README 面向用户和开发者，维护项目介绍、组件入口和常用命令。
- AGENTS.md 维护项目操作规则、稳定约束和授权边界。
- 验证报告维护自动化、人工和真机证据；各模块专题维护当前实现事实与有效约束。
- 实现事实以源码为准；证据范围以验证报告为准；操作与授权以 AGENTS.md 为准。
- 每项验证写明范围和限制；编译、帧循环、FPS、界面启动和图片读取不能替代业务语义验收。
- 版本号、发布脚本、模型文件名不要在未授权情况下改动；验证报告仅维护当前验收结果与未覆盖项，不恢复方案历史。
- 修改文档、入口、版本、正式域名或产品边界后运行 `node scripts/check-docs.js`；新增本目录文档时必须登记到本索引、根 `README.md` 和 `CODEX.md`。
