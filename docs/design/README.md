# VisionGuard Design

这个目录维护全部产品界面的现代简洁主题。颜色、字体、控件与逐页检查范围只在统一规范中定义；平台细则引用该规范。

## 当前入口

- [unified-ui.md](./unified-ui.md)：Windows、Web 与三个 Android 组件的统一主题、控件、状态及完整页面检查范围。
- [android-ui-guidelines.md](./android-ui-guidelines.md)：共享 Compose 主题、系统界面与各 Android 应用的布局细则。

## 维护约定

- UI 改动先对照统一规范，再检查实际平台主题和组件实现；新增页面同时补齐页面清单。
- 新增可长期维护的通用规范放在 `docs/design/`。
- 一次性探索稿、失败原型、未采用素材不要继续提交到仓库。
- 运行时代码不得直接引用 `docs/design/` 下的素材。
- 模块专属设计源仅在明确采用并持续维护时保留。
