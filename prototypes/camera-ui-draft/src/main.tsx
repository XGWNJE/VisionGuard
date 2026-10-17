import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { createRoot } from 'react-dom/client';
import {
  Camera, CircleHelp, SunMoon, UserRound, ChevronDown, RefreshCw, Play, Square,
  Video, EyeOff, CameraOff, WifiOff, Check, Monitor, Moon, Sun, LogOut,
  Pencil, KeyRound, Download, CircleCheck, CircleAlert, LoaderCircle, MoreHorizontal,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardHeader, CardTitle, CardAction, CardContent, CardFooter, CardDescription } from '@/components/ui/card';
import { Field, FieldGroup, FieldLabel, FieldContent } from '@/components/ui/field';
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select';
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group';
import { Input } from '@/components/ui/input';
import { Checkbox } from '@/components/ui/checkbox';
import { Separator } from '@/components/ui/separator';
import { Alert, AlertTitle, AlertDescription } from '@/components/ui/alert';
import { Empty, EmptyHeader, EmptyMedia, EmptyTitle, EmptyDescription } from '@/components/ui/empty';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter, DialogClose } from '@/components/ui/dialog';
import { Switch } from '@draft/components/ui/switch';
import { Progress } from '@draft/components/ui/progress';
import previewImage from '../../android-inference-geometric/public/assets/camera-frame.png';
import versionText from '../../../VERSION?raw';
import '@/style.css';
import './styles.css';

const query = new URLSearchParams(location.search);
const platform = query.get('platform') === 'android' ? 'android' : 'web';
const scene = query.get('scene') || 'streaming';
const dark = query.get('theme') === 'dark';
const largeText = query.get('text') === 'large';
const longNames = query.get('names') === 'long';
const safeArea = query.get('safe') === '1';
const version = versionText.trim();
document.documentElement.classList.toggle('dark', dark);
document.documentElement.style.fontSize = largeText ? '20.8px' : '16px';
document.documentElement.dataset.draftPlatform = platform;
document.documentElement.dataset.draftScene = scene;

const deviceName = longNames ? '仓库北侧入口与装卸通道'.repeat(8).slice(0, 64) : '仓库入口';
const targetName = longNames ? '视觉推理节点与北侧仓库装卸通道联合检测'.repeat(4).slice(0, 64) : '仓库视觉节点';
const noop = () => {};
const panels = ['menu', 'targets', 'help', 'account', 'appearance', 'update', 'update-download', 'rename', 'account-security'];

type ViewState = {
  running: boolean; online: boolean; preview: boolean; targets: boolean;
  status: string; message?: string; destructive?: boolean;
};
const views: Record<string, ViewState> = {
  streaming: { running: true, online: true, preview: true, targets: true, status: '推流中' },
  stopped: { running: false, online: true, preview: true, targets: true, status: '未推流' },
  'preview-off': { running: true, online: true, preview: false, targets: true, status: '推流中' },
  empty: { running: false, online: true, preview: true, targets: false, status: '未关联',
    message: '暂无可用的视觉推理节点', },
  connecting: { running: false, online: false, preview: true, targets: true, status: '正在连接' },
  offline: { running: false, online: false, preview: true, targets: true, status: '连接中断',
    message: '与服务的连接已断开，正在重连。恢复后请重新开始推流。', destructive: true },
  permission: { running: false, online: true, preview: true, targets: true, status: '需要相机权限',
    message: '相机权限未开启，请在本机授权后重试。', destructive: true },
  occupied: { running: false, online: true, preview: true, targets: true, status: '摄像头被占用',
    message: '关闭正在使用摄像头的应用后重试。', destructive: true },
};
const view = views[scene] || (scene === 'targets' ? views.stopped : views.streaming);
const safeStyle = safeArea ? {
  '--safe-top': '24px', '--safe-bottom': '24px', '--safe-left': '16px', '--safe-right': '16px',
} as CSSProperties : undefined;

function StatusBadge() {
  return <Badge variant={view.destructive ? 'outline' : view.running ? 'default' : 'secondary'}>
    {view.destructive ? <CircleAlert /> : view.running ? <Video /> : !view.online ? <WifiOff /> : null}{view.status}
  </Badge>;
}

