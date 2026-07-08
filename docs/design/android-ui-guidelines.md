# VisionGuard Android UI Guidelines

本规范是当前 Android UI 的唯一设计风格基准。来源以 `receiver/android/` 当前 Compose 实现为准；目前只有 Android 接收端的新方案被确认采用。Android 检测端、WinForms 和 WPF 仍按毛坯状态看待，旧设计方案不再保留为实现依据。

## 设计定位

- Android 端是运行中的监控操作台，不做营销式首页、装饰性大标题或说明卡。
- 第一屏优先回答状态问题：连接是否正常、是否有报警、设备能否操作。
- 视觉应安静、轻量、可扫描；复杂设置放到上下文操作里，不用独立说明页承载。

## 颜色

| Token | 值 | 用途 |
|---|---|---|
| `ReceiverBackground` | `#F1F6F1` | 页面背景 |
| `ReceiverSurface` | `#FAFCF8` | 卡片、弹窗、底部栏 |
| `ReceiverSurfaceMuted` | `#E9F0EA` | 次级块、未选中控件、输入区 |
| `ReceiverPrimary` | `#1F4638` | 主操作、标题、重要图标 |
| `ReceiverPrimarySoft` | `#DDEAE3` | 主色轻背景 |
| `ReceiverInk` | `#111B18` | 正文主文本 |
| `ReceiverMuted` | `#727C75` | 辅助文本、离线态 |
| `ReceiverAlert` | `#E35B52` | 报警、危险操作、人员目标 |
| `ReceiverAlertSoft` | `#FFE7E3` | 报警轻背景 |
| `ReceiverAmber` | `#D09A25` | 连接中、未就绪 |
| `ReceiverAmberSoft` | `#FFF5DE` | 琥珀轻背景 |

不要启用默认 Material 动态紫色主题。Android 检测端后续迁移时也使用这组 token，可按端名重命名，但语义不要变。

## 字体

- `titleLarge`: 28sp / 34sp，Bold，用于设备名等最强信息。
- `titleMedium`: 20sp / 26sp，SemiBold，用于模块标题、状态标题、弹窗标题。
- `bodyLarge`: 16sp / 24sp，Normal，用于正文。
- `labelLarge`: 14sp / 18sp，SemiBold，用于 chip、按钮、状态辅助信息。
- Letter spacing 固定 `0.sp`；不要用视口宽度动态缩放字号。

## 布局

- 页面背景铺满 `ReceiverBackground`。
- 主内容水平边距 18dp。
- 顶部连接/状态胶囊浮在内容之上，状态栏下方 12dp。
- 底部导航浮在底部，左右 18dp、底部 14dp，整体高度 76dp。
- 顶部/底部浮层要为列表预留空间，避免遮挡内容；接收端当前值为顶部 104dp、底部 132dp。
- 首页不放独立标题栏；连接状态条就是顶部主模块。

## 组件

### 状态胶囊

- 用于 WebSocket、运行状态、更新检查等全局状态。
- 圆角 28dp，水平 16dp、垂直 16dp。
- 左侧图标 24dp，标题使用 `titleMedium`，辅助信息使用 `labelLarge`。
- 可点击时只承担明确操作，例如手动检查更新；不要把多项操作塞进状态条。

### 列表卡片

- 默认圆角 28dp，白色 1dp 边框，0dp elevation。
- 警报卡高度 112dp，三栏结构：设备名、时间和目标 chip、详情箭头。
- 警报列表不展示截图缩略图、截图状态说明或详情文字；截图只在详情页按需加载。
- 设备卡使用上半部 hero 区加下半部操作区；背景图只能作为低透明度语义插图，不能抢主信息。

### Chip 与按钮

- Chip 圆角 18-22dp，文字 14sp，优先图标加文字。
- 主操作用 `ReceiverPrimary` 底色和白色文字。
- 普通操作用 `ReceiverSurfaceMuted` 底色和主色文字。
- 危险操作用 `ReceiverAlertSoft` 底色和 `ReceiverAlert` 文字。
- 数值选择优先 chip / slider / stepper，不用普通文本按钮堆叠。

### 弹窗与底部表单

- 更新弹窗使用 32dp 圆角、24dp 内边距，保持轻量。
- 设备参数调节使用底部 sheet；顶部有 42x5dp handle。
- 表单项按语义分组，每组 28dp 圆角、白色边框、轻背景。
- 应用按钮只有在有变更且设备在线时可用。

### 底部导航

- 只承载主任务 Tab。接收端当前固定为 `警报 / 设备`。
- 设置、检查更新、设备参数等上下文功能不要新增底部 Tab。
- 当前选中项使用主色填充，未选中项透明并使用 muted 文本。

## 图标

- 运行时 UI 图标优先使用已有 Compose Material Icons Extended。
- 不手写散落 SVG/path；缺少图标时先检查现有图标库。
- 图标只表达动作或状态，不承担装饰。

## 交互状态

- 在线：主色 / 主色轻背景。
- 离线：muted 文本和轻背景，不允许执行在线控制。
- 未就绪或连接中：琥珀色。
- 报警或危险动作：红色。
- 检查更新中：禁用重复点击，状态条辅助文案显示“正在检查更新”。
- 空状态要说明下一步会自动发生什么，不写功能介绍。

## 信息架构

接收端当前结构：

- `main/alertList`：连接状态条 + 警报列表。
- `main/deviceList`：设备列表、排序、离线删除和设备参数调节入口。
- `alertDetail/{alertId}`：报警截图和详情，支持按需加载。

不要恢复独立 `Settings` Tab。更新检查留在警报页连接状态条，设备参数留在设备卡上下文入口。

Android 检测端后续迁移时：

- 保留其监控、遮罩、模型和服务器设置等任务分区。
- 视觉语言复用本规范的颜色、圆角、浮层、chip 和状态表达。
- 模型下载状态仍放在模型选择处，不新增模型管理页。
- 监控运行态优先显示当前状态和可执行动作，避免解释性卡片占据首屏。

WinForms / WPF 后续探索时：

- 不从已删除的 Android 原型或旧 Pencil 方案继承布局。
- 先保证检测工作流和构建输出稳定，再单独探索桌面视觉语言。
- 若要复用本规范，只复用颜色和状态语义，不强行套用移动端浮层/底部导航结构。

## 禁止项

- 不再使用旧 HTML 原型、旧 Pencil 导出或一次性生成脚本作为设计来源。
- 不引用 `docs/design/` 下素材到运行时代码。
- 不保留未采用的模块专属 `.pen` 设计源。
- 不使用 Material 默认紫色动态主题。
- 不在列表页预加载报警截图。
- 不用独立设置页承载可放进上下文的动作。
- 不为装饰添加渐变球、玻璃拟物大背景或无语义插图。

## 验证

- UI 模型变化先跑 `receiver/android` JVM 单元测试。
- Android 接收端 UI 改动至少跑 `:app:testDebugUnitTest` 和 `:app:assembleDebug`。
- 视觉变更需要安装到模拟器或真机并截图核对：顶部状态条、底部导航、警报卡、设备卡、弹窗/底部 sheet 不遮挡、不溢出。
