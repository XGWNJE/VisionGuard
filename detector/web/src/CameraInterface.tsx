import { useRef, useState, type FormEvent, type RefObject } from 'react';
import { Camera, CameraOff, Check, ChevronDown, CircleAlert, CircleHelp, EyeOff, LogOut,
  Maximize, Minimize, Monitor, Moon, MoreHorizontal, Play, RefreshCw, Square, Sun, SunMoon, UserRound, Video, WifiOff } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardAction, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card';
import { Field, FieldContent, FieldGroup, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Checkbox } from '@/components/ui/checkbox';
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select';
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group';
import { Switch } from '@/components/ui/switch';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from '@/components/ui/empty';
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Separator } from '@/components/ui/separator';
import { useTheme, type Theme } from '@/components/theme-provider';
import type { CameraDevice } from './cameraSource';
import type { CameraSettings, ControlState, LoginResult } from './protocol';
import type { MediaState } from './media';

export function CameraContextError({ message }: { message: string }) {
  return <main className="cam-login"><Alert className="cam-context-error"><CameraOff />
    <AlertTitle>相机推流节点</AlertTitle><AlertDescription>{message}</AlertDescription>
  </Alert></main>;
}

type FullscreenProps = { fullscreen: boolean; fullscreenError: string; onFullscreen(): void };
type LoginProps = FullscreenProps & { username: string; password: string; remember: boolean; busy: boolean; error: string;
  onUsername(value: string): void; onPassword(value: string): void; onRemember(value: boolean): void;
  onSubmit(event: FormEvent): void };

export function CameraLogin(props: LoginProps) {
  return <main className="cam-login"><Card className="cam-login-card">
    <CardHeader><div className="cam-login-heading"><div className="cam-brand-icon"><Camera /></div><h1 className="flex-1">相机推流节点</h1>
      <Button data-camera-fullscreen size="icon-lg" className="shrink-0 min-w-12" variant="ghost" aria-label={props.fullscreen ? '退出全屏' : '进入全屏'} onClick={props.onFullscreen}>{props.fullscreen ? <Minimize /> : <Maximize />}</Button></div>
      <CardDescription>Web · 使用同一账号关联视觉推理节点</CardDescription></CardHeader>
    <form onSubmit={props.onSubmit}>
      <CardContent><FieldGroup>
        <Field><FieldLabel htmlFor="username">账号</FieldLabel><Input id="username" value={props.username}
          maxLength={64} autoComplete="username" required disabled={props.busy} onChange={event => props.onUsername(event.target.value)} /></Field>
        <Field><FieldLabel htmlFor="password">密码</FieldLabel><Input id="password" type="password" value={props.password}
          maxLength={256} autoComplete="current-password" required disabled={props.busy} onChange={event => props.onPassword(event.target.value)} /></Field>
        <Field orientation="horizontal" className="cam-login-remember"><Checkbox id="remember" checked={props.remember}
          disabled={props.busy} onCheckedChange={checked => props.onRemember(checked === true)} />
          <FieldContent><FieldLabel htmlFor="remember">记住账号（仅本机浏览器）</FieldLabel></FieldContent></Field>
        {props.error && <Alert><CircleAlert /><AlertTitle>登录失败</AlertTitle><AlertDescription>{props.error}</AlertDescription></Alert>}
        {props.fullscreenError && <Alert><CircleAlert /><AlertTitle>全屏状态</AlertTitle><AlertDescription>{props.fullscreenError}</AlertDescription></Alert>}
      </FieldGroup></CardContent>
      <CardFooter className="cam-login-footer"><Button type="submit" disabled={props.busy}>{props.busy ? '正在登录…' : '登录'}<Play data-icon="inline-end" /></Button>
        <span className="cam-version">需要 HTTPS 或本机 localhost，并在本机允许摄像头权限</span></CardFooter>
    </form>
  </Card></main>;
}

type ScreenProps = FullscreenProps & { session: LoginResult; control?: ControlState; media?: MediaState; settings: CameraSettings;
  streaming: boolean; starting: boolean; notice: string; devices: CameraDevice[]; deviceId: string;
  videoRef: RefObject<HTMLVideoElement | null>; onStart(): void; onStop(): void; onLogout(): void;
  onBind(id: string): Promise<string | undefined>; onRefresh(): void; onCamera(id: string): void;
  onResolution(value: '480p' | '720p'): void; onPreview(value: boolean): void };
type Panel = 'menu' | 'targets' | 'help' | 'account' | 'appearance';

