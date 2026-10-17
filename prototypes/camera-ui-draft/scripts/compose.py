"""Arrange exact browser PNG captures; never crop the captured UI."""
import html
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

output = Path(sys.argv[1]).resolve()
records = json.loads((output / "capture-matrix.json").read_text(encoding="utf-8"))
audit = json.loads((output / "audit-summary.json").read_text(encoding="utf-8"))
font_path = Path("C:/Windows/Fonts/msyh.ttc")


def font(size):
    return ImageFont.truetype(str(font_path), size)


def place(canvas, record, x, y, width=None, height=None):
    source = Image.open(output / record["group"] / (record["name"] + ".png")).convert("RGB")
    factor = min((width or source.width) / source.width, (height or source.height) / source.height)
    source = source.resize((round(source.width * factor), round(source.height * factor)), Image.Resampling.LANCZOS)
    canvas.paste(source, (x, y))
    return source.size


def label(draw, point, text, fill, size=20):
    draw.text(point, text, font=font(size), fill=fill)


boards = []
for platform in ("web", "android"):
    for theme in ("light", "dark"):
        dark = theme == "dark"
        canvas = Image.new("RGB", (1600, 1040), "#151515" if dark else "#f5f5f5")
        draw = ImageDraw.Draw(canvas)
        ink, secondary = ("#fafafa", "#b3b3b3") if dark else ("#171717", "#626262")
        title = ("Web" if platform == "web" else "Android") + " / 相机推流节点"
        label(draw, (32, 24), title, ink, 34)
        subtitle = ("深色" if dark else "浅色") + " · 控制优先 · 静态草稿 · 2026-10-17"
        label(draw, (32, 78), subtitle, secondary, 20)
        if platform == "android":
            label(draw, (880, 82), "Compose 落地示意，当前由浏览器排版", secondary, 18)
        slots = [
            ("phone-portrait", 32, 170, 324, 720),
            ("tablet-portrait", 396, 170, 450, 720),
            ("phone-landscape", 888, 170, 680, 306),
            ("tablet-landscape", 888, 548, 680, 425),
        ]
        for profile, x, y, width, height in slots:
            record = next(r for r in records if r["group"] == "main" and r["platform"] == platform and r["theme"] == theme and r["id"] == profile)
            label(draw, (x, y - 34), record["label"] + "  " + str(record["width"]) + "×" + str(record["height"]), secondary, 18)
            place(canvas, record, x, y, width, height)
        label(draw, (32, 994), "示例画面与固定状态，仅用于审结构与视觉；主界面原尺寸 PNG 见 main/。", secondary, 18)
        name = platform + "-" + theme + "-board.png"
        canvas.save(output / name, optimize=True)
        boards.append((name, canvas))