function Statistics() {
  const known = view.online && view.running;
  return <div className="cam-stats" aria-label="推流统计">
    <div className="cam-stat"><div className="cam-stat-label">本机入队</div>
      <div className="cam-stat-value">{known ? '12,864' : view.online ? '0' : '—'}</div></div>
    <div className="cam-stat"><div className="cam-stat-label">服务收帧</div>
      <div className="cam-stat-value">{known ? '12,861' : view.online ? '0' : '—'}</div></div>
    <div className="cam-drops"><span>本机丢弃 {known ? '2' : view.online ? '0' : '—'}</span>
      <span>服务丢弃 {known ? '1' : view.online ? '0' : '—'}</span></div>
  </div>;
}

function CaptureFields({ showTargets }: { showTargets: () => void }) {
  return <FieldGroup className="cam-fields">
    <Field className="cam-field cam-target-field" orientation="horizontal">
      <FieldLabel htmlFor="target">目标</FieldLabel>
      <div className="cam-target-row">
        <Button id="target" variant="outline" className="cam-target" disabled={view.running || !view.online || !view.targets}
          title={view.targets ? targetName : '暂无节点'} onClick={showTargets} aria-haspopup="dialog">
          <span className="cam-target-label">{view.targets ? targetName : '暂无节点'}</span><ChevronDown data-icon="inline-end" />
        </Button>
        <Button size="icon-lg" variant="outline" aria-label="刷新目标" disabled={!view.online}>
          <RefreshCw />
        </Button>
      </div>
    </Field>
    <Field className="cam-field" orientation="horizontal">
      <FieldLabel id="resolution-label">规格</FieldLabel>
      <ToggleGroup type="single" value="720p" onValueChange={noop} variant="outline" spacing={0}
        aria-labelledby="resolution-label" className="cam-resolution" disabled={view.running}>
        <ToggleGroupItem value="480p">640×480</ToggleGroupItem>
        <ToggleGroupItem value="720p">720P</ToggleGroupItem>
      </ToggleGroup>
    </Field>
    {platform === 'web' && <Field className="cam-field" orientation="horizontal">
      <FieldLabel htmlFor="camera">镜头</FieldLabel>
      <NativeSelect id="camera" className="cam-camera-picker" defaultValue="rear" disabled={view.running}>
        <NativeSelectOption value="rear">后置摄像头</NativeSelectOption>
        <NativeSelectOption value="front">前置摄像头</NativeSelectOption>
      </NativeSelect>
    </Field>}
    {platform === 'android' && <div className="cam-local-options">
      <Field className="cam-option" orientation="horizontal">
        <FieldLabel htmlFor="dim">降低亮度</FieldLabel>
        <Switch id="dim" checked={false} onCheckedChange={noop} />
      </Field>
    </div>}
  </FieldGroup>;
}

function Preview() {
  const visible = view.running && view.preview;
  const PreviewIcon = !view.preview ? EyeOff : scene === 'permission' ? CameraOff : Camera;
  return <Card className="cam-preview" aria-label="本机预览区">
    <CardHeader>
      <CardTitle className="cam-preview-title"><Camera />本机预览</CardTitle>
      <CardAction>
        <Field className="cam-preview-toggle" orientation="horizontal">
          <Switch id="show-preview" aria-label="本机预览开关" checked={view.preview} onCheckedChange={noop} />
        </Field>
      </CardAction>
    </CardHeader>
    <div className="cam-stage">
      {visible ? <img src={previewImage} alt="仓库通道中的示例相机画面" /> :
        <Empty className="cam-placeholder"><EmptyHeader>
          <EmptyMedia variant="icon"><PreviewIcon /></EmptyMedia>
          <EmptyTitle>{!view.preview ? '预览已关闭' : scene === 'permission' ? '等待相机授权' : '暂无画面'}</EmptyTitle>
          <EmptyDescription>{!view.preview ? '推流仍在继续' : view.targets ? '开始推流后显示画面' : '关联视觉推理节点后开始'}</EmptyDescription>
        </EmptyHeader></Empty>}
    </div>
    <div className="cam-preview-footer"><span>{visible ? '发送 1280×720' : '本机画面'}</span><span>{visible ? '0°' : '等比预览'}</span></div>
  </Card>;
}