export function CameraScreen(props: ScreenProps) {
  const { session, control, media, settings, streaming, starting, videoRef } = props;
  const notice = props.notice || props.fullscreenError;
  const [panel, setPanel] = useState<Panel | null>(null);
  const [candidate, setCandidate] = useState('');
  const [binding, setBinding] = useState(false);
  const [bindingError, setBindingError] = useState('');
  const previousFocus = useRef<HTMLElement | null>(null);
  const { theme, setTheme, error: themeError } = useTheme();
  const online = control?.connected === true;
  const locked = streaming || starting;
  const target = control?.targets.find(item => item.deviceId === control.stream?.targetDeviceId);
  const status = !online ? '正在连接' : starting ? '正在启动' : streaming ? media?.ready ? '推流中' : '等待画面' : '未推流';
  const visible = streaming && !settings.cameraHidePreview;
  const video = videoRef.current;
  const captureSize = video?.videoWidth ? `${video.videoWidth}×${video.videoHeight}` : undefined;
  const panelTitle: Record<Panel, string> = { menu: '本机操作', targets: '选择视觉推理节点', help: '相机帮助', account: '账号与本机', appearance: '外观与设置' };
  function chooseTarget() { setCandidate(control?.stream?.targetDeviceId ?? ''); setBindingError(''); setPanel('targets'); }
  async function bind() {
    if (binding || !candidate) return;
    setBinding(true); setBindingError('');
    try { const failure = await props.onBind(candidate); if (failure) setBindingError(failure); else setPanel(null); }
    catch { setBindingError('关联失败，请重试'); }
    finally { setBinding(false); }
  }
  return <main className="cam-shell">
    <header className="cam-header">
      <div className="cam-brand"><div className="cam-brand-icon"><Camera /></div><div className="min-w-0">
        <h1>相机推流节点</h1><span className="cam-identity" title={session.device.deviceName}>{session.device.deviceName} · {online ? '已连接' : '连接中'}</span>
      </div></div>
      <div className="cam-top-actions">
        <Button data-camera-fullscreen size="icon-lg" variant="ghost" aria-label={props.fullscreen ? '退出全屏' : '进入全屏'} onClick={props.onFullscreen}>{props.fullscreen ? <Minimize /> : <Maximize />}</Button>
        <Button size="icon-lg" variant="ghost" aria-label="相机帮助" onClick={() => setPanel('help')}><CircleHelp /></Button>
        <Button size="icon-lg" variant="ghost" aria-label="外观与设置" onClick={() => setPanel('appearance')}><SunMoon /></Button>
        <Button size="icon-lg" variant="ghost" aria-label="账号与本机" onClick={() => setPanel('account')}><UserRound /></Button>
      </div>
      <Button className="cam-mobile-menu" size="icon-lg" variant="ghost" aria-label="更多操作" onClick={() => setPanel('menu')}><MoreHorizontal /></Button>
    </header>
    <div className="cam-workspace">
      <Card className="cam-control"><CardHeader><CardTitle>推流控制</CardTitle><CardAction>
        <Badge variant={streaming && online ? 'default' : 'secondary'}>{!online ? <WifiOff /> : streaming ? <Video /> : null}{status}</Badge>
      </CardAction></CardHeader>
        <CardContent className="cam-control-scroll"><div className="cam-control-body">
          <div className="cam-stats" aria-label="推流统计">
            <div className="cam-stat"><div className="cam-stat-label">本机入队</div><div className="cam-stat-value">{media?.sentFrames ?? 0}</div></div>
            <div className="cam-stat"><div className="cam-stat-label">服务收帧</div><div className="cam-stat-value">{media?.acknowledgedFrames ?? 0}</div></div>
            <div className="cam-drops"><span>本机丢弃 {media?.droppedFrames ?? 0}</span><span>服务丢弃 {media?.relayDroppedFrames ?? 0}</span></div>
          </div>
          {(notice || !online) && <Alert className="cam-state-message"><CircleAlert /><AlertTitle>{online ? '相机状态' : '连接状态'}</AlertTitle>
            <AlertDescription>{notice || control?.status || '正在连接服务'}</AlertDescription></Alert>}
          <FieldGroup className="cam-fields">
            <Field className="cam-field cam-target-field" orientation="horizontal"><FieldLabel htmlFor="target">目标</FieldLabel>
              <div className="cam-target-row"><Button id="target" variant="outline" className="cam-target" aria-haspopup="dialog"
                title={target?.deviceName} disabled={locked || !online || !control?.targets.length} onClick={chooseTarget}>
                <span className="cam-target-label">{target?.deviceName || (control?.stream?.targetDeviceId ? '已关联节点' : control?.targetsLoading ? '加载中…' : control?.targetsLoadFailed ? '加载失败' : control?.targets.length ? '选择视觉推理节点' : '暂无节点')}</span><ChevronDown data-icon="inline-end" />
              </Button><Button size="icon-lg" variant="outline" aria-label="刷新目标" disabled={!online || control?.targetsLoading} onClick={props.onRefresh}><RefreshCw /></Button></div>
            </Field>
            <Field className="cam-field" orientation="horizontal"><FieldLabel id="resolution-label">规格</FieldLabel>
              <ToggleGroup className="cam-resolution" type="single" variant="outline" spacing={0} value={settings.cameraResolution}
                disabled={locked} aria-labelledby="resolution-label" onValueChange={value => { if (value === '480p' || value === '720p') props.onResolution(value); }}>
                <ToggleGroupItem value="480p">640×480</ToggleGroupItem><ToggleGroupItem value="720p">720P</ToggleGroupItem>
              </ToggleGroup>
            </Field>
            <Field className="cam-field" orientation="horizontal"><FieldLabel htmlFor="camera">镜头</FieldLabel>
              <NativeSelect id="camera" className="cam-camera-picker" value={props.deviceId} disabled={locked} onChange={event => props.onCamera(event.target.value)}>
                <NativeSelectOption value="">默认（后置优先）</NativeSelectOption>
                {props.devices.map((device, index) => <NativeSelectOption key={device.deviceId || index} value={device.deviceId}>{device.label}</NativeSelectOption>)}
              </NativeSelect>
            </Field>
          </FieldGroup>
          {!locked && props.devices.length === 0 && <Alert><CameraOff /><AlertTitle>未检测到摄像头</AlertTitle>
            <AlertDescription>请确认本机有摄像头，并在浏览器中允许访问后重试。</AlertDescription></Alert>}
        </div></CardContent>
        <CardFooter className="cam-action"><Button disabled={!streaming && (starting || !online || !control?.stream?.targetDeviceId)}
          onClick={streaming ? props.onStop : props.onStart}>{streaming ? <Square data-icon="inline-start" /> : <Play data-icon="inline-start" />}{streaming ? '停止推流' : starting ? '正在启动…' : '开始推流'}</Button></CardFooter>
      </Card>
      <Card className="cam-preview" aria-label="本机预览区"><CardHeader><CardTitle className="cam-preview-title"><Camera />本机预览</CardTitle>
        <CardAction><Field className="cam-preview-toggle" orientation="horizontal"><Switch aria-label="本机预览开关"
          checked={!settings.cameraHidePreview} onCheckedChange={props.onPreview} /></Field></CardAction></CardHeader>
        <div className="cam-stage"><video ref={videoRef} hidden={!visible} playsInline muted autoPlay />
          {!visible && <Empty className="cam-placeholder"><EmptyHeader><EmptyMedia variant="icon">{settings.cameraHidePreview ? <EyeOff /> : <Camera />}</EmptyMedia>
            <EmptyTitle>{settings.cameraHidePreview ? '预览已关闭' : '暂无画面'}</EmptyTitle>
            <EmptyDescription>{settings.cameraHidePreview && streaming ? '推流仍在继续' : control?.stream?.targetDeviceId ? '开始推流后显示画面' : '关联视觉推理节点后开始'}</EmptyDescription>
          </EmptyHeader></Empty>}
        </div><div className="cam-preview-footer"><span>{captureSize && visible ? `采集 ${captureSize}` : '本机画面'}</span><span>等比预览</span></div>
      </Card>
    </div>
    <Dialog open={panel !== null} onOpenChange={open => { if (!open && !binding) setPanel(null); }}>
      <DialogContent className="cam-dialog" onOpenAutoFocus={() => { previousFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null; }}
        onCloseAutoFocus={event => {
          event.preventDefault();
          const trigger = previousFocus.current;
          const target = trigger?.getClientRects().length ? trigger :
            Array.from(document.querySelectorAll<HTMLButtonElement>('.cam-header button')).find(button => button.getClientRects().length);
          target?.focus({ preventScroll: true });
        }}>
        <DialogHeader><DialogTitle>{panel ? panelTitle[panel] : ''}</DialogTitle>
          <DialogDescription className="cam-dialog-description">{panel === 'targets' ? '同账号内可关联的视觉推理节点' : panel === 'help' ? '推流、画面与收帧状态' : panel === 'appearance' ? '仅作用于本机界面' : '相机推流节点 · Web'}</DialogDescription></DialogHeader>
        {panel === 'menu' && <div className="cam-dialog-options">
          <Button data-camera-fullscreen variant="outline" onClick={() => { setPanel(null); props.onFullscreen(); }}>{props.fullscreen ? <Minimize data-icon="inline-start" /> : <Maximize data-icon="inline-start" />}{props.fullscreen ? '退出全屏' : '进入全屏'}</Button>
          <Button variant="outline" onClick={() => setPanel('help')}><CircleHelp data-icon="inline-start" />相机帮助</Button>
          <Button variant="outline" onClick={() => setPanel('appearance')}><SunMoon data-icon="inline-start" />外观与设置</Button>
          <Button variant="outline" onClick={() => setPanel('account')}><UserRound data-icon="inline-start" />账号与本机</Button>
        </div>}
        {panel === 'targets' && <>
          <ToggleGroup className="cam-target-list" type="single" value={candidate} onValueChange={value => { if (value) setCandidate(value); }}
            orientation="vertical" variant="outline" disabled={binding || locked || !online} aria-label="视觉推理节点">
            {(control?.targets ?? []).map(item => <ToggleGroupItem key={item.deviceId} value={item.deviceId}><span>{item.deviceName}
              <span className="cam-target-detail">{item.online === false ? '离线' : '可关联'} · {item.platform}</span></span>{candidate === item.deviceId && <Check />}</ToggleGroupItem>)}
          </ToggleGroup>
          {bindingError && <Alert><CircleAlert /><AlertTitle>关联失败</AlertTitle><AlertDescription>{bindingError}</AlertDescription></Alert>}
        </>}
        {panel === 'help' && <div className="cam-dialog-copy">
          <section><h3>开始与停止</h3><p>关联同账号的视觉推理节点后开始推流。推流期间不能更换目标、规格或镜头。</p></section>
          <section><h3>收帧统计</h3><p>本机入队表示发送队列已接纳；服务收帧表示中继已收到，不代表完成推理。本机丢弃与服务丢弃分别统计。</p></section>
          <section><h3>前台与画面</h3><p>首次点击或按键请求全屏，可从顶部切换；浏览器不支持时保留普通页面。横竖屏切换保留推流。切换标签页、进入后台或锁屏会停止推流，返回后手动开始。关闭本机预览不停止推流。</p></section>
          <section><h3>规格与权限</h3><p>640×480 / 720P 是发送上限，最高 5 帧/秒，实际尺寸取决于摄像头能力。需要 HTTPS 或本机 localhost，并在本机允许摄像头权限；亮度由系统控制。</p></section>
          <Separator /><div className="cam-data-list">
            <DataRow label="关联目标" value={target?.deviceName || (control?.stream?.targetDeviceId ? '已关联节点' : '未关联')} />
            <DataRow label="控制状态" value={control?.status || '正在连接'} /><DataRow label="画面状态" value={media?.status || '未推流'} />
            <DataRow label="主动未采样" value={String(media?.sampledOutFrames ?? 0)} /><DataRow label="媒体会话" value={String(media?.sessions ?? 0)} />
          </div>
        </div>}
        {panel === 'account' && <><div className="cam-data-list"><DataRow label="账号" value={session.account.username} />
          <DataRow label="本机名称" value={session.device.deviceName} /><DataRow label="平台" value="Web 浏览器" /></div><Separator />
          <Button variant="outline" onClick={() => { setPanel(null); props.onLogout(); }}><LogOut data-icon="inline-start" />退出登录</Button></>}
        {panel === 'appearance' && <FieldGroup><Field><FieldLabel id="theme-label">界面主题</FieldLabel>
          <ToggleGroup className="cam-appearance-options" type="single" variant="outline" value={theme} aria-labelledby="theme-label"
            onValueChange={value => { if (value) setTheme(value as Theme); }}>
            <ToggleGroupItem value="system"><Monitor data-icon="inline-start" />系统</ToggleGroupItem><ToggleGroupItem value="light"><Sun data-icon="inline-start" />浅色</ToggleGroupItem><ToggleGroupItem value="dark"><Moon data-icon="inline-start" />深色</ToggleGroupItem>
          </ToggleGroup></Field>{themeError && <Alert><AlertDescription>{themeError}</AlertDescription></Alert>}</FieldGroup>}
        <DialogFooter><DialogClose asChild><Button variant="outline" disabled={binding}>返回</Button></DialogClose>
          {panel === 'targets' && <Button disabled={!candidate || binding || locked || !online} onClick={() => void bind()}>{binding ? '正在关联…' : '关联此节点'}</Button>}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </main>;
}

function DataRow({ label, value }: { label: string; value: string }) {
  return <div className="cam-data-row"><span>{label}</span><strong>{value}</strong></div>;
}
