# VisionGuard 品牌物料

采用 owner 确认的扫描人简化稿：实心头部、三条等厚圆弧横纹、四角扫描框和橙色扫描线。背景透明的图形使用 64 × 64 坐标；平台启动图标使用深蓝底，不改变业务界面主题。

| 形式 | 文件 | 用途 |
| --- | --- | --- |
| 透明 SVG / PNG | `visionguard-icon.svg` / `visionguard-icon-master.png` | 深色背景上的原始图形；SVG 是可编辑矢量稿，1024 PNG 是平台导出输入 |
| 浅色 SVG / PNG | `visionguard-icon-light.*` | 浅色背景上的深色人形和橙色扫描线 |
| 单色 SVG / PNG | `visionguard-icon-mono.*` / `visionguard-icon-mono-white.*` | 深色单色 / 白色单色透明物料 |
| 圆角方形 / 圆形 SVG | `visionguard-app.svg` / `visionguard-round.svg` | 带深底应用标识 |
| 圆角方形 / 圆形 PNG | `visionguard-windows.png` / `visionguard-round.png` | 1024 应用标识；`visionguard-server-web.png` 与 Windows 版本相同 |
| 字标 SVG | `visionguard-wordmark.svg` / `-light.svg` / `-mono.svg` | 图形与 VisionGuard 名称的横向组合；文字保持可编辑 |
| 多尺寸 PNG | `png/16.png` 至 `png/512.png` | 16、24、32、48、64、128、192、256、512 像素应用标识 |
| 多帧 ICO | `favicon.ico` / `favico2n.ico` | 包含 16、24、32、48、64、128、256 像素帧 |

Windows 主程序、统一启动器和驻留程序共用 `detector/windows-wpf/favico3n.ico`；托盘提取主程序的图标。Web 的 SVG / ICO favicon 及 192 PNG 存在 `receiver/web/public/`，统一服务发布控制台时使用相同资源。相机和通知节点的五档 Android 启动、圆形、自适应前景 / 背景、单色层及 512 商店 PNG 使用同一套导出；单色层由系统着色，通知小图标仍使用既有 Lucide 功能图标。

Android 自适应层采用 108dp 画布，前景等比居中并留出系统裁切安全区；商店 PNG 使用不透明方形背景。系统桌面的最终遮罩、主题图标及 Windows 缓存显示仍需设备目检。`visionguard-gate-master.png` 与中文文件名图片是未引用的旧探索素材，不属于当前应用标识。

## 再生成与检查

修改矢量稿时，将相同轮廓渲染到透明 PNG 母版并同步浅色 / 单色版本；不能用截图、手绘或平台单独变形替代。安装 Pillow 后运行维护入口：

```powershell
python -m pip install Pillow==12.3.0
python scripts/generate-icons.py
python scripts/generate-icons.py --check
```

生成入口导出 75 项平台 / SVG 组合资源。`--check` 逐项核对图像尺寸、像素和 ICO 帧，以及 SVG 组合资源；只读检查不改写资源。重新渲染 SVG 到 PNG 时使用标准 SVG 渲染器，保持透明背景和 1024 × 1024 输出，不能靠放大旧平台图标制作母版。
