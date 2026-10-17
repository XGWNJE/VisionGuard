# SettingsPanel 功能映射

范围：仅 `SettingsPanel.tsx` / `SettingsPanel.css` 的本地 HTML 评审交互。未修改原生代码，未调用真实账号、系统授权、原生文件、安装器或服务接口。所有来源、账号、性能数值与维护结果都是显式样例；原生能力不能由 HTML 执行的入口仅显示短暂说明。

## 已落地交互与来源

| 草稿控件 / 行为 | 原生证据（仓库根相对路径与行号） | 草稿处理 |
| --- | --- | --- |
| Windows 登录、退出、记住账号密码、登录忙碌与失败 | `detector/windows-wpf/ViewModels/ServerViewModel.cs:17–39,165–193`；`detector/windows-wpf/Views/GlobalSettingsPage.xaml:45–84` | 固定只读 `demo / demo1234`；模拟请求延时；失败情景可重试；不接收或持久化真实密码。`onLogin` 交父组件停止全部来源；注销确认保留参数与遮罩。 |
| Android 登录、记住账号密码、失败 | `android-shared/src/main/java/com/xgwnje/visionguard/account/AccountStore.kt:289–348` | 同一固定样例登录流程。正式登录页和节点账号设置由同一草稿分类承接，不冒充真实认证。 |
| Android 修改密码、退出 | `detector/android-inference/app/src/main/java/com/xgwnje/visionguard/inference/ui/geo/GeoSettingsDrawer.kt:230–301` | 只读示例当前/新密码；明确所有设备需重新登录；确认后仅结束样例会话和停止样例来源。 |
| 设备名称校验 / 保存 / 还原 | Windows `ServerViewModel.cs:238–255`；Android `GeoSettingsDrawer.kt:210–228`；`android-shared/src/main/java/com/xgwnje/visionguard/account/DisplayNamePolicy.kt:4–20` | 1–64 UTF-16 字符、非空、无换行/控制字符；保存样例状态；离线不伪报保存成功；输入错误预留固定高度。 |
| Windows 手动重连 / 失败后重试 | `detector/windows-wpf/ViewModels/ServerViewModel.cs:196–199` | 连接中按钮禁止重复点击；可演示失败；结果走自动消失通知。持续连接状态只由工作区统一状态区表达，设置不重复摆放连接徽标/状态值。 |
| Android 节点指标与连接边界 | `GeoSettingsDrawer.kt:307–341` | 第五版将媒体、远程收帧、校时、累计报警与待回执集中到工作区节点明细，不再藏在配置菜单中。当前样例限额与工作台一致为 4，协议最多 16。不添加正式页不存在的手动重连按钮；不重复全局“最后报警”。 |
| Windows DirectML / CPU 偏好与 legacy 固定 CPU | `detector/windows-wpf/ViewModels/SettingsViewModel.cs:83–97` | modern 下二选一，legacy 固定 CPU。`legacy` 切换明确放在评审场景，不伪装成原生设置。运行来源的实际后端不能因偏好选择被改写。 |
| Windows 驻留刷新、完整退出 | `ServerViewModel.cs:113–120,201–235`；`GlobalSettingsPage.xaml:241–257,288–302` | 仅解释原生行为，不声称已拉起/关闭进程或成功退出。失败时原生保留主体的边界明确。 |
| Android 前台服务、通知/相机权限、系统应用设置 | `GeoSettingsDrawer.kt:378–421` | 第五版运行来源、前台服务、权限状态统一放工作区节点明细；权限标为未读取，系统设置入口仅提示无法由 HTML 执行；不伪造授权开关。 |
| 应用更新 | Windows `ServerViewModel.cs:257–271`；Android `GeoSettingsDrawer.kt:511–516` | 有检查中防重复状态、样例成功/离线失败；显式未查询线上版本、未下载或安装。版本是根 `VERSION` 的源码快照 0.7.8。 |
| Android 缓存盘点 / 清理确认 / 分类结果 / 再次清理为空 | `GeoSettingsDrawer.kt:518–570`；`android-shared/src/main/java/com/xgwnje/visionguard/account/TemporaryCache.kt:19–40`；`TemporaryCachePolicy.kt:3–7` | 仅模拟当前账号过期诊断导出 24h 与已知安装包 7d，保护未过期和安装中项。真实清理范围不包含模型、账号、配置与业务数据。重复清理不再次声称释放相同数据。 |
| Android 崩溃/异常退出刷新、展开、写日志 | `GeoSettingsDrawer.kt:426–488` | 默认空态；评审开关提供一条明确示例记录，可展开；写日志只给原生边界说明，不伪报文件已写入。 |
| Windows 10 秒性能采样、按来源分组、无处理帧 | `detector/windows-wpf/ViewModels/PerformanceAnalysisViewModel.cs:21–38`；`detector/windows-wpf/Services/PerformanceMeasurement.cs:29–54` | 真实经过 10 秒后才产生示例分组结果，采样中禁用按钮；运行后端与目标 FPS 按来源快照，不从全局偏好反推；采集/遮罩/预处理/推理/解析/告警各示例平均/P95/最大值；无来源或“无处理帧”情景不产生性能结论。结果不是设备实测。 |

## 明确排除与源码差异

- Windows 不增加本机清理缓存入口：`GlobalSettingsPage.xaml:262–307` 的维护区只有更新和完整退出；控制台远程维护是另一入口，不被伪装成本机设置。
- Android 正式应用不显示推理加速测量。`GeoSettingsDrawer.kt:545–548,574–578` 要求 `BuildConfig.DEBUG` 且不是 `.uipreview`。本稿不增加该 debug 入口。
- 推流模式、目标发现/绑定不在本稿。Windows `MultiSourceViewModel.cs:384–406` 切换本身先停止正在运行的来源，再尝试恢复符合条件的来源；不是要求用户先手动全部停止，更不能承诺全部自动恢复。草稿仅保留边界说明。
- 原生外观设置未在本组件重做；顶部深浅色切换是评审工具，不等同于原生“跟随系统 / 浅色 / 深色”的完整持久化实现。
- Android 原生维护提示写了“截图”，但实际 `TemporaryCache.kt:25–29` 只生成诊断导出与安装包两类。草稿遵循实现，不扩张清理范围。
- Android 当前 `InferenceRuntime.kt:782–790` 把 `runningSourceCount > 0` 传入缓存有效性检查；`TemporaryCache.kt:22–23` 以此为必需条件。因此无运行来源时本稿返回取消/失败，不伪报成功。这是现有实现差异记录，不在本轮修改原生逻辑。

## 状态与验证

- 普通反馈统一走父组件 `notify` 自动消失，业务报告保留，避免插入成功/失败段落导致重排。保留确认的仅注销、改密与模拟缓存删除。
- timeout/interval 在组件卸载或平台变化时清理；重连回调确认会话仍有效，缓存回调重新确认会话与运行来源。登录/退出忙碌禁用重复提交。
- 针对最终组件运行 `./node_modules/.bin/tsc --noEmit` 通过。父任务负责整体 JSDOM 流程；未声称完成原生/视觉验收。
