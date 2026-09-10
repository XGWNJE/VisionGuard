# Verification Report

本文是 VisionGuard 的验证证据台账，不是产品路线图，也不把源码存在、编译成功、帧循环、FPS、界面启动或截图展示写成完整功能通过。每条结论都注明验证范围；详细命令由[运维文档](60-operations.md)和两个保留 Skill 维护。

## 判定词

- **已自动验证**：有可复现命令和机器可读结果，断言覆盖了所写语义。
- **主体实现**：源码已具备功能，但验证范围不足以宣布完整交付。
- **待人工目检**：需要 owner 检查真实 UI、窗口采集或发行包表现。
- **待真机**：需要授权的真实 Android 设备或目标硬件。
- **未实现/未来规划**：当前源码没有该能力，不能用局部 smoke 代替。

## 当前仓库证据

| 范围 | 状态 | 本次证据能证明什么 | 证据 |
|---|---|---|---|
| 文档与治理审核 | 已自动验证（本次运行） | Markdown 链接/锚点、编码、版本、组件、入口、Skill 和产品边界符合契约 | `node scripts/check-docs.js`；本文件所在提交后的命令输出 |
| Server 单测与协议测试 | 已自动验证 | Server 配置、告警/截图安全、控制请求关联和 WS 协议测试通过 | `npm --prefix server test`；`server/dist/index.js` |
| ServerBuild | 已自动验证 | TypeScript 编译成功且 `server/dist/index.js` 存在；不是 HTTP/WS 运行 E2E | `artifacts/e2e/20260910-063530/summary.json`、`server-build.txt` |
| WPF 三路人员图片推理 | 已自动验证 | 三张含人图片每路至少一帧 `person`，并通过停止隔离、配置隔离、DirectML 失败回退和 CPU 多路拒绝 | `artifacts/e2e/20260910-063535/summary.json`、`artifacts/e2e/20260910-063535/wpf-person-detection.json` |
| WPF DirectML/CPU 负样本对照 | 已自动验证（有限范围） | 当前界面截图样本在两后端均无有效检测；不证明人员召回或代表性精度 | `artifacts/v3/wpf-directml-cpu-parity.json` |
| Windows 驻留程序 | 主体实现 | 本机进程级互斥、握手和正常关闭有测试入口；Win7 SP1 x64 兼容、登录重启、崩溃恢复和完整远控 WSS 链路未形成当前证据 | `tests/SingleInstance.Probe/`；路线状态见[产品路线图](15-product-roadmap.md) |
| Android 检测端/接收端启动 | 已自动验证（小米 15） | 指定 `7d3584e1` 设备上的 Debug 构建、安装、清数据、运行时权限、Activity、前台服务和观测窗口无崩溃；不证明完整告警链 | `artifacts/e2e/20260910-105355/summary.json` |
| Android NNAPI 实际推理 | 已自动验证（局部） | 归一化 `yolo26n_320` 在小米 15 上完成 CameraX → 预处理 → 真实推理，profile 记录 `NnapiExecutionProvider`；`CPU_DISABLED` 下仍有 45 个 CPU 事件，未证明全图下沉、GPU/NPU 具体单元、长期稳定性或精度 | `artifacts/e2e/20260910-111229/android-nnapi-evidence-summary.json`、`artifacts/v4/wpf-person-slicefix.json` |
| 当前 Android 环境发现 | 已自动验证 | 指定小米 15 `7d3584e1` 为可用 ADB `device`，SoC 为 `SM8750`，Android API 36；本轮命令均显式绑定该序列号 | `artifacts/e2e/20260910-102912/inventory.json` |

## 当前实现与未来能力边界

- 当前 Server 维护 WS 认证、心跳、告警广播、截图/更新路由和连接在线状态；`online=false` 不等于 `DeviceOfflineAlert` 已生成或送达。
- `DeviceOfflineAlert`、权威多租户事件存储、可靠 outbox/逐接收端 ACK、独立 Web Management Console、Linux Edge Detector、多传感器融合和 Qualcomm QNN/NCNN 加速仍属于路线图范围；Android NNAPI 已有小米 15 的局部实际 provider 证据，但尚未完成 V4 闭环验收。
- WPF ImageFile smoke 不证明真实窗口采集；界面截图只能作为负样本或性能输入，不能作为人员检测正样本。
- Android 启动 smoke 不证明摄像头推理、模型下载、Server 连接或接收端展示的完整链路。
- Release 构建不等于正式发行包已通过；正式发行还需要签名、ZIP 清洁度、元数据、上传/部署和公网验证。

## 待人工、真机或生产验证

1. Windows 真实窗口采集、三路 UI 外观、窗口重定位和故障矩阵：待 owner 目检/桌面验证。
2. Android 检测端与接收端的真机 UI、归一化模型正式下载、摄像头长时运行、网络切换和温升/资源表现：仍待真机验收。
3. 检测端 → Server → Android 接收端的真实报警、截图归属、重复/重试、离线恢复和 ACK：完整 E2E 尚未执行。
4. Win7 SP1 x64 下 Windows 驻留程序与 WinForms 的安装、TLS/WSS、进程握手、网络/休眠恢复和退出清理：待目标环境；驻留 Win7 兼容是路线图硬门槛，当前未实现。
5. 生产 VPS、正式发行 ZIP/APK、发布回滚和公网更新接口：本次治理未执行。

历史验证记录必须保留原始证据路径，并在重新运行后更新状态；不要仅因日期较新就把历史局部证据升级为完整验收。
