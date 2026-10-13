# VPS 磁盘占用盘点与回收

负责：统一服务所在 VPS（`novixlink-lax`）的磁盘占用盘点结论与回收候选清单。不负责：回收授权（一律另行申请）、其它节点（东京）与 VisionGuard 内部数据保留策略。更新时机：执行回收后或占用复核时；回收落地后删除本文并把读数并入[验证记录](../90-验证记录.md)。

范围：只覆盖 `xgwnje` 指向的 LAX 节点（9.6G 根分区），2026-10-14 实时只读读数（`du` / `df` / `ls`）。本轮未删除、未改动任何文件。

## 读数（2026-10-14）

根分区 9.6G，已用 7.0G，可用 2.6G（73%）。项目侧合计约 1.7G，系统侧约 5.3G。

### 项目侧（从大到小）

| 项目 | 位置 | 占用 | 明细 |
|---|---|---|---|
| VisionGuard 统一服务 | `/opt/visionguard-server` | 1.2G | 模型 728M、组件包 205M、快照独有约 225M、代码与依赖约 12M |
| VisionGuard 账号测试实例 | `/opt/visionguard-account-test` | 241M | 2026-10-07 节点身份备份 230M + app 7M |
| 主页 API | `/opt/homepage-api` | 163M | 两个 81M 全量包（`current` → `20260822T105336Z`）+ data 1.6M |
| 代理 / 订阅 | `/usr/local/bin/sing-box`、`/var/lib/vps-proxies-subscription` | 59M | 二进制 58M + 订阅残留 404K |
| 主页静态站 | `/var/www/xgwnje-home` + `.releases` | 56M | `current` 12M + `previous` 44M |
| TouchDeck signal | `/opt/touchdeck-signal` | 336K | |
| VisionGuard 备份目录 | `/opt/visionguard-backups` | 4K | 空 |

硬链接提醒：模型与组件包文件 link count = 2，`backups/` 里的快照与 `data/` 共享同一批 inode；单次 `du` 只算一次，跨目录分别统计时会看到「快照 1.2G」的假象。

### 系统侧（非项目）

| 项 | 位置 | 占用 |
|---|---|---|
| 系统本体 | `/usr` | 1.9G |
| swapfile | `/swapfile` | 1.0G（swap 实用 265M） |
| 共享 systemd journal | `/var/log/journal` | 896M（本项目约 5%） |
| snap 包 | `/var/lib/snapd/snaps` | 602M（core20 / core22 / lxd / snapd 各留两个 revision） |
| apt 数据库 + 缓存 | `/var/lib/apt` + `/var/cache/apt` | 454M |
| 传统文本日志 | `/var/log/*.log*` | 162M（`syslog.1` 72M、`syslog` 29M） |
| 内核与引导 | `/boot` | 111M |
| root 构建缓存 | `/root/.cache/node-gyp` | 54M |

## 推荐操作

### A. 可直接执行（低风险）

| 操作 | 回收 | 依据 |
|---|---|---|
| `apt clean` + `apt autoremove` | 136M | 已安装包的下载缓存 |
| 删 `/root/.cache/node-gyp` | 54M | node 头文件编译缓存 |
| 删 `/opt/homepage-api/releases/20260822T104610Z-full-c4dec4b` | 81M | `current` 指向 `20260822T105336Z`，该包无引用 |
| 轮转 / 清空 `/var/log/syslog*`、`kern.log*`、`ufw.log*`、`dmesg*` | ~150M | 与 journal 重复记录，排障窗口由 journal 保留 |

### B. 需先确认引用

| 操作 | 回收 | 确认点 |
|---|---|---|
| 删 `/opt/visionguard-account-test/backups/20261007-node-identity` | 230M | 该测试实例是否还需要回滚点 |
| `snap remove lxd` | 241M | `lxd` 与 `snap.lxd.daemon` 均 inactive，`lxc list` 确认无容器 |
| 删 core20 / core22 / snapd 的旧 revision | 196M | 确认当前 revision 工作正常后逐个删 |
| 删 `/opt/visionguard-server/data/releases/VisionGuard-Receiver-v0.6.0.apk` | 12M | 核对 `data/releases.json` 无引用 |

### C. 需与 Server-infra 协调

| 操作 | 回收 | 说明 |
|---|---|---|
| `journald.conf` 设 `SystemMaxUse=512M` | ~384M | 共享 journal，对应[待办 9](../06-待办.md#待办)；改动前协调并留证 |

合计候选约 **1.4G**（A 约 420M、B 约 680M、C 约 384M）。

### 不动

- `/opt/visionguard-server/backups/` 的最新快照：当前唯一回滚点，按策略保留（与 `data` 硬链接之外的约 225M 独有内容一并保留）。
- `/swapfile` 1.0G：swap 在使用，缩容风险大于收益。
- `/usr`、`/boot`、主页 `.releases` 的 `current` 与 `previous`：系统本体与主页回滚点。

## 执行口径

- 顺序：先 A，再逐项确认 B，最后协调 C；每步只碰列出的路径，删前复核引用与时间戳，记录前后 `df -h /`。
- 全部删除操作需 owner 授权；本轮只做只读盘点。
- 回收后把读数与释放量并入[验证记录](../90-验证记录.md)，并删除本文。

## 未覆盖

- 未核验快照里那 225M 独有内容的来源与价值。
- 未连接东京节点，未做文件级重复统计。
- 未执行 `--vacuum`，未改 `journald.conf`，未动 snap、apt 与任何日志。