function HelpContent() {
  return <div className="cam-dialog-copy">
    <section><h3>开始与停止</h3><p>关联同账号的视觉推理节点后开始推流。推流期间不能切换目标、规格{platform === 'web' ? '或镜头' : ''}。</p></section>
    <section><h3>收帧统计</h3><p>本机入队表示发送队列已接纳；服务收帧表示中继已收到，不代表完成推理。本机丢弃与服务丢弃分别统计。</p></section>
    <section><h3>前台与画面</h3><p>横竖屏切换保留推流。{platform === 'web' ? '切换标签页、' : '切到后台或'}锁屏会停止推流，返回后手动开始。关闭本机预览不停止推流。</p></section>
    {platform === 'web' && <section><h3>浏览器权限</h3><p>需要 HTTPS 或本机 localhost，并在本机允许摄像头权限。亮度由系统控制。</p></section>}
    <Separator />
    <div className="cam-data-list">
      <div className="cam-data-row"><span>关联目标</span><strong>{targetName}</strong></div>
      <div className="cam-data-row"><span>采集 / 发送</span><strong>1280×720 / 1280×720</strong></div>
      <div className="cam-data-row"><span>主动未采样</span><strong>38,592</strong></div>
      <div className="cam-data-row"><span>媒体会话</span><strong>1</strong></div>
    </div>
  </div>;
}

