# Prototype Instructions

## Owner direction

- 8.8 inch Android 推理节点的 Web 交互原型,目标设备 16:10 平板;只用 Web 技术实现,不改 Android 原生源码。
- 方向已定稿:保留几何构成,严格使用项目中性灰 / 青色主题令牌与 Lucide 1.49.0 官方图标。
- 用平面几何模拟物理操控:有界纵向 FPS 滚筒、置信度滑轨、十进制冷却计数器、镜头 / 主题拨档、压板式操作键、滑动抽屉。禁止装饰性 3D 明暗、渐变、拟物阴影与圆角胶囊。
- 业务契约承接 `dsh/android-inference`:预览选择与来源运行状态相互独立;运行中冻结冷改动。原型内所有数据与操作结果都是模拟,不连生产服务、不持久化凭据。
- **概念素材已按 owner 要求丢弃**(2026-10-09)。落地唯一依据是预览内的「落地规范」页与[几何空间界面规范](../../docs/72-几何空间界面规范.md);不再保留生成图,也不新引入概念图作为交付物。

## 交付组成

- `src/` 是原型本体:节点预览、32 项控件设计稿、落地规范页。
- 每项控件必须同时出现在控件清单(`src/catalogue.mjs`)、设计稿示例、落地规范表里;清单只按落地分区归类,不再引用图片。
- 规范里的数字必须与 `src/styles.css` 实际值一致,由 `tests/contract.test.mjs` 断言,改样式不改规范会被测试拦住。

## 运行与取证

自己起本地服务并在本环境浏览器里查看,不要给 owner 服务启动说明。

- `npm run dev` 起本地预览;`npm test` 跑契约与规范一致性测试;`npm run build` + `npm run test:sites` 校验交付产物。
- 视觉检查用无头 Chrome 截图,证据放仓库根 `.local/` 下(已忽略,不入库)。
- 细微视觉或较长验证先交 owner 选择,不要自行宣布通过真机目检。

## 记录

- owner 给出的长期原型偏好、决策写进本文件;一次性进度写[任务清单](../../docs/06-任务清单.md)。
- 界面规范只维护[几何空间界面规范](../../docs/72-几何空间界面规范.md),不在本文件复制规范正文。

## Sites 交接

`src/` 承载界面。保留 `.openai/hosting.json`、`worker/index.js`、`scripts/prepare-sites-build.mjs`、`tests/sites-worker.test.mjs` 原样,便于同一原型交给 Sites;交接前跑 `npm run build` 与 `npm run test:sites`,构建须留下 `dist/client/index.html`、`dist/server/index.js`、`dist/.openai/hosting.json`。
