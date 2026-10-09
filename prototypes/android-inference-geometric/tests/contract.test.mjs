import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import test from "node:test";
import { COCO } from "../src/coco.mjs";
import { CATALOGUE, ZONES } from "../src/catalogue.mjs";
import {
  LIMITS,
  MODEL_KEYS,
  VERSION,
  applyScenario,
  bounded,
  coldError,
  createSeed,
  normalizeMask,
  statusText,
  validateMask,
  validateName,
  validateParams,
} from "../src/domain.mjs";

/* 契约来源：detector/android-inference 的 DetectionParams / ModelStore / SettingsStore。
   原型只模拟交互，字段边界必须与承接的 Android 实现一致，不靠截断掩盖非法输入。 */

const root = new URL("../", import.meta.url);
const params = overrides => ({ confidence: 65, fps: 3, cooldown: 30, model: "yolo26n_320", targets: ["person"], ...overrides });
const models = () => createSeed().models;

test("参数边界与承接实现一致", () => {
  assert.deepEqual(LIMITS.confidence, [10, 95]);
  assert.deepEqual(LIMITS.fps, [1, 5]);
  assert.deepEqual(LIMITS.cooldown, [1, 300]);
  assert.equal(LIMITS.name, 64);
  assert.equal(LIMITS.sources, 16);
});

test("合法边界通过参数校验", () => {
  const available = models();
  assert.equal(validateParams(params(), available), null);
  assert.equal(validateParams(params({ confidence: 10 }), available), null);
  assert.equal(validateParams(params({ confidence: 95 }), available), null);
  assert.equal(validateParams(params({ fps: 1 }), available), null);
  assert.equal(validateParams(params({ fps: 5 }), available), null);
  assert.equal(validateParams(params({ cooldown: 1 }), available), null);
  assert.equal(validateParams(params({ cooldown: 300 }), available), null);
});

test("越界与非整数参数被拒绝，而不是截断后保存", () => {
  const available = models();
  for (const bad of [
    { confidence: 9 },
    { confidence: 96 },
    { confidence: 65.5 },
    { fps: 0 },
    { fps: 6 },
    { fps: 2.5 },
    { cooldown: 0 },
    { cooldown: 301 },
    { cooldown: 30.5 },
  ]) {
    assert.ok(validateParams(params(bad), available), `${JSON.stringify(bad)} 应被拒绝`);
  }
  assert.equal(validateParams(params({ confidence: NaN }), available), "置信度需为 10–95 的整数");
  assert.match(validateParams(params({ fps: 6 }), available), /1–5/);
  assert.match(validateParams(params({ cooldown: 0 }), available), /1–300/);
});

test("模型必须已下载，目标集合遵守契约上限", () => {
  const available = models();
  assert.equal(available.find(m => m.key === "yolo26s_640").downloaded, false);
  assert.equal(validateParams(params({ model: "yolo26s_640" }), available), "当前模型未下载");
  assert.equal(validateParams(params({ targets: [] }), available), "至少选择一个检测目标");
  assert.equal(validateParams(params({ targets: ["person", "不存在的目标"] }), available), "存在不支持的检测目标");
  assert.equal(validateParams(params({ targets: new Array(257).fill("person") }), available), "目标集合超出契约上限");
  assert.equal(params({ targets: new Array(256).fill("person") }).targets.join(",").length, 1791);
  assert.equal(validateParams(params({ targets: new Array(256).fill("person") }), available), null);
  assert.equal(validateParams(params({ targets: new Array(600).fill("person") }), available), "目标集合超出契约上限");
});

test("模型清单覆盖六个 YOLO26 变体", () => {
  assert.deepEqual(models().map(m => m.key), MODEL_KEYS);
  assert.equal(MODEL_KEYS.length, 6);
});

test("COCO 目标使用协议英文键，可被中文检索", () => {
  assert.equal(COCO.length, 80);
  assert.equal(new Set(COCO.map(c => c.key)).size, 80);
  assert.deepEqual(COCO[0], { key: "person", name: "人" });
  assert.ok(COCO.some(c => c.name.includes("卡车")));
});

test("名称边界：非空、64 个 UTF-16 单元、无控制字符", () => {
  assert.equal(validateName("仓库入口"), null);
  assert.equal(validateName("a".repeat(64)), null);
  assert.equal(validateName("几何空间".repeat(16)), null);
  assert.equal(validateName("   "), "名称不能为空");
  assert.equal(validateName("a".repeat(65)), "最多 64 个 UTF-16 单元");
  assert.equal(validateName("走廊\u0007"), "名称不能包含控制字符");
  assert.equal(validateName("几何空间".repeat(17)), "最多 64 个 UTF-16 单元");
});

