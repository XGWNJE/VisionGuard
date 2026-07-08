# VisionGuard Design

这个目录只保留当前仍有效的设计规范。旧 Android 接收端 HTML 原型、Android 检测端 Pencil 源、一次性生成脚本、Pencil 导出和未采用素材已清理，不能再作为实现依据。

## 当前入口

- [android-ui-guidelines.md](./android-ui-guidelines.md)：Android UI 通用规范。当前只有接收端 Compose 方案是已确认基准；Android 检测端后续迁移复用这套视觉语言，Windows 端后续另行探索。

## 维护约定

- Android UI 改动先对照规范，再查看实际 Compose token 和组件实现。
- 新增可长期维护的通用规范放在 `docs/design/`。
- 一次性探索稿、失败原型、未采用素材不要继续提交到仓库。
- 运行时代码不得直接引用 `docs/design/` 下的素材。
- 模块专属设计源只有在已明确采用并会继续维护时才保留；毛坯探索阶段不要提交 `.pen`、HTML 原型或生成脚本。
