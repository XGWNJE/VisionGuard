# Verification Report

本报告基于当前仓库源码逐项核对，用来替代“默认相信现存说明文档”的做法。

## 已核对范围

- 根目录：`AGENTS.md`、`CODEX.md`、`README.md`
- Server：入口、配置、WS 连接管理、告警、截图、更新路由
- Windows：WinForms / WPF 关键项目文件、配置、迁移说明
- Android Detector：包常量、设置仓库、前台服务、消息模型
- Android Receiver：包常量、设置仓库、前台服务、消息模型
- 资源说明：两个 `ASSETS_README.md`、两份 `COCO_CLASSES.md`

## 已验证事实

- 根 `VERSION` 当前为 `4.3.0`
- Server `package.json` 当前版本为 `4.3.0`
- Server 线上 smoke 已在 `https://visionguard.xgwnje.cn` 通过：`/health`、`/api/update`、`/releases/*`、`/models/*`、`/ws`
- Android 接收端实机 UI 已显示可连接，后续继续观察真实告警链路
- 2026-07-08 Android 接收端模拟器 smoke 已验证设备页 UI、手动排序持久化、离线保留、离线侧滑删除、恢复联网并回列表；证据目录为 `artifacts/e2e/20260708-004420-manual-receiver-emulator/`
- Server 认证失败后会关闭连接，WS 认证超时为 5000ms
- `server/src/routes/update.ts` 当前按平台读取 `data/releases.json`
- `server/src/routes/screenshot.ts` 当前要求 `X-API-Key`
- `server/src/routes/alerts.ts` 当前只返回脱敏后的告警摘要
- Android Detector 当前包名为 `com.xgwnje.visionguard`
- Android Receiver 当前包名为 `com.xgwnje.visionguard_android`
- Windows 两端当前通过共享 `ApiKeyProvider` 解析 API key：环境变量优先，未配置时保留发行兼容兜底
- Android 两端 API key 当前由 Gradle 注入 `BuildConfig.API_KEY`，本地可通过 `VISIONGUARD_API_KEY` 提供
- Android Detector 当前前台服务类型为 `camera`
- Android Receiver 当前前台服务类型为 `remoteMessaging`
- Android Detector 当前使用 DataStore 持久化设置
- Android Receiver 当前使用 DataStore 持久化设置

## 旧说明与源码不一致之处

1. 早期根目录说明如果存在乱码或旧架构表述，不能作为单一事实来源。
2. 早期说明中如果写成 `4.2.1` 或更早版本，当前根版本源已经是 `4.3.0`。
3. 早期说明中如果把 Android API key 写成源码常量，当前实现已改为 Gradle 构建注入。
4. 早期说明中如果把 Android Detector 说成支持 `Preview`，当前源码和实现都指向仅 `ImageAnalysis`。
5. 早期说明中如果把 Server 截图下载描述成公开访问，当前源码要求 `X-API-Key`。

## 迁移结论

- 根目录旧解释文档应由 `docs/codex/` 取代
- WPF 迁移说明中的结构性信息已适合并入 Windows 专题文档
- 资产说明和 COCO 类目说明存在跨目录重复，适合收敛成单一模型资源文档

## 需要谨慎表述的内容

- 具体心跳间隔字段在不同层可能以“业务心跳”或“幽灵阈值”出现，写说明时应引用源码常量，不要口头简化。
- 自动更新的触发路径和强制升级逻辑要按 `server/src/routes/update.ts` 与各端 `AutoUpdater` 实现描述，不要泛化成“统一更新”。
- Windows WinForms 与 WPF 的模型格式不同，不能合并写成“Windows 端统一模型”。

## 建议后续动作

如果要继续提升可信度，下一步应做两件事：

1. 为 `docs/codex/` 增加按模块的“证据链接”索引，把关键结论绑定到具体源码文件。
2. 把 Android 签名模板和发布前密钥轮换步骤整理成独立操作文档。
