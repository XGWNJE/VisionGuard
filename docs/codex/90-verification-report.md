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
| WPF 四路真实窗口人员推理 | 已自动验证（本次运行） | 四个独立可见浏览器窗口经 `WindowHandle` 捕获；每路至少 30 帧、`person` 命中且实际 3 FPS，并通过停止隔离、重配隔离、CPU 多路拒绝和 DirectML 失败回退 | `artifacts/e2e/20260911-065336/summary.json`、`artifacts/e2e/20260911-065336/wpf-person-detection.json` |
| WPF 三路人员图片推理 | 已自动验证 | 三张含人图片每路至少一帧 `person`，并通过停止隔离、配置隔离、DirectML 失败回退和 CPU 多路拒绝 | `artifacts/e2e/20260910-063535/summary.json`、`artifacts/e2e/20260910-063535/wpf-person-detection.json` |
| WPF DirectML/CPU 负样本对照 | 已自动验证（有限范围） | 当前界面截图样本在两后端均无有效检测；不证明人员召回或代表性精度 | `artifacts/v3/wpf-directml-cpu-parity.json` |
| WPF 现代化主界面 | 已自动验证（运行与交互）/待人工目检（视觉） | Release 程序可启动且保持响应；“当前信号”“运行环境”“连接”三个右侧标签均可切换并显示各自控件；检测类别多选菜单展示 COCO 80 类，勾选可切换且最后一项不可清空；全局底栏已移除，更新时间、推理耗时、最后报警和后端信息均按来源显示在对应卡片内。三个控制台页面均已去除嵌套卡片并使用高信息密度布局：来源名称与状态单行显示，采集选择动作并排，后端/模型、连接状态、设备名和版本操作分别收敛为单行。自动交互验证覆盖名称点击原位编辑、回车保存、失焦保存和 `settings.ini` 落盘，并在验证后恢复原名称。主要按钮、单行输入框、下拉框和多选入口经 UI Automation 测得均为 40 逻辑像素高，次要清除按钮为 28px；三个页面全部内容在默认窗口内可见。自动检查不替代 owner 对视觉层级、配色和高 DPI 表现的目检 | `artifacts/e2e/wpf-density-source.png`、`artifacts/e2e/wpf-density-environment.png`、`artifacts/e2e/wpf-density-connection-final.png`、`artifacts/e2e/wpf-multiselect-open.png`；`visionguard-build -Target WPF`；Windows UI Automation 原位编辑、自动保存、配置落盘、三页控件高度与可见性、80 个可见复选项及选择约束断言 |
| WPF 采集尺寸与遮罩窗口约束 | 已自动验证（边界、运行枚举与实际拖拽）/待人工目检（遮罩布局） | 统一断言宽高分别严格大于 100 物理像素：`100×101`、`101×100` 和 `99×500` 均拒绝，`101×101` 接受；200% DPI 映射断言覆盖 `50 DIP → 100 px` 拒绝和 `50.5 DIP → 101 px` 接受。Win32 创建并调整为 `100×150` 物理像素的可见测试窗口未出现在实际枚举结果中；在 Release 程序中用系统鼠标实际拖出 `93×93 px` 屏幕选区后，选择器保持打开并显示“选区过小”，未接受配置。窗口选择确认、区域选择、配置就绪、启动、屏幕截图和窗口截图均复用相同边界；遮罩窗口声明 520×360 逻辑像素最小尺寸并在构造时钳制 | `dotnet run --project tests/WindowsConfig.Tests/WindowsConfig.Tests.csproj -c Release`；`visionguard-build -Target WPF`；Windows UI Automation + Win32 鼠标输入；`CaptureSizeConstraints` DPI 映射及边界断言；`MaskEditorWindow.xaml` 最小尺寸 |
| Windows 驻留程序 | 主体实现 | 本机进程级互斥、握手和正常关闭有测试入口；Win7 SP1 x64 兼容、登录重启、崩溃恢复和完整远控 WSS 链路未形成当前证据 | `tests/SingleInstance.Probe/`；路线状态见[产品路线图](15-product-roadmap.md) |
| Android 检测端/接收端启动 | 已自动验证（小米 15） | 指定 `7d3584e1` 设备上的 Debug 构建、安装、清数据、运行时权限、Activity、前台服务和观测窗口无崩溃；不证明完整告警链 | `artifacts/e2e/20260910-105355/summary.json` |
| Android 接收端模拟器启动 | 已自动验证（本次运行） | `VisionGuard_API36` 上 Debug 同签名覆盖安装、运行权限、Activity/进程状态和观测窗口无崩溃；Release APK 已单独构建，因模拟器已有不同签名包而未覆盖安装，未清除应用数据 | `artifacts/e2e/20260911-065633/summary.json`、`artifacts/e2e/20260911-065620/summary.json` |
| Android 两端服务器认证与 MI 6X 运行 | 已自动验证（局部） | MI 6X 上检测端重新编译、安装、启动并记录 `WS 认证成功`，界面显示已连接。接收端最初因旧协议设备记录缺少 `capabilities` 崩溃；现已改为严格接收当前协议并丢弃旧记录，复测记录认证成功、丢弃 3 条旧记录且应用持续运行。该证据不包含真实报警端到端链路 | `artifacts/e2e/20260911-001601/summary.json`、`artifacts/e2e/20260911-003309/summary.json`、`artifacts/e2e/20260911-004712/summary.json`、对应 `android-*-logcat.txt` |
| Android NNAPI 实际推理 | 已自动验证（局部） | 归一化 `yolo26n_320` 在小米 15 上完成 CameraX → 预处理 → 真实推理，profile 记录 `NnapiExecutionProvider`；MI 6X 上同一 provider 实际落到 `nnapi-reference`，约 `471–499 ms/次`，慢于原始模型 CPU 回退的 `157–179 ms/次`。provider 命中不再直接写成硬件执行确认，尚未证明 GPU/NPU/DSP、全图下沉、长期稳定性或精度 | `artifacts/e2e/20260910-111229/android-nnapi-evidence-summary.json`、`artifacts/e2e/20260910-231809-mi6x-nnapi/summary.json`、`artifacts/v4/wpf-person-slicefix.json` |
| Android 设备环境发现 | 已自动验证（历史快照） | 小米 15 `7d3584e1` 曾以 ADB `device` 连接，SoC 为 `SM8750`、Android API 36；MI 6X `1fdbd0b1` 用于本轮 NNAPI、检测端和接收端验证。该记录不表示两台设备现在仍连接 | `artifacts/e2e/20260910-102912/inventory.json`、`artifacts/e2e/20260910-231809-mi6x-nnapi/summary.json`、`artifacts/e2e/20260911-004712/summary.json` |

