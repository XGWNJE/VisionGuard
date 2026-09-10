# Operations

本文只维护可执行入口、授权边界和验证分类。模块事实见对应专题，验证结果见[验证报告](90-verification-report.md)，不在此复制完整报告。

## 构建

六个组件的统一 Release 构建入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target All
```

可用目标为 `Server`、`WinForms`、`WPF`、`WindowsResident`、`AndroidDetector`、`AndroidReceiver`，也可使用组合目标 `Windows`、`Android`。脚本只编译并检查产物，不打包、不上传、不部署、不改版本。

Windows 驻留程序的生命周期命令以源码为准：`open-wpf`、`open-winforms`、`close-wpf`、`close-winforms`。

构建结果必须按组件分别报告，并在完成后检查：

- Server：`server/dist/index.js`
- Windows WinForms 检测端：`detector/windows-winforms/bin/Release/VisionGuard.exe`
- Windows WPF 检测端：`detector/windows-wpf/bin/x64/VisionGuard.exe`
- Windows 驻留程序：`detector/windows-resident/bin/Release/net9.0-windows/VisionGuard.Resident.exe`
- Android 检测端：`detector/android/app/build/outputs/apk/release/app-release.apk`
- Android 接收端：`receiver/android/app/build/outputs/apk/release/app-release.apk`

Windows 发行输出不得包含 `.pdb`、`.lib`、`.dll.config`、`.onnx`、`Assets/` 或 `alerts/`；模型按需下载，不随发行包分发。详细模型与项目文件边界见[模型资源](35-model-assets.md)。

## 运行与设备验证

运行验证入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode Discover
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode ServerBuild
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WpfPersonDetection
```

Android 运行 smoke 使用 `-Mode AndroidDetectorSmoke` 或 `-Mode AndroidReceiverSmoke`；默认使用 Debug 构建，只有发行验证才使用 `-BuildType Release`。设备选择顺序是已连接且状态为 `device` 的真机，再是可用模拟器；多台真机必须用 `-DeviceSerial` 指定。

结果分类不能合并：

- `ServerBuild` 只验证 TypeScript 编译和 `server/dist/index.js` 产物；历史兼容别名 `ServerSmoke` 也只做同一件事，不是 HTTP/WS 运行测试。
- Android 启动 smoke 只验证安装、启动、前台服务/进程状态和观测窗口内无崩溃，不验证检测端→Server→接收端报警链。
- `WpfPersonDetection` 使用三张含人的图片，要求每路至少一帧 `person`，证明 ImageFile 推理与来源隔离；不证明真实窗口采集或 UI 目检。
- 真实窗口采集、真机 UI、完整报警链、持续运行和故障恢复分别记录为人工/真机/完整 E2E 结果。
- Windows 驻留程序的 Win7 SP1 x64 兼容不是当前构建 smoke 结论，必须在目标环境单独验收；本轮仅登记路线与验收门槛，不执行代码实现。

证据写入 `artifacts/e2e/<timestamp>/`。不要为了本地验证清除应用数据，除非任务明确要求；脚本启动的模拟器必须在结束时关闭。未明确要求时不操作生产服务。

## 发布授权

正式发布唯一入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\publish-release.ps1 -Version <version> -Target All -UploadVps
```

该命令会同步版本、构建、准备签名包、更新 release 元数据、按目标上传并可部署 Server；每一步都需要明确发布授权。仅检查前置条件时使用 `-PreflightOnly`，明确只发客户端时使用 `-SkipServerDeploy`。GitHub push、tag 和 Release 仍需显式开关。

发布前必须确认 Android 签名材料、Windows ZIP 清洁度、元数据大小和目标范围；发布后才可执行公网 `/health`、`/api/update`、`HEAD 200` 和 byte-range `206` 验证。发布脚本是唯一的正式打包/部署实现，不恢复已删除的旧发布入口。

## 配置与服务边界

- Server 真实密钥使用 `server/.env` 或部署环境变量，不能提交。
- Windows 两端优先读取 `VISIONGUARD_API_KEY`；Android 两端由 Gradle 注入 `BuildConfig.API_KEY`。
- VisionGuard 正式域名：`https://visionguard.xgwnje.cn`；根域 `https://xgwnje.cn` 不是新客户端服务地址。
- 当前 VPS/DNS/SNI 事实由 `Server-infra` 项目维护；不要运行旧式 `server/deploy.sh --nginx` 覆盖现有 SNI 架构。
- `VERSION` 只由 owner 明确授权的版本流程修改；普通构建、测试、修复和文档治理不得改动它。

## 文档审核

```powershell
node scripts/check-docs.js
node --test scripts/check-docs.test.js scripts/release-workflow.test.js
```

审核会检查 Markdown 链接和锚点、UTF-8 无 BOM、版本来源、六个组件入口、四个 WS 角色、模型打包边界、Skill 与脚本契约、路线图产品边界和旧入口不存在。新增或删除文档/Skill/入口后必须先更新对应唯一来源，再运行审核。
