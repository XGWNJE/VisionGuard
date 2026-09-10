# Codex Index

这个目录是 VisionGuard 的当前事实、操作和验证入口。每份文档只覆盖一个主题；源码、脚本和测试结果优先于解释性文字。

## 文档分工

- [10-project-overview.md](10-project-overview.md) - 六个实际组件的地图、规范名称和当前状态边界
- [15-product-roadmap.md](15-product-roadmap.md) - 商业化定位、边缘探测器方向、阶段顺序与验收闸门
- [20-server.md](20-server.md) - Server 当前职责、接口、运行参数和协议角色
- [30-windows-detector.md](30-windows-detector.md) - 两个 Windows 检测端和驻留程序的实现事实
- [35-model-assets.md](35-model-assets.md) - ONNX 模型、COCO 类别、目标子集和资源维护约束
- [40-android-detector.md](40-android-detector.md) - Android 检测端的采集、推理、前台服务和 WS
- [50-android-receiver.md](50-android-receiver.md) - Android 接收端的设备列表、告警、前台服务和 WS
- [60-operations.md](60-operations.md) - 构建、验证、发布授权边界和常见风险
- [90-verification-report.md](90-verification-report.md) - 可追溯验证证据、状态判定和未覆盖项
- [../design/README.md](../design/README.md) - 当前设计规范入口

## 维护原则

- README 面向用户和开发者，维护项目介绍、组件入口和常用命令。
- AGENTS.md 维护项目操作规则、稳定约束和授权边界。
- 路线图维护规划、阶段进度和验收状态；验证报告维护自动化、人工和真机证据。
- 当前实现事实回到源码、脚本、构建产物或测试结果核对；规划只进入产品路线图。
- 每项验证写明范围和限制；编译、帧循环、FPS、界面启动和图片读取不能替代业务语义验收。
- `README.md` 只保留用户/开发者入口，`AGENTS.md` 只保留项目操作规则，`CODEX.md` 只保留 AI 导航。
- 版本号、发布脚本、模型文件名不要在未授权情况下改动；历史方案标为失效并指向当前路线图。
- 修改文档、入口、版本、正式域名或产品边界后运行 `node scripts/check-docs.js`；新增本目录文档时必须登记到本索引、根 `README.md` 和 `CODEX.md`。