function DraftPanel({ panel, setPanel }: { panel: string | null; setPanel: (value: string | null) => void }) {
  const previousFocus = useRef<HTMLElement | null>(null);
  const titles: Record<string, string> = {
    menu: '本机操作', targets: '选择视觉推理节点', help: '相机帮助', account: '账号与本机',
    appearance: '外观与设置', update: '应用更新', 'update-download': '下载更新',
    rename: '修改本机名称', 'account-security': '修改密码',
  };
  return <Dialog open={panel !== null} onOpenChange={open => { if (!open) setPanel(null); }}>
    <DialogContent className="cam-dialog" style={safeStyle}
      onOpenAutoFocus={() => { previousFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null; }}
      onCloseAutoFocus={event => { event.preventDefault(); previousFocus.current?.focus({ preventScroll: true }); }}>
      <DialogHeader>
        <DialogTitle>{titles[panel || ''] || '相机设置'}</DialogTitle>
        <DialogDescription className="cam-dialog-description">
          {panel === 'targets' ? '同账号内可关联的视觉推理节点' :
            panel === 'help' ? '推流、画面与收帧状态' :
            panel === 'appearance' ? '仅作用于本机界面' :
            panel === 'account' ? '本机身份与登录账号' :
            panel === 'rename' ? '最多 64 个 UTF-16 单元' :
            panel === 'account-security' ? '修改后重新登录' : '相机推流节点 · ' + (platform === 'android' ? 'Android' : 'Web')}
        </DialogDescription>
      </DialogHeader>
      {panel === 'menu' && <div className="cam-dialog-options">
        <Button variant="outline" onClick={() => setPanel('help')}><CircleHelp data-icon="inline-start" />相机帮助</Button>
        <Button variant="outline" onClick={() => setPanel('appearance')}><SunMoon data-icon="inline-start" />外观与设置</Button>
        <Button variant="outline" onClick={() => setPanel('account')}><UserRound data-icon="inline-start" />账号与本机</Button>
      </div>}
      {panel === 'targets' && <ToggleGroup type="single" value="warehouse" onValueChange={noop}
        orientation="vertical" variant="outline" className="cam-target-list" aria-label="视觉推理节点">
        <ToggleGroupItem value="warehouse"><span>{targetName}<span className="cam-target-detail">在线 · Windows</span></span><Check /></ToggleGroupItem>
        <ToggleGroupItem value="tablet"><span>值班室平板<span className="cam-target-detail">在线 · Android</span></span></ToggleGroupItem>
      </ToggleGroup>}
      {panel === 'help' && <HelpContent />}
      {panel === 'account' && <>
        <div className="cam-data-list">
          <div className="cam-data-row"><span>账号</span><strong>camera-demo</strong></div>
          <div className="cam-data-row"><span>本机名称</span><strong>{deviceName}</strong></div>
          <div className="cam-data-row"><span>平台</span><strong>{platform === 'android' ? 'Android' : 'Web 浏览器'}</strong></div>
        </div>
        <Separator />
        <div className="cam-dialog-options">
          {platform === 'android' && <>
            <Button variant="outline" onClick={() => setPanel('rename')}><Pencil data-icon="inline-start" />修改本机名称</Button>
            <Button variant="outline" onClick={() => setPanel('account-security')}><KeyRound data-icon="inline-start" />修改密码</Button>
          </>}
          <Button variant="outline"><LogOut data-icon="inline-start" />退出登录</Button>
        </div>
      </>}
      {panel === 'appearance' && <>
        <FieldGroup><Field><FieldLabel id="theme-label">界面主题</FieldLabel>
          <ToggleGroup className="cam-appearance-options" type="single" value={dark ? 'dark' : 'light'}
            onValueChange={noop} variant="outline" aria-labelledby="theme-label">
            <ToggleGroupItem value="system"><Monitor data-icon="inline-start" />系统</ToggleGroupItem>
            <ToggleGroupItem value="light"><Sun data-icon="inline-start" />浅色</ToggleGroupItem>
            <ToggleGroupItem value="dark"><Moon data-icon="inline-start" />深色</ToggleGroupItem>
          </ToggleGroup></Field></FieldGroup>
        {platform === 'android' && <>
          <Separator /><div className="cam-data-row"><span>当前版本</span><strong>{version}</strong></div>
          <Button variant="outline" onClick={() => setPanel('update')}><Download data-icon="inline-start" />检查更新</Button>
        </>}
      </>}
      {(panel === 'update' || panel === 'update-download') && <>
        <div className="cam-data-row"><span>当前版本</span><strong>{version}</strong></div>
        {panel === 'update' ? <>
          <Alert><CircleCheck /><AlertTitle>已是最新版本</AlertTitle><AlertDescription>最近检查：刚刚</AlertDescription></Alert>
          <Button variant="outline"><RefreshCw data-icon="inline-start" />重新检查</Button>
        </> : <>
          <Progress value={52} aria-label="更新下载进度" aria-valuenow={52} /><div className="cam-progress-row"><span>6.2 / 12.0 MB</span><span>52%</span></div>
          <Button variant="outline">取消下载</Button>
        </>}
      </>}
      {panel === 'rename' && <FieldGroup><Field><FieldLabel htmlFor="new-name">设备名称</FieldLabel>
        <Input id="new-name" defaultValue={deviceName} maxLength={64} /></Field></FieldGroup>}
      {panel === 'account-security' && <FieldGroup>
        <Field><FieldLabel htmlFor="old-password">当前密码</FieldLabel><Input id="old-password" type="password" autoComplete="current-password" /></Field>
        <Field><FieldLabel htmlFor="new-password">新密码</FieldLabel><Input id="new-password" type="password" autoComplete="new-password" /></Field>
      </FieldGroup>}
      <DialogFooter>
        <DialogClose asChild><Button variant="outline">返回</Button></DialogClose>
        {panel === 'targets' && <Button>关联此节点</Button>}
        {(panel === 'rename' || panel === 'account-security') && <Button>保存</Button>}
      </DialogFooter>
    </DialogContent>
  </Dialog>;
}