## 当前实现与未来能力边界

- 当前 Server 维护 WS 认证、心跳、告警广播、截图/更新路由和连接在线状态；`online=false` 不等于 `DeviceOfflineAlert` 已生成或送达。
- `DeviceOfflineAlert`、权威多租户事件存储、可靠 outbox/逐接收端 ACK、独立 Web Management Console、Linux Edge Detector、多传感器融合和 Qualcomm QNN/NCNN 加速仍属于路线图范围；Android NNAPI 已有小米 15 的局部实际 provider 证据，但尚未完成 V4 闭环验收。
- WPF 四个静态图片窗口 smoke 证明真实 `WindowHandle` 捕获和人员推理，但不证明动态业务视频、报警截图、中继链路、长时稳定性或 UI 外观。
- Android 启动 smoke 不证明摄像头推理、模型下载、Server 连接或接收端展示的完整链路。
- Release 构建不等于正式发行包已通过；正式发行还需要签名、ZIP 清洁度、元数据、上传/部署和公网验证。

## 待人工、真机或生产验证

1. Windows 四路现代化 UI 的视觉层级、品牌配色和高 DPI 表现，以及动态窗口重定位及关闭/遮挡/最小化故障矩阵：程序已打开，待 owner 目检/桌面验证。
2. Android 检测端与接收端的真机 UI、归一化模型正式下载、摄像头长时运行、网络切换和温升/资源表现：仍待真机验收。
3. 检测端 → Server → Android 接收端的真实报警、截图归属、重复/重试、离线恢复和 ACK：完整 E2E 尚未执行。
4. Win7 SP1 x64 下 Windows 驻留程序与 WinForms 的安装、TLS/WSS、进程握手、网络/休眠恢复和退出清理：待目标环境；驻留 Win7 兼容是路线图硬门槛，当前未实现。
5. 生产 VPS、正式发行 ZIP/APK、发布回滚和公网更新接口：本次治理未执行。

历史验证记录必须保留原始证据路径，并在重新运行后更新状态；不要仅因日期较新就把历史局部证据升级为完整验收。