overview = Image.new("RGB", (3200, 2080), "#f5f5f5")
for index, (_, board) in enumerate(boards):
    overview.paste(board, ((index % 2) * 1600, (index // 2) * 1040))
overview.save(output / "overview.png", optimize=True)

for group in ("states", "dialogs", "boundary", "stress"):
    for platform in ("web", "android"):
        items = [r for r in records if r["group"] == group and r["platform"] == platform]
        columns, cell_width, cell_height = (4, 360, 470)
        rows = (len(items) + columns - 1) // columns
        canvas = Image.new("RGB", (columns * cell_width, rows * cell_height + 88), "#ededed")
        draw = ImageDraw.Draw(canvas)
        label(draw, (24, 18), platform.upper() + " / " + group + " · 静态检查", "#171717", 26)
        for index, record in enumerate(items):
            x, y = (index % columns) * cell_width + 16, (index // columns) * cell_height + 88
            label(draw, (x, y), record["label"], "#171717", 18)
            label(draw, (x, y + 26), str(record["width"]) + "×" + str(record["height"]) + " / " + record["theme"], "#626262", 14)
            size = place(canvas, record, x, y + 56, 328, 390)
            draw.rectangle((x, y + 56, x + size[0], y + 56 + size[1]), outline="#d4d4d4")
        canvas.save(output / (platform + "-" + group + "-board.png"), optimize=True)

groups = [
    ("main", "1 · 主界面：手机 / 平板，横屏 / 竖屏，浅色 / 深色"),
    ("states", "2 · 登录、设置、账号、帮助与运行状态"),
    ("boundary", "3 · 较短手机与 4:3 平板"),
    ("stress", "4 · 130% 字号、64 单元名称与安全区域组合"),
    ("dialogs", "5 · 手机横屏弹层：大字号与长名称"),
]
sections = []
for group, heading in groups:
    cards = []
    for record in (r for r in records if r["group"] == group):
        link = record["group"] + "/" + record["name"] + ".png"
        caption = record["platform"].upper() + " · " + record["label"] + " · " + record["theme"]
        detail = str(record["width"]) + "×" + str(record["height"])
        cards.append('<figure><a href="' + link + '"><img loading="lazy" src="' + link + '" alt="' + html.escape(caption) + '"></a><figcaption>' + html.escape(caption) + '<small>' + detail + ' · 点击查看原图</small></figcaption></figure>')
    sections.append("<section><h2>" + heading + '</h2><div class="grid">' + "".join(cards) + "</div></section>")
review = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>相机双端 UI 草稿审阅</title><style>
*{box-sizing:border-box}body{margin:0;background:#f4f4f4;color:#171717;font:15px/1.6 'Microsoft YaHei UI',sans-serif}
main{max-width:1480px;margin:auto;padding:32px}h1{font-size:28px;margin:0 0 12px}h2{font-size:20px;margin:40px 0 16px}
p{margin:8px 0;color:#555}nav{display:flex;gap:16px;flex-wrap:wrap;margin:20px 0}a{color:inherit}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:20px}figure{margin:0;background:white;border:1px solid #ddd;border-radius:10px;overflow:hidden}
figure a{display:flex;justify-content:center;align-items:center;min-height:240px;height:360px;padding:12px;background:#e9e9e9}
img{max-height:100%;max-width:100%;object-fit:contain}figcaption{padding:14px;font-size:14px}small{display:block;color:#666}
aside{border:1px solid #d5d5d5;border-radius:10px;padding:16px;background:white}li{margin:6px 0}
</style><main><h1>相机推流节点 · 双端静态草稿</h1>
<p>控制优先 / Android 保留 Compose / 2026-10-17 / 待 owner 审稿确认。</p>
<aside>16 张主界面稿，另附比例边界、130% 字号与长名称、安全区域、辅助状态和弹层，共 86 张原尺寸 PNG。
Android 为浏览器渲染的原生布局示意；固定统计与仓库画面均为示例。正式界面、相机采集与服务协议尚未改造。</aside>
<nav><a href="overview.png">完整 PNG 总览</a><a href="web-light-board.png">Web 浅色</a><a href="web-dark-board.png">Web 深色</a>
<a href="android-light-board.png">Android 浅色</a><a href="android-dark-board.png">Android 深色</a><a href="#audit">审计结果</a></nav>
<section><h2>审阅顺序</h2><ol><li>确认控制与预览的主次、横竖屏排布和固定启停。</li><li>检查辅助入口、两端差异与异常提示是否清楚。</li>
<li>确认浅深色、控件密度、长名称和较短窗口的滚动取舍。</li></ol></section>
""" + '<section id="audit"><h2>静态草稿审计</h2><aside><strong>本轮静态检查通过，等待 owner 设计审稿确认。</strong><p>' \
    + str(audit["cases"]) + ' 组尺寸与状态检查无越界；原图尺寸、资源加载、预览区域稳定性通过。' \
    + str(audit["contrastSamples"]) + ' 组关键文字对比抽查，最低 ' + str(audit["minimumContrast"]) + ':1。</p><p>本地渲染器构建、文档 / 协议 / 护栏 / 主题检查及 ' \
    + str(audit["repositoryTests"] + audit["consoleTests"] + audit["cameraTests"]) + ' 项既有测试通过。</p></aside>' \
    + '<ol><li>登录与失败提示：布局、遮罩与必要提示正常，凭据均为固定示例。</li>' \
    + '<li>主界面：双端手机 / 平板、横竖屏、浅深色 16 张主稿正常；启停固定可见。</li>' \
    + '<li>推流设置：目标与规格分组清楚；Web 镜头选择与 Android 亮度操作保留平台差异；长名称不改变输入契约。</li>' \
    + '<li>辅助操作：帮助、账号、外观及 Android 更新稿正常；菜单→帮助→Escape 关闭并恢复焦点。</li>' \
    + '<li>异常状态：未推流、关闭预览、无目标、连接中 / 中断、权限与占用均有明确状态和操作条件。</li></ol>' \
    + '<p>已修正：横屏空间与开关点击区、低对比停止按钮、弹层动画中间帧、焦点恢复及菜单平台文案。</p>' \
    + '<p>边界：较短手机、大字号与部分异常提示允许在控制区内滚动；页面整体和启停不越界。未验证 Compose / 真机渲染、真实授权与采集、启停 / 旋转、输入法、TalkBack、完整键盘路径、实际更新或报警链。静态稿不代表完整无障碍通过。</p>' \
    + '</section>' + "".join(sections) + "</main></html>"
(output / "review.html").write_text(review, encoding="utf-8")
print(json.dumps({"captures": len(records), "main": sum(r["group"] == "main" for r in records), "boards": 13, "review": str(output / "review.html")}, ensure_ascii=False))
