// Checks the actual platform palettes against the shared design spec and calculates
// text contrast. This is a source contract; rendered/device acceptance is separate.
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const errors = [];
// Verify official SVG provenance and every native/generated license artifact.
execFileSync(process.execPath, [path.join(root, 'scripts/generate-lucide-icons.js'), '--check'], { stdio: 'inherit' });
const roles = [
  ['页面', 'page', 'background', 'BackgroundDark'],
  ['面板', 'panel', 'surface', 'SurfaceDark'],
  ['次级表面', 'surface', 'surfaceVariant', 'SurfaceLight'],
  ['正文', 'text', 'onSurface', 'TextPrimary'],
  ['辅助文字', 'muted', 'onSurfaceVariant', 'TextSecondary'],
  ['边框', 'border', 'outlineVariant', 'BorderBrush'],
  ['主色、成功', 'accent', 'primary', 'Primary'],
  ['主色上的文字', 'on-accent', 'onPrimary', 'OnPrimaryBrush'],
  ['选中背景', 'selected', 'primaryContainer', 'SelectedBrush'],
  ['选中背景上的文字', 'on-selected', null, 'SelectedTextBrush'],
  ['警告文字 / 表面', 'warning', 'tertiary', 'WarningBrush'],
  ['错误文字 / 表面', 'error', 'error', 'DangerBrush'],
];
const spec = new Map();
for (const line of read('docs/70-UI设计规范.md').split('\n')) {
  const cells = line.split('|').map(cell => cell.trim());
  if (cells.length < 5) continue;
  const colors = cells.slice(2, 4).map(cell => [...cell.matchAll(/#([0-9A-F]{6})\b/gi)].map(m => m[1].toUpperCase()));
  if (colors.every(list => list.length)) spec.set(cells[1], colors);
}
const css = read('receiver/web/src/style.css');
const web = [...css.matchAll(/:root(?:\[data-theme="dark"\])?\s*\{([^}]+)\}/g)].map(match =>
  Object.fromEntries([...match[1].matchAll(/--([\w-]+):\s*#([0-9A-F]{6})\s*;/gi)].map(m => [m[1], m[2].toUpperCase()])));
const compose = read('android-shared/src/main/java/com/xgwnje/visionguard/account/VisionGuardTheme.kt');
const android = ['Light', 'Dark'].map(name => {
  const block = compose.match(new RegExp(`private val ${name} = [\\s\\S]*?(?=\\nprivate val |$)`))?.[0] ?? '';
  return Object.fromEntries([...block.matchAll(/\b(\w+)\s*=\s*Color(?:\(0xFF([0-9A-F]{6})\)|\.(White|Black))/gi)].map(m => [m[1], (m[2] || (m[3] === 'White' ? 'FFFFFF' : '000000')).toUpperCase()]));
});
const success = compose.match(/val onSuccessContainer[\s\S]*?Color\(0xFF([0-9A-F]{6})\)[\s\S]*?Color\(0xFF([0-9A-F]{6})\)/i);
android[0]['on-selected'] = success?.[2]?.toUpperCase();
android[1]['on-selected'] = success?.[1]?.toUpperCase();
const windowsSource = read('detector/windows-wpf/Themes/ThemeManager.cs');
const dictionaries = [...windowsSource.matchAll(/new Dictionary<string, string>\s*\{([^}]+)\}/g)].map(match =>
  Object.fromEntries([...match[1].matchAll(/\["(\w+)"\]\s*=\s*"#([0-9A-F]{6})"/gi)].map(m => [m[1], m[2].toUpperCase()])));
const windows = [dictionaries[1] ?? {}, dictionaries[0] ?? {}];
let checks = 0;
const neutralRoles = new Set(['页面', '面板', '次级表面', '正文', '辅助文字', '边框', '选中背景']);
for (const [index, theme] of ['light', 'dark'].entries()) {
  if (!web[index]) errors.push(`Web ${theme} root palette missing`);
  for (const [label, cssKey, kotlinKey, wpfKey] of roles) {
    const expected = spec.get(label)?.[index]?.[0];
    if (!expected) { errors.push(`Design specification missing ${label}/${theme}`); continue; }
    if (neutralRoles.has(label)) {
      checks++;
      if (new Set(expected.match(/../g)).size !== 1) errors.push(`Design ${theme} ${label}: neutral gray requires equal RGB channels`);
    }
    const actual = [['Web', web[index]?.[cssKey]], ['Android', android[index][kotlinKey ?? cssKey]], ['Windows', windows[index][wpfKey]]];
    for (const [platform, value] of actual) {
      checks++;
      if (value !== expected) errors.push(`${platform} ${theme} ${label}: #${value ?? 'missing'} != #${expected}`);
    }
  }
  for (const [label, cssKey, kotlinKey] of [['警告文字 / 表面', 'warning-surface', 'tertiaryContainer'], ['错误文字 / 表面', 'error-surface', 'errorContainer']]) {
    const expected = spec.get(label)?.[index]?.[1];
    for (const [platform, value] of [['Web', web[index]?.[cssKey]], ['Android', android[index][kotlinKey]]]) {
      checks++;
      if (value !== expected) errors.push(`${platform} ${theme} ${label} surface differs from specification`);
    }
  }
}
function luminance(hex) {
  if (!/^[0-9A-F]{6}$/i.test(hex ?? '')) return NaN;
  const channels = hex.match(/../g).map(value => parseInt(value, 16) / 255)
    .map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4);
  return channels.reduce((sum, value, i) => sum + value * [.2126, .7152, .0722][i], 0);
}
function checkContrast(label, foreground, background) {
  checks++;
  const a = luminance(foreground), b = luminance(background);
  const ratio = (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
  if (!(ratio >= 4.5)) errors.push(`${label}: text contrast ${ratio.toFixed(2)}:1 < 4.5:1`);
}
for (const [i, theme] of ['light', 'dark'].entries()) {
  const palette = web[i] ?? {};
  for (const surface of ['page', 'panel', 'surface']) {
    for (const foreground of ['text', 'muted', 'on-selected', 'error']) checkContrast(`Web ${theme} ${foreground}/${surface}`, palette[foreground], palette[surface]);
  }
  for (const [foreground, background] of [['on-accent', 'accent'], ['on-error', 'error'], ['on-selected', 'selected'], ['error', 'error-surface'], ['warning', 'warning-surface']]) {
    checkContrast(`Web ${theme} ${foreground}/${background}`, palette[foreground], palette[background]);
  }
  const material = android[i];
  for (const role of ['primary', 'primaryContainer', 'secondary', 'secondaryContainer', 'tertiary', 'tertiaryContainer', 'error', 'errorContainer', 'background', 'surface', 'surfaceVariant', 'inverseSurface']) {
    const onRole = `on${role[0].toUpperCase()}${role.slice(1)}`;
    checkContrast(`Android ${theme} ${onRole}/${role}`, role === 'inverseSurface' ? material.inverseOnSurface : material[onRole], material[role]);
  }
  checkContrast(`Android ${theme} inversePrimary/inverseSurface`, material.inversePrimary, material.inverseSurface);
}
for (const file of ['detector/android/app/src/main/java/com/xgwnje/visionguard/detector/ui/theme/Theme.kt', 'notifier/android/app/src/main/java/com/xgwnje/visionguard/notifier/ui/theme/Theme.kt']) {
  checks++;
  const source = read(file);
  if (!source.includes('VisionGuardTheme(') || /dynamic(?:Light|Dark)ColorScheme|(?:light|dark)ColorScheme\(/.test(source)) errors.push(`${file}: bypasses shared theme`);
}
checks++;
if (/window\.(?:confirm|alert)\(/.test(read('receiver/web/src/main.tsx'))) errors.push('Web app dialog bypasses theme');
for (const [selector, token] of [['.button', 'on-accent'], ['.button.danger', 'on-error'], ['.nav-item.active', 'on-selected'], ['.status-tag.good', 'on-selected'], ['.event-kind', 'error']]) {
  checks++;
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const rule = css.match(new RegExp(`${escaped}\\s*\\{([^}]+)\\}`))?.[1] ?? '';
  if (!rule.includes(`color: var(--${token})`)) errors.push(`Web ${selector}: does not use its contrasting text token`);
}
// New windows and editor tools must not refer to removed or misspelled theme keys.
function xamlFiles(directory) {
  return fs.readdirSync(path.join(root, directory), { withFileTypes: true }).flatMap(entry => {
    const relative = `${directory}/${entry.name}`;
    return entry.isDirectory() ? xamlFiles(relative) : entry.name.endsWith('.xaml') ? [relative] : [];
  });
}
const xaml = ['detector/windows-wpf/App.xaml', ...xamlFiles('detector/windows-wpf/Themes'), ...xamlFiles('detector/windows-wpf/Views')].map(file => [file, read(file)]);
const resourceKeys = new Set(xaml.flatMap(([, source]) => [...source.matchAll(/x:Key="([\w]+)"/g)].map(m => m[1])));
for (const [file, source] of xaml) {
  for (const match of source.matchAll(/\{(?:StaticResource|DynamicResource)\s+([\w]+)\}/g)) {
    checks++;
    if (!resourceKeys.has(match[1])) errors.push(`${file}: missing theme resource ${match[1]}`);
  }
}
if (errors.length) {
  console.error(errors.join('\n'));
  process.exitCode = 1;
} else {
  console.log(`UI theme contract passed: ${checks} palette, text contrast, theme reuse and resource name checks; rendered/device acceptance remains separate.`);
}