function Login() {
  return <main className="cam-login" style={safeStyle}>
    <Card className="cam-login-card"><CardHeader>
      <div className="cam-login-heading"><div className="cam-brand-icon"><Camera /></div><h1>相机推流节点</h1></div>
      <CardDescription>{platform === 'android' ? 'Android' : 'Web'} · 使用同一账号关联视觉推理节点</CardDescription>
    </CardHeader>
      <CardContent><FieldGroup>
        <Field><FieldLabel htmlFor="username">账号</FieldLabel><Input id="username" defaultValue="camera-demo" autoComplete="username" readOnly /></Field>
        <Field><FieldLabel htmlFor="password">密码</FieldLabel><Input id="password" type="password" defaultValue="draft-only" autoComplete="current-password" readOnly /></Field>
        <Field orientation="horizontal" className="cam-login-remember"><Checkbox id="remember" defaultChecked />
          <FieldContent><FieldLabel htmlFor="remember">{platform === 'android' ? '记住账号密码' : '记住账号'}</FieldLabel></FieldContent></Field>
        {scene === 'login-error' && <Alert><CircleAlert /><AlertTitle>登录失败</AlertTitle><AlertDescription>请检查账号和密码后重试。</AlertDescription></Alert>}
      </FieldGroup></CardContent>
      <CardFooter className="cam-login-footer"><Button>登录<Play data-icon="inline-end" /></Button>
        <span className="cam-version">VisionGuard {version}</span></CardFooter>
    </Card>
  </main>;
}

function App() {
  const [panel, setPanel] = useState<string | null>(panels.includes(scene) ? scene : null);
  useEffect(() => {
    let active = true;
    const images = Array.from(document.images, image => image.decode().catch(noop));
    void Promise.all([document.fonts.ready, ...images]).then(() => {
      requestAnimationFrame(() => requestAnimationFrame(() => {
        if (active) document.documentElement.dataset.captureReady = 'true';
      }));
    });
    return () => { active = false; };
  }, []);
  if (scene === 'login' || scene === 'login-error') return <Login />;
  return <main className="cam-shell" style={safeStyle} aria-label={platform === 'android' ? 'Android 相机设计稿' : 'Web 相机设计稿'}>
    <header className="cam-header">
      <div className="cam-brand"><div className="cam-brand-icon"><Camera /></div>
        <div className="min-w-0"><h1>相机推流节点</h1><span className="cam-identity" title={deviceName}>{deviceName} · {platform === 'android' ? 'Android' : 'Web'}</span></div>
      </div>
      <nav className="cam-top-actions" aria-label="本机操作">
        <Button size="icon-lg" variant="ghost" aria-label="相机帮助" onClick={() => setPanel('help')}><CircleHelp /></Button>
        <Button size="icon-lg" variant="ghost" aria-label="外观与设置" onClick={() => setPanel('appearance')}><SunMoon /></Button>
        <Button size="icon-lg" variant="ghost" aria-label="账号与本机" onClick={() => setPanel('account')}><UserRound /></Button>
      </nav>
      <Button size="icon-lg" variant="ghost" className="cam-mobile-menu" aria-label="更多操作" onClick={() => setPanel('menu')}><MoreHorizontal /></Button>
    </header>
    <div className="cam-workspace">
      <Card className="cam-control" aria-label="控制和状态区">
        <CardHeader><CardTitle>推流控制</CardTitle><CardAction><StatusBadge /></CardAction></CardHeader>
        <CardContent className="cam-control-scroll" tabIndex={0} aria-label="推流设置与统计">
          <div className="cam-control-body">
            {view.message && <Alert className="cam-alert">
              <CircleAlert /><AlertTitle>{view.status}</AlertTitle><AlertDescription>{view.message}</AlertDescription>
            </Alert>}
            <Statistics /><Separator /><CaptureFields showTargets={() => setPanel('targets')} />
          </div>
        </CardContent>
        <CardFooter className="cam-action">
          <Button disabled={!view.online || !view.targets}
            aria-label={view.running ? '停止推流' : '开始推流'}>
            {view.running ? <Square data-icon="inline-start" /> : scene === 'connecting' ? <LoaderCircle data-icon="inline-start" /> : <Play data-icon="inline-start" />}
            {view.running ? '停止推流' : '开始推流'}
          </Button>
        </CardFooter>
      </Card>
      <Preview />
    </div>
    <DraftPanel panel={panel} setPanel={setPanel} />
  </main>;
}

const root = createRoot(document.getElementById('root')!);
root.render(<App />);
if (import.meta.hot) import.meta.hot.dispose(() => root.unmount());
