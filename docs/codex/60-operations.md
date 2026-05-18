# Operations

这个文件只记录对协作有用、且已经从仓库确认过的操作约束。

## 构建入口

- Server：`cd server && npm ci && npm run build`
- WinForms：打开 `detector/windows-winforms/VisionGuard.slnx`
- WPF：打开 `detector/windows-wpf/VisionGuard.sln`
- Android Detector：使用 `detector/android/`
- Android Receiver：使用 `receiver/android/`

## 发布边界

- 未经明确要求，不改 `VERSION`
- 未经明确要求，不运行 `scripts/sync-version.js`
- 未经明确要求，不运行 `scripts/release.js`
- 未经明确要求，不运行 `scripts/bump-version.sh`

## 建议验证顺序

1. 先确认影响范围
2. 再改最小文件集
3. 最后补构建/测试

## 易错点

- `server/` 和 Android 端协议耦合很强
- 遮罩修改会同时影响识别和截图
- Android 前台服务类型不能混用
- `README.md` 可能存在编码问题，不应作为单一真相
- 项目内旧解释文档已迁移后删除，后续不要恢复双份维护
