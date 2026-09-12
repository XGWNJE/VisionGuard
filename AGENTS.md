# VisionGuard 项目协作规则

本文件只维护项目操作规则、不可违反的约束和稳定工作流。当前组件名称、目录地图和实现状态见 [项目概览](docs/codex/10-project-overview.md)；产品方向见 [产品路线图](docs/codex/15-product-roadmap.md)；各模块事实见 `docs/codex/` 专题。

## 事实与文档边界

- 关键结论必须回到源码、脚本、构建产物或测试结果核实；代码存在不等于功能已交付。
- `README.md` 面向用户和开发者，只保留项目介绍、组件入口和常用命令。
- `AGENTS.md` 只保留本文件的操作规则与安全约束。
- `CODEX.md` 和 `docs/codex/00-index.md` 只负责 AI 导航、文档职责和维护入口。
- `docs/codex/15-product-roadmap.md` 是产品方向、阶段顺序、阶段进度和验收闸门的唯一来源；未来规划必须明确标记。
- `docs/codex/90-verification-report.md` 是验证证据与未覆盖项的唯一来源；不要在其他专题复制完整测试报告。
- `docs/codex/20-*`、`30-*`、`35-*`、`40-*`、`50-*` 分别维护各自模块的当前事实；`60-operations.md` 维护常用操作和授权边界。
- 没有维护价值的历史说明、重复清单和被替代入口不恢复；仍有追溯价值的历史方案必须标记失效并链接当前来源。
- 中文文档使用 UTF-8 无 BOM；新增 `docs/codex/*.md` 必须登记到索引、README 和 CODEX，并通过 `node scripts/check-docs.js`。

## 不可违反的边界

- 根目录 `VERSION` 是唯一权威版本源。修复、构建、测试和提交不得自动 bump；版本同步必须由 owner 明确授权。
- 未经明确授权，不运行版本同步、正式发布、VPS 上传、Server 部署、Git push、Git tag 或 GitHub Release。
- 正式发布唯一入口是 `scripts/publish-release.ps1`；旧的发布或 bump 入口不得重新引入。
- 不修改 `LICENSE`、`LICENSE-MIT`、`LICENSE-HISTORY.md`、商业授权边界或外部贡献政策，除非 owner 明确授权。
- 许可证历史截止点和回溯边界只以根目录 `LICENSE-HISTORY.md` 为准。
- 不在公开位置或历史提交中写入密钥、token、私钥或生产配置；真实配置只使用被忽略的本地文件或环境变量。
- `server/` 与 Android 端协议强耦合；修改任一端后必须核对消息模型、兼容字段和对应测试。
- 修改 `.csproj`、Gradle、发布脚本或删除文件前，先确认源码引用、替代入口和历史价值。
- 构建输出不干净时从 `.csproj`/Gradle 根源修复，不在旧发布脚本中事后删除；编译后用 `Get-ChildItem` 检查输出目录。
- 当前公网业务只通过 Server 中继；不实现或恢复 P2P、ICE、STUN、TURN 等旧路线内容。
- 当前 Server 的离线状态不等同于路线图中的 `DeviceOfflineAlert`；测试结论不得把连接状态或 UI 显示夸大为完整报警链。

## 文档与验证入口

修改文档、组件入口、协议说明、版本/域名边界或路线状态后运行：

```powershell
node scripts/check-docs.js
node --test scripts/check-docs.test.js scripts/release-workflow.test.js
```

项目级 Skill 只保留三个有明确脚本或授权边界的流程；操作细节见[运维文档](docs/codex/60-operations.md)：

- `visionguard-build`：调用 `.agents/skills/visionguard-build/scripts/build-all.ps1` 做 Release 编译和产物核验，不发布。
- `visionguard-e2e`：调用 `.agents/skills/visionguard-e2e/scripts/e2e-smoke.ps1` 做环境发现、ServerBuild、Android 运行烟测和 WPF 多窗口人员推理；不把这些结果称为完整 E2E。
- `visionguard-release`：调用 `scripts/publish-release.ps1` 做需要明确授权的版本发布、部署和公网验证。

构建、运行烟测、完整 E2E 和发布必须分开报告：

- `ServerBuild` 只证明 Server 编译和 `server/dist/index.js` 存在。
- Android 启动 smoke 只证明安装、启动、进程/前台服务和观测窗口内无崩溃。
- WPF 人员窗口 smoke 必须使用独立可见窗口，经 `WindowHandle` 捕获并断言每路至少一帧 `person` 和逐路 FPS；窗口数量按当次验证覆盖的来源数量确定，四路保留为回归基线；静态图片窗口不能替代动态视频。来源容量扩展落地时同步 smoke 脚本，使其按配置的来源数量取证。
- 真机 UI、完整检测端→Server→接收端报警链、持续运行和生产状态必须单独列为人工/真机/生产验证。

## 变更交付

- 保留 owner 已有改动，不覆盖无关文件；本任务结束不自动提交。
- 新增功能必须优先采用满足当前需求的最小设计，并评估长期维护成本；不为未经验证的未来需求增加抽象层、兼容分支或基础设施，不得无必要提高系统复杂度。
- 改动后按影响范围运行最小但真实的语法检查、契约测试和构建验证，并记录失败、跳过和人工验收项。
- 涉及 Android 真机 UI 或运行流程的 ADB 调试，以及模拟器调试，默认开启 `scrcpy`、模拟器窗口或等效的可见预览，全程保持过程可见并在结束后关闭；纯构建、安装包签名验证和只读设备信息检查可以不启动预览。
- UI 改动分批交付，由 owner 在真实设备目检；ADB/模拟器操作不得擅自修改锁屏、休眠或唤醒设置。
- 真机锁屏或休眠后无法由 owner 正常唤醒时，停止调试并报告，等待 owner 解锁后再继续。