test("遮罩为 [0,1] 相对坐标且最小边长 .02", () => {
  assert.equal(validateMask({ x: 0, y: 0, width: .02, height: .02 }), true);
  assert.equal(validateMask({ x: .98, y: .98, width: .02, height: .02 }), true);
  assert.equal(validateMask({ x: .9, y: .5, width: .2, height: .2 }), false);
  assert.equal(validateMask({ x: .5, y: .5, width: .01, height: .2 }), false);
  assert.equal(validateMask({ x: -0.01, y: .5, width: .2, height: .2 }), false);
  assert.equal(validateMask({ x: .5, y: .5, width: NaN, height: .2 }), false);
  assert.equal(validateMask(normalizeMask(.9, .9, .1, .1)), true);
  assert.equal(validateMask(normalizeMask(.99, .99, 1.2, 1.2)), true);
});

test("冷改动闸门：运行或忙碌的来源拒绝参数、镜头与遮罩修改", () => {
  const seed = createSeed();
  assert.equal(coldError(seed.sources[0]), "请先停止该来源；参数、镜头和遮罩是冷改动");
  assert.equal(coldError({ running: false, busy: true }), "请先停止该来源；参数、镜头和遮罩是冷改动");
  assert.equal(coldError({ running: false, busy: false }), null);
  assert.equal(coldError(undefined), null);
});

test("来源状态文字覆盖运行、忙碌、错误与未就绪", () => {
  assert.equal(statusText({ running: true, ready: true }), "推理中");
  assert.equal(statusText({ running: false, ready: true }), "已停止");
  assert.equal(statusText({ running: false, ready: false }), "等待绑定");
  assert.equal(statusText({ running: true, busy: true }), "处理中…");
  assert.equal(statusText({ running: true, error: "会话创建失败" }), "会话创建失败");
});

test("预览场景保持来源互相独立", () => {
  const normal = applyScenario("normal");
  assert.equal(normal.sources.length, 3);
  assert.equal(normal.sources[0].running, true);
  assert.equal(normal.sources[1].running, false);

  const stopped = applyScenario("stopped");
  assert.equal(stopped.sources.every(s => !s.running), true);

  const offline = applyScenario("offline");
  assert.equal(offline.sources.filter(s => s.type === "remote").every(s => s.error === "连接中断"), true);
  assert.equal(offline.sources[0].error, null);

  const unready = applyScenario("unready");
  assert.equal(unready.sources[0].ready, false);
  assert.equal(unready.sources[1].ready, true);

  const empty = applyScenario("empty");
  assert.deepEqual(empty.sources, []);

  const long = applyScenario("long");
  assert.equal(long.sources[0].name.length, 64);

  const failed = applyScenario("failed");
  assert.equal(failed.sources[0].error, "模型会话创建失败");
  assert.equal(failed.sources[0].backend, "CPU");
});

test("滚筒吸附把位置限制在合法档位", () => {
  assert.equal(bounded(-3, 1, 5), 1);
  assert.equal(bounded(9, 1, 5), 5);
  assert.equal(bounded(2.4, 1, 5), 2);
  assert.equal(bounded(2.6, 1, 5), 3);
});

test("控件清单完整覆盖 32 项并挂到落地分区", () => {
  assert.equal(CATALOGUE.length, 32);
  assert.equal(new Set(CATALOGUE.map(c => c.id)).size, 32);
  const zones = Object.keys(ZONES);
  assert.deepEqual(zones, ["main", "params", "sources", "settings", "motion"]);
  const used = new Set();
  for (const c of CATALOGUE) {
    assert.match(c.id, /^C\d{2}$/);
    for (const field of ["name", "metaphor", "contract", "interaction", "coverage"]) {
      assert.ok(c[field] && c[field].trim().length > 0, `${c.id}.${field} 不能为空`);
    }
    assert.ok(zones.includes(c.zone), `${c.id} 指向未知分区 ${c.zone}`);
    used.add(c.zone);
  }
  assert.equal(used.size, 5, "五个分区都要有控件覆盖");
  for (const zone of Object.values(ZONES)) {
    assert.ok(zone.label.trim().length > 0 && zone.where.trim().length > 0);
  }
});

test("概念素材已丢弃，只保留落地资产", async () => {
  await assert.rejects(access(new URL("public/concepts", root)), "概念图目录不应再存在");
  const catalogue = await readFile(new URL("src/catalogue.mjs", root), "utf8");
  assert.ok(!/\.png/.test(catalogue), "控件清单不应再引用概念图文件");
  assert.ok(!/Concepts/.test(await readFile(new URL("src/DesignLab.jsx", root), "utf8")), "概念图画廊组件应已删除");
  await access(new URL("public/assets/brand.png", root));
  await access(new URL("public/assets/camera-frame.png", root));
});

