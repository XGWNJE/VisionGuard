# VisionGuard Design

这个目录维护当前采用的设计规范；实现依据以 视觉告警 Compose 源码为准。

## 当前入口

- [android-ui-guidelines.md](./android-ui-guidelines.md)：Android UI 通用规范。当前只有接收端 Compose 方案是已确认基准；视觉检测（Android）与 视觉检测（Windows）当前未采用本规范。

## 维护约定

- Android UI 改动先对照规范，再查看实际 Compose token 和组件实现。
- 新增可长期维护的通用规范放在 `docs/design/`。
- 一次性探索稿、失败原型、未采用素材不要继续提交到仓库。
- 运行时代码不得直接引用 `docs/design/` 下的素材。
- 模块专属设计源只有在已明确采用并会继续维护时才保留；毛坯探索阶段不要提交 `.pen`、HTML 原型或生成脚本。
