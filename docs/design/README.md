# VisionGuard Design

这个目录维护当前采用的设计规范；Web 与通知节点 采用统一浅深色规范，保留的 Android 控制台按其 Compose 规范维护。

## 当前入口

- [unified-ui.md](./unified-ui.md)：Web 控制台与通知节点的统一颜色、布局、控件和浅深色规范。
- [android-ui-guidelines.md](./android-ui-guidelines.md)：三个 Android 组件的现代简约 Compose 规范；Windows 视觉节点保留现有外观。

## 维护约定

- Android UI 改动先对照规范，再查看实际 Compose token 和组件实现。
- 新增可长期维护的通用规范放在 `docs/design/`。
- 一次性探索稿、失败原型、未采用素材不要继续提交到仓库。
- 运行时代码不得直接引用 `docs/design/` 下的素材。
- 模块专属设计源仅在明确采用并持续维护时保留。