test("规范数字与实际样式一致", async () => {
  const css = await readFile(new URL("src/styles.css", root), "utf8");
  const spec = [
    [".drawer--wide{width:min(760px,100vw)}", "参数 / 遮罩抽屉宽度 760"],
    [".drawer{width:min(560px,100vw)", "普通抽屉宽度 560"],
    [".mask-canvas-frame{--mask-handle:48px;--mask-handle-offset:-25px;padding:26px", "遮罩内衬 26 与把手 48"],
    [".parameter-strip>span,.parameter-strip>button{padding:8px 12px;white-space:nowrap;border-right:1px solid var(--border);min-height:48px", "底部参数条触点 48"],
    [".wheel{width:112px;height:220px", "采样滚筒 112 × 220"],
    [".wheel-assembly--compact .wheel{width:60px;height:146px}", "冷却滚筒每位 60 × 146"],
    [".main-paddle{flex:1;font-size:30px;min-width:0;min-height:80px", "启停压板高 80"],
    [".square-key{width:80px;min-height:80px", "参数 / 遮罩方键 80"],
    [".source-stop{display:flex;align-items:center;gap:12px;width:100%;border-bottom:1px solid var(--border);border-left:3px solid transparent;min-height:94px", "来源行高 94 与左缘 3px"],
    [".mask-plane{position:relative;aspect-ratio:1672/941;width:100%;background:#202020;touch-action:none;overflow:hidden", "遮罩画布溢出裁剪"],
    [".tablet-frame{background:var(--page);width:100%;max-width:1280px;aspect-ratio:16/10;padding:20px 24px;display:flex;flex-direction:column;border:1px solid var(--border);min-height:700px", "平板帧 1280×800、内高下限 700"],
  ];
  for (const [needle, label] of spec) {
    assert.ok(css.includes(needle), `样式与规范不一致：${label}`);
  }
  assert.ok(!css.includes("920px"), "旧的 920px 抽屉宽度应已收回");
  assert.ok(!css.includes("min-height:560px"), "平板帧内高下限不应退回 560");
  assert.ok(!css.includes("concept-gallery"), "概念图画廊样式应已删除");
});

test("遮罩把手贴边时内移，四角都有规则", async () => {
  const css = await readFile(new URL("src/styles.css", root), "utf8");
  for (const edge of ["l", "r", "t", "b"]) {
    assert.ok(css.includes(`data-edge~="${edge}"`), `缺少 ${edge} 边内移规则`);
  }
  const editor = await readFile(new URL("src/MaskEditor.jsx", root), "utf8");
  assert.ok(editor.includes("data-edge="), "遮罩把手应带上贴边标记");
  assert.ok(editor.includes('className="mask-canvas-frame"'), "遮罩画布应带内衬容器");
});

test("原型只使用项目主题色与 Lucide 资源", async () => {
  const css = await readFile(new URL("src/styles.css", root), "utf8");
  for (const token of ["#087F83", "#4FBCC1", "#F5F5F5", "#121212", "#202020", "#F2F2F2"]) {
    assert.ok(css.includes(token), `样式缺少项目主题令牌 ${token}`);
  }
  const pkg = JSON.parse(await readFile(new URL("package.json", root), "utf8"));
  assert.equal(pkg.dependencies["lucide-react"], "1.49.0");
  assert.equal(pkg.private, true);
  assert.equal(pkg.scripts.test, "node --test tests/contract.test.mjs");
});

test("原型声明版本与当前源码版本一致", async () => {
  const version = (await readFile(new URL("../../VERSION", root), "utf8")).trim();
  assert.equal(VERSION, version);
});

test("模拟边界：不出现生产地址或凭据持久化", async () => {
  const files = ["src/App.jsx", "src/Settings.jsx", "src/domain.mjs"];
  for (const file of files) {
    const text = await readFile(new URL(file, root), "utf8");
    const hosts = [...text.matchAll(/https?:\/\/([^"'\s`]+)/g)].map(m => m[1]);
    for (const host of hosts) {
      assert.ok(/^(?:example\.|preview\.invalid)/.test(host), `${file} 内嵌了非隔离地址 ${host}`);
    }
    assert.ok(!/localStorage\.setItem\(['"](?:vg-geometric-)?(?:password|token)/i.test(text), `${file} 不应持久化凭据`);
  }
});
