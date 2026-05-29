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

## 服务域名边界

- VisionGuard 正式域名：`https://visionguard.xgwnje.cn`
- 个人主页根域：`https://xgwnje.cn`
- 不要把新客户端配置回根域或泛用 `api.xgwnje.cn`
- 更新 Nginx 时使用 `server/nginx-visionguard.conf` 和 `server/deploy.sh --nginx`，目标站点应是 `visionguard.xgwnje.cn`

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
- 根域存在旧客户端兼容入口，不代表根域仍是 VisionGuard 正式服务地址
- 发行包冗余文件必须从 .csproj/gradle 根源解决，不要在 release.js 中事后删除
- 修改构建配置或删除文件前，先 `grep` 确认无代码引用
- 编译通过后必须 `Get-ChildItem` 检查输出目录，确保无多余文件
- 模型文件不打包进发行包，发布时由 release.js 收集到 `server/data/models/`
- Android Release 必须用 `assembleRelease`（不是 Debug），必须签名
