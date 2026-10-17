export const profiles = [
  { id: 'phone-portrait', label: '手机竖屏', width: 360, height: 800 },
  { id: 'phone-landscape', label: '手机横屏', width: 800, height: 360 },
  { id: 'tablet-portrait', label: '平板竖屏', width: 800, height: 1280 },
  { id: 'tablet-landscape', label: '平板横屏', width: 1280, height: 800 },
];
export const boundaries = [
  { id: 'short-phone-portrait', label: '较短手机竖屏', width: 360, height: 640 },
  { id: 'short-phone-landscape', label: '较短手机横屏', width: 640, height: 360 },
  { id: 'tablet-4x3-portrait', label: '4:3 平板竖屏', width: 768, height: 1024 },
  { id: 'tablet-4x3-landscape', label: '4:3 平板横屏', width: 1024, height: 768 },
];
export const scenes = [
  ['login', '登录'], ['login-error', '登录错误'], ['stopped', '未推流'],
  ['preview-off', '关闭预览仍推流'], ['empty', '无目标'], ['connecting', '连接中'],
  ['offline', '连接中断'], ['permission', '相机未授权'], ['occupied', '摄像头被占用'],
  ['menu', '本机操作'], ['targets', '目标选择'], ['help', '相机帮助'],
  ['account', '账号与本机'], ['appearance', '外观与设置'],
];
export const androidScenes = [
  ['rename', '修改本机名称'], ['account-security', '账号安全'],
  ['update', '检查更新'], ['update-download', '更新下载中'],
];
export const jobs = [];
for (const platform of ['web', 'android']) {
  for (const theme of ['light', 'dark']) {
    for (const profile of profiles) jobs.push({
      ...profile, platform, theme, scene: 'streaming', group: 'main',
      name: platform + '-' + profile.id + '-' + theme,
    });
    for (const profile of boundaries) jobs.push({
      ...profile, platform, theme, scene: 'streaming', group: 'boundary',
      name: platform + '-' + profile.id + '-' + theme,
    });
    for (const profile of profiles) jobs.push({
      ...profile, platform, theme, scene: 'streaming', group: 'stress',
      extra: '&text=large&names=long&safe=1',
      name: platform + '-' + profile.id + '-' + theme + '-large-long-safe',
    });
  }
  for (const [index, [scene, label]] of [...scenes, ...(platform === 'android' ? androidScenes : [])].entries()) jobs.push({
    ...profiles[0], platform, theme: index % 2 === 0 ? 'light' : 'dark', scene, label, group: 'states',
    name: platform + '-' + scene,
  });
  for (const scene of ['targets', 'help', 'appearance']) jobs.push({
    ...profiles[1], platform, theme: 'dark', scene, label: '横屏弹层', group: 'dialogs',
    extra: '&names=long&text=large&safe=1', name: platform + '-' + scene + '-landscape-large',
  });
}
