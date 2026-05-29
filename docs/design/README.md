# VisionGuard Design

这个目录是仓库统一的设计稿与视觉素材入口。

## 目录分工

- `pencil-exports/android-receiver-alert-list-neumorphic.pen`: Android 接收端警报页 Pencil 源工程，后续还原到代码以它为准。
- `pencil-exports/android-receiver-alert-list-neumorphic.png`: Android 接收端警报页新简约主义 Pencil 设计预览。
- `pencil-exports/component-alert-card-variants.png`: 警报列表卡片组件变体。
- `pencil-exports/component-metric-card-variants.png`: 在线设备等统计卡组件变体。
- `pencil-exports/component-bottom-navigation-variants.png`: 底部导航组件变体。
- `pencil-exports/component-empty-state-variants.png`: 空状态组件变体。
- `pencil-exports/detection-icon-chip-library.png`: 当前 6 类监控目标的图标 chip 备选库。
- `pencil-exports/connection-status-variants.png`: 接收端 WebSocket 连接状态条 4 种状态。
- `assets/app-assets/`: 原根目录 `design/` 中的应用图标、参考截图和视觉素材。

## 维护约定

- 新增通用设计稿和视觉素材优先放在 `docs/design/` 下。
- 模块专属、会被特定工具继续编辑的源文件可以留在模块目录，例如 `detector/android/design/AndroidDetectorDesign.pen`。
- 不要把 `docs/design/assets/app-assets/` 里的图片路径直接写进运行时代码；运行时资源应复制到对应端的正式资源目录。
