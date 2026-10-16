// Checks existing client palette regressions and the official console theme with
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
// Existing-client regression fixture; independent of design documentation.
const baseline = new Map([
  ["页面",[["F5F5F5"],["121212"]]],
  ["面板",[["FFFFFF"],["1B1B1B"]]],
  ["次级表面",[["EEEEEE"],["282828"]]],
  ["正文",[["202020"],["F2F2F2"]]],
  ["辅助文字",[["626262"],["B3B3B3"]]],
  ["边框",[["DDDDDD"],["3D3D3D"]]],
  ["主色、成功",[["087F83"],["4FBCC1"]]],
  ["主色上的文字",[["FFFFFF"],["062E30"]]],
  ["选中背景",[["EDEDED"],["303030"]]],
  ["选中背景上的文字",[["07666A"],["75D2D6"]]],
  ["警告文字 / 表面",[["805500","FFF1D6"],["F4CC79","3B3020"]]],
  ["错误文字 / 表面",[["B83434","FCEBEC"],["FF9696","442326"]]],
]);
// Other clients retain their released theme during this console-only update.
const css = read('detector/web/src/style.css');
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
    const expected = baseline.get(label)?.[index]?.[0];
    if (!expected) { errors.push(`Palette regression fixture missing ${label}/${theme}`); continue; }
    if (neutralRoles.has(label)) {
      checks++;
      if (new Set(expected.match(/../g)).size !== 1) errors.push(`Palette ${theme} ${label}: neutral gray requires equal RGB channels`);
    }
    const actual = [['Web', web[index]?.[cssKey]], ['Android', android[index][kotlinKey ?? cssKey]], ['Windows', windows[index][wpfKey]]];
    for (const [platform, value] of actual) {
      checks++;
      if (value !== expected) errors.push(`${platform} ${theme} ${label}: #${value ?? 'missing'} != #${expected}`);
    }
  }
  for (const [label, cssKey, kotlinKey] of [['警告文字 / 表面', 'warning-surface', 'tertiaryContainer'], ['错误文字 / 表面', 'error-surface', 'errorContainer']]) {
    const expected = baseline.get(label)?.[index]?.[1];
    for (const [platform, value] of [['Web', web[index]?.[cssKey]], ['Android', android[index][kotlinKey]]]) {
      checks++;
      if (value !== expected) errors.push(`${platform} ${theme} ${label} surface differs from regression fixture`);
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
for (const file of ['detector/android/app/src/main/java/com/xgwnje/visionguard/detector/ui/theme/Theme.kt', 'notifier/android/app/src/main/java/com/xgwnje/visionguard/notifier/ui/theme/Theme.kt', 'detector/android-inference/app/src/main/java/com/xgwnje/visionguard/inference/ui/theme/Theme.kt']) {
  checks++;
  const source = read(file);
  if (!source.includes('VisionGuardTheme(') || /dynamic(?:Light|Dark)ColorScheme|(?:light|dark)ColorScheme\(/.test(source)) errors.push(`${file}: bypasses shared theme`);
}
checks++;
if (/window\.(?:confirm|alert)\(/.test(read('receiver/web/src/main.tsx'))) errors.push('Web app dialog bypasses theme');
// Web 相机保留当前发行主题；控制台的官方主题在下方单独核对。
const webCameraCss = read('detector/web/src/style.css');
checks++;
if (/window\.(?:confirm|alert)\(/.test(read('detector/web/src/main.tsx'))) errors.push('Web camera dialog bypasses theme');
for (const token of ['--panel', '--accent', '--on-accent', '--error', '--on-error', '--selected', '--on-selected']) {
  checks++;
  if ((webCameraCss.match(new RegExp(`${token}:\\s*#[0-9A-F]{6}`, 'gi')) ?? []).length < 2) errors.push(`Web camera style.css: missing light/dark ${token}`);
}
function tokens(css, selector) {
  const block = css.match(new RegExp(`${selector}\\s*\\{([^}]+)\\}`))?.[1] ?? '';
  return Object.fromEntries([...block.matchAll(/--([\w-]+):\s*([^;]+);/g)].map(m => [m[1], m[2].trim()]));
}
// Convert console OKLCH tokens to sRGB for contrast checks.
function rgb(value) {
  const parts = value?.match(/^oklch\(([\d.]+) ([\d.]+) ([\d.]+)\)$/);
  if (!parts) return undefined;
  const [, L, C, H] = parts.map(Number), a = C * Math.cos(H * Math.PI / 180), b = C * Math.sin(H * Math.PI / 180);
  const l = (L + .3963377774 * a + .2158037573 * b) ** 3;
  const m = (L - .1055613458 * a - .0638541728 * b) ** 3;
  const s = (L - .0894841775 * a - 1.291485548 * b) ** 3;
  return [4.0767416621*l-3.3077115913*m+.2309699292*s, -1.2684380046*l+2.6097574011*m-.3413193965*s, -.0041960863*l-.7034186147*m+1.707614701*s]
    .map(c => Math.round(Math.max(0, Math.min(1, c <= .0031308 ? 12.92*c : 1.055*c**(1/2.4)-.055))*255).toString(16).padStart(2,'0')).join('').toUpperCase();
}

const consoleCss = read('receiver/web/src/style.css');
const config = JSON.parse(read('receiver/web/components.json'));
const dependencies = JSON.parse(read('receiver/web/package.json')).dependencies;
checks++;
if (config.style !== 'radix-nova' || config.tailwind.baseColor !== 'neutral' || !config.tailwind.cssVariables) errors.push('Console must use official Neutral/Radix Nova CSS-variable components');
for (const dependency of ['radix-ui','shadcn','tailwindcss','class-variance-authority']) {
  checks++;
  if (!dependencies[dependency]) errors.push('Console missing official dependency '+dependency);
}
const consoleTokens = [tokens(consoleCss, ':root'),tokens(consoleCss, '\\.dark')];
for (const [index, theme] of ['light','dark'].entries()) {
  const palette = Object.fromEntries(Object.entries(consoleTokens[index]).map(([name,value])=>[name,rgb(value)]));
  for (const [role,expected] of [['background',index?'0A0A0A':'FFFFFF'],['foreground',index?'FAFAFA':'0A0A0A'],['muted-foreground',index?'A1A1A1':'737373']]) {
    checks++;
    if (palette[role] !== expected) errors.push('Console '+theme+' '+role+' differs from official Neutral theme');
  }
  for (const surface of ['background','card']) for (const foreground of ['foreground','muted-foreground','destructive']) checkContrast('Console '+theme+' '+foreground+'/'+surface,palette[foreground],palette[surface]);
  checkContrast('Console '+theme+' foreground/muted',palette.foreground,palette.muted);
  for (const [foreground,background] of [['primary-foreground','primary'],['secondary-foreground','secondary']]) checkContrast('Console '+theme+' '+foreground+'/'+background,palette[foreground],palette[background]);
  const nativeBlock = compose.match(new RegExp(`private val Console${index ? 'Dark' : 'Light'} = [\\s\\S]*?(?=\\nprivate val |$)`))?.[0] ?? '';
  const native = Object.fromEntries([...nativeBlock.matchAll(/\b(\w+)\s*=\s*Color(?:\(0xFF([0-9A-F]{6})\)|\.(White|Black))/gi)].map(m => [m[1], (m[2] || (m[3] === 'White' ? 'FFFFFF' : '000000')).toUpperCase()]));
  for (const [nativeRole, webRole] of [['background','background'],['onBackground','foreground'],['surface','card'],['onSurface','card-foreground'],['primary','primary'],['onPrimary','primary-foreground'],['surfaceVariant','muted'],['onSurfaceVariant','muted-foreground']]) {
    checks++;
    if (native[nativeRole] !== palette[webRole]) errors.push('Notifier '+theme+' '+nativeRole+' differs from console Neutral theme');
  }
  for (const surface of ['background','surface','surfaceVariant']) checkContrast('Notifier '+theme+' onSurface/'+surface,native.onSurface,native[surface]);
  for (const surface of ['background','surface']) checkContrast('Notifier '+theme+' onSurfaceVariant/'+surface,native.onSurfaceVariant,native[surface]);
}
checks++;
if (!read('notifier/android/app/src/main/java/com/xgwnje/visionguard/notifier/ui/theme/Theme.kt').includes('VisionGuardThemeStyle.CONSOLE')) errors.push('Notifier must explicitly select the shared console theme');
const provider = read('receiver/web/src/components/theme-provider.tsx');
checks++;
if (!provider.includes('root.classList.add(') || !provider.includes("'dark'") || !provider.includes('localStorage')) errors.push('Console must use the official class-based Vite theme provider');
for (const [file,marker] of [['button','buttonVariants'],['dialog','DialogPrimitive'],['field','FieldGroup']]) {
  checks++;
  if (!read('receiver/web/src/components/ui/'+file+'.tsx').includes(marker)) errors.push('Console missing official '+file+' implementation');
}
checks++;
if (/@media[^{}]*(?:max-width|orientation\s*:\s*portrait)/.test(consoleCss) || !read('receiver/web/src/main.tsx').includes('collapsible="none"')) errors.push('Console must retain its landscape layout and expanded navigation');

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
