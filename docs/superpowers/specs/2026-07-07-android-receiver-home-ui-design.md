# Android Receiver Home UI Design

## Scope

本轮只落地 A 范围：Android 接收端首页骨架，包括警报页、连接状态条、警报卡和底部导航。设备页、警报详情页和参数调节弹窗只做必要的视觉兼容，不重做交互；设置页已取消。

## Design Direction

以 `docs/design/pencil-exports/` 中的浅色新简约主义设计稿为准。整体使用浅灰绿背景、深墨绿主色、红色报警强调和少量琥珀状态色；避免默认 Material 紫色动态主题。首页要像接收端操作台：先看连接状态，再扫最新报警列表。

## Components

- `ReceiverHomeModels`：从现有 `AlertMessage` 和 `DeviceInfo` 派生首页统计、主导航、更新反馈、警报页顶部策略、卡片文案、年月日时间、详情图标描述和目标 chip 数据，保持 UI 可测试。
- `AlertListScreen`：承载首页布局，移除独立警报标题、小标题和右侧图标；连接状态条置顶并兼作手动检查更新入口，下面显示最新警报标题和列表/空状态。
- `ConnectionBanner`：改为图标化状态胶囊，显示 WebSocket 状态和自动接收报警提示，不再使用字符符号。
- `AlertCard`：三栏布局。左栏显示放大的设备名，中栏显示简洁年月日时间和检测目标 tag，右栏只显示单个详情引导图标；列表层不展示截图预览、截图状态或详情文字。
- `MainActivity`：保留警报 / 设备两个 Tab，底部导航改为设计稿风格。
- `Theme`/`Color`/`Type`：固定 VisionGuard 接收端主题，关闭默认动态紫色。

## Constraints

- 不修改协议、服务、版本号、发布脚本或 API Key 注入。
- 不把 `docs/design/assets/app-assets/` 图片直接写进运行时代码。
- 图标优先使用现有 `androidx.compose.material:material-icons-extended`。
- 代码标识符使用英文；用户可见文案可用中文。
- 列表页仍不预加载截图；截图原图仍在详情页按需加载。

## Verification

- 先写并运行 JVM 单元测试，锁住首页统计和警报卡 UI 派生逻辑。
- 编译 Android 接收端。
- 启动 Android 虚拟机安装运行接收端，并截屏给用户看。
