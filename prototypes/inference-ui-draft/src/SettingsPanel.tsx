import {useEffect, useRef, useState, type ReactNode} from 'react';
import {Activity, Check, ExternalLink, Info, LogIn, LogOut, RefreshCw, ShieldCheck, Trash2} from 'lucide-react';
import {Button} from '@/components/ui/button';
import {Input} from '@/components/ui/input';
import {Checkbox} from '@/components/ui/checkbox';
import {NativeSelect, NativeSelectOption} from '@/components/ui/native-select';
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from '@/components/ui/dialog';
import './SettingsPanel.css';

export type SettingsPanelProps = {
  section:Section; savedDeviceName:string; onDeviceNameSaved:(value:string)=>void;
  android: boolean; online: boolean; onOnline: (value: boolean) => void;
  loggedIn: boolean; onLogin: (value: boolean) => void; notify: (message: string) => void;
  backend: string; onBackend: (value: string) => void; legacy: boolean; onLegacy: (value: boolean) => void;
  runningSources: {id: number; name: string; activeBackend?: string; rate?: number;push?:boolean}[];
};
export type Section = 'account' | 'connection' | 'backend' | 'background' | 'maintenance' | 'performance';
type Prompt = {title: string; body: string; label?: string; action?: () => void; danger?: boolean};
type Sample = {id: number; name: string; frames: number; activeBackend?: string; rate?: number;push?:boolean};
const SAMPLE_ACCOUNT = 'demo', SAMPLE_PASSWORD = 'demo1234';
// Non-secret review state survives navigating away from the settings component.
function Note({children}: {children: ReactNode}) {return <p className="sp-note"><Info size={14}/><span>{children}</span></p>;}
function Row({label, children}: {label: string; children: ReactNode}) {return <div className="sp-row"><span>{label}</span><div>{children}</div></div>;}

/** Local HTML review state. No account requests, native files, or OS permission changes. */
export function SettingsPanel({section,savedDeviceName,onDeviceNameSaved,android, online, onOnline, loggedIn, onLogin, notify, backend, onBackend, legacy, onLegacy, runningSources}: SettingsPanelProps) {
  const [prompt, setPrompt] = useState<Prompt | null>(null);
  const [deviceName, setDeviceName] = useState(savedDeviceName), [savedName, setSavedName] = useState(savedDeviceName);
  const [nameError, setNameError] = useState(''), [remember, setRemember] = useState(false);
  const [accountBusy, setAccountBusy] = useState(false), [loginFails, setLoginFails] = useState(false);
  const [connecting, setConnecting] = useState(false), [reconnectFails, setReconnectFails] = useState(false);
  const [updateBusy, setUpdateBusy] = useState(false);
  const [cacheBusy, setCacheBusy] = useState(false), [cacheReport, setCacheReport] = useState(false), [cacheCleaned, setCacheCleaned] = useState(false);
  const [crashSample, setCrashSample] = useState(false), [crashExpanded, setCrashExpanded] = useState(false);
  const [sampleSeconds, setSampleSeconds] = useState<number | null>(null), [samples, setSamples] = useState<Sample[] | null>(null), [noFrames, setNoFrames] = useState(false);
  const [passwordDialog, setPasswordDialog] = useState(false);
  const timers = useRef<Set<ReturnType<typeof setTimeout>>>(new Set());
  const sampleTimer = useRef<ReturnType<typeof setInterval> | null>(null);
  const sourcesRef = useRef(runningSources), sessionRef = useRef(loggedIn), onlineRef = useRef(online);
  sourcesRef.current = runningSources; sessionRef.current = loggedIn; onlineRef.current = online;
  function later(action: () => void, delay = 750) {
    const timer = setTimeout(() => {timers.current.delete(timer);action();}, delay);timers.current.add(timer);
  }
  useEffect(() => () => {timers.current.forEach(clearTimeout);if (sampleTimer.current) clearInterval(sampleTimer.current);}, []);
  useEffect(() => {
    setDeviceName(savedDeviceName);setSavedName(savedDeviceName);setNameError('');setPrompt(null);setPasswordDialog(false);setSamples(null);setSampleSeconds(null);
    setCacheReport(false);setCacheCleaned(false);setAccountBusy(false);setConnecting(false);setCacheBusy(false);setUpdateBusy(false);
    timers.current.forEach(clearTimeout);timers.current.clear();if(sampleTimer.current){clearInterval(sampleTimer.current);sampleTimer.current=null;}
  }, [android]);
  function login() {
    if(accountBusy)return;
    setAccountBusy(true);onOnline(false);onLogin(false);
    later(() => {
      setAccountBusy(false);
      if(loginFails){notify('登录失败：示例账号或密码错误。关闭「登录失败」后可以重试。');return;}
      onLogin(true);onOnline(true);notify(remember?'示例登录成功；来源保持停止，记住选项不存储任何密码。':'已登录示例账号；来源保持停止，可按需重新启动');
    });
  }
  function logout() {
    setPrompt({title:'退出登录？',body:'退出会停止全部来源并清除本机会话；来源参数与遮罩保留。本草稿只演示会话状态，不撤销真实账号。',label:'退出示例账号',danger:true,action:()=>{
      setAccountBusy(true);onOnline(false);onLogin(false);
      later(()=>{setAccountBusy(false);notify(online?'已退出示例账号。':'示例本机会话已清除；离线状态下无法确认服务端撤销。');});
    }});
  }
  function reconnect() {
    if(!loggedIn||connecting)return;
    setConnecting(true);onOnline(false);
    later(()=>{setConnecting(false);if(!sessionRef.current)return;if(reconnectFails){notify('连接失败：示例服务暂不可达，请检查网络后重试。');onOnline(false);}else{onOnline(true);notify('示例控制通道已重新连接；媒体与告警回执仍分别判断');}},1200);
  }
  function saveName() {
    if(!deviceName.trim()||deviceName.trim().length>64||/[\u0000-\u001f]/.test(deviceName)){setNameError('名称须为 1–64 个字符，不能包含换行或控制字符');return;}
    if(!online){setNameError('服务未连接，名称未保存；恢复连接后重试。');return;}
    const next=deviceName.trim();setDeviceName(next);setSavedName(next);onDeviceNameSaved(next);setNameError('');notify('示例设备名称已保存，未修改真实设备');
  }
  function measure() {
    if(sampleTimer.current)return;
    setSamples(null);setSampleSeconds(10);
    const start=Date.now(),collected=new Map<number,Sample>();
    sampleTimer.current=setInterval(()=>{
      if(!noFrames)sourcesRef.current.forEach(source=>{const current=collected.get(source.id);collected.set(source.id,{...source,frames:(current?.frames??0)+(source.rate??2)});});
      const remaining=Math.max(0,10-Math.floor((Date.now()-start)/1000));
      if(remaining===0){if(sampleTimer.current)clearInterval(sampleTimer.current);sampleTimer.current=null;setSampleSeconds(null);setSamples([...collected.values()]);notify(collected.size?'10 秒演示采样结束；结果为示例数据，不是本机性能测量':'未采到示例处理帧；请先启动来源并确认画面可用');}else setSampleSeconds(remaining);
    },1000);
  }
  function maintainCache(clean:boolean) {
    if(cacheBusy)return;setCacheBusy(true);setCacheReport(false);
    later(()=>{setCacheBusy(false);if(!sessionRef.current||sourcesRef.current.length===0){notify('操作取消：当前实现要求会话有效且至少有一路来源运行。');return;}setCacheReport(true);if(clean){setCacheCleaned(true);notify(cacheCleaned?'演示清理完成：没有可清理的过期项；未删除真实文件。':'演示清理完成：3 项 / 5.2 MiB；失败 0 项。没有删除真实文件。');}else notify('示例盘点完成；没有读取本机文件。');});
  }
  function nativeBoundary(title:string,body:string){notify(`${title}：${body} HTML 不调用系统能力，未执行。`);}
  return <div className="settings-panel">
    <section id={`settings-${section}`} role="tabpanel" aria-label="节点配置" className="sp-content">
      {section==='account'&&<>
        <Row label="当前账号"><span>{loggedIn?`${SAMPLE_ACCOUNT} · 示例账号`:'未登录'}</span>{loggedIn&&<Button variant="outline" disabled={accountBusy} onClick={logout}><LogOut/>退出登录</Button>}</Row>
        {!loggedIn&&<form className="sp-login" onSubmit={event=>{event.preventDefault();login();}}>
          <div className="sp-form-grid"><label className="field"><span>示例账号</span><Input aria-label="示例账号" value={SAMPLE_ACCOUNT} readOnly autoComplete="off"/></label><label className="field"><span>示例密码</span><Input aria-label="示例密码" type="password" value={SAMPLE_PASSWORD} readOnly autoComplete="off"/></label></div>
          <div className="sp-actions"><label className="sp-checkbox"><Checkbox checked={remember} disabled={accountBusy} onCheckedChange={checked=>setRemember(checked===true)} aria-label="记住账号密码"/>记住账号密码</label><Button type="submit" disabled={accountBusy}><LogIn/>{accountBusy?'登录中…':'登录'}</Button></div>
          <p className="sp-caption">仅使用固定样例 demo / demo1234，不接收、不保存真实凭据。</p>
          <details className="sp-review"><summary>评审场景</summary><label className="sp-checkbox"><Checkbox checked={loginFails} disabled={accountBusy} onCheckedChange={checked=>setLoginFails(checked===true)} aria-label="演示登录失败"/>登录失败</label></details>
        </form>}
        <div className="sp-divider"/>
        <label className="field"><span>设备名称</span><div className="sp-input-action"><Input aria-label="设备名称" value={deviceName} disabled={!loggedIn||accountBusy} maxLength={64} onPaste={event=>{const input=event.currentTarget,next=input.value.slice(0,input.selectionStart??0)+event.clipboardData.getData('text')+input.value.slice(input.selectionEnd??input.value.length);if(next.length>64||/[\u0000-\u001f]/.test(next)){event.preventDefault();setNameError('粘贴未应用：名称最多 64 个字符，不能包含换行或控制字符');}}} onChange={event=>{setDeviceName(event.target.value);setNameError('');}}/><Button variant="outline" onClick={saveName} disabled={!loggedIn||accountBusy||deviceName===savedName}><Check/>保存名称</Button><Button variant="ghost" disabled={deviceName===savedName||accountBusy} onClick={()=>{setDeviceName(savedName);setNameError('');}}>还原</Button></div><small>1–64 个字符，不含换行或控制字符</small></label>
        <p role="alert" aria-live="polite" className="sp-error sp-name-error">{nameError||'\u00a0'}</p>
        {android&&loggedIn&&<><Row label="设备身份"><span className="sp-mono">android-inference-demo</span></Row><Row label="账号安全"><Button variant="outline" onClick={()=>setPasswordDialog(true)}><ShieldCheck/>修改密码</Button></Row><p className="sp-caption">改密会让所有设备重新登录；草稿仅提供样例流程。</p></>}
        <Note>账号变更会停止全部来源；重新登录后不会把示例来源自动标记为运行。</Note>
      </>}
      {section==='connection'&&<>
        {!android&&<div className="sp-actions"><Button variant="outline" disabled={!loggedIn||connecting} onClick={reconnect}><RefreshCw/>{connecting?'连接中…':'重新连接'}</Button></div>}
        {android&&<p className="sp-caption">Android 控制连接由运行时自动重连，不提供手动重连按钮。</p>}
        <Note>控制连接、媒体收帧与服务回执是不同环节。服务回执不代表通知节点收件或声音播放。</Note>
        {!android&&<><details className="sp-review"><summary>评审场景</summary><label className="sp-checkbox"><Checkbox checked={reconnectFails} disabled={connecting} onCheckedChange={checked=>setReconnectFails(checked===true)} aria-label="演示重连失败"/>下一次重连失败</label><p className="sp-caption">只切换 HTML 连接状态，不访问真实服务。</p></details></>}
      </>}
      {section==='backend'&&!android&&<>
        <label className="field"><span>推理后端偏好</span><NativeSelect aria-label="推理后端偏好" value={legacy?'CPU':backend} disabled={legacy} onChange={event=>{onBackend(event.target.value);notify('示例后端偏好已更新，下次启动来源生效');}}>{!legacy&&<NativeSelectOption value="DirectML">DirectML · GPU 加速</NativeSelectOption>}<NativeSelectOption value="CPU">{legacy?'CPU · Windows 7 固定后端':'CPU · 兼容与诊断'}</NativeSelectOption></NativeSelect></label>
        <Note>{legacy?'Windows 7 的 legacy 运行时不具备 DirectML，只能使用 CPU。':'偏好不是实际运行后端。加速失败时可回退 CPU，实际后端和回退原因显示在对应来源。'}现有运行中的来源不被此选择重启。</Note>
        <details className="sp-review"><summary>评审平台档位</summary><NativeSelect aria-label="评审平台档位" value={legacy?'legacy':'modern'} onChange={event=>{const next=event.target.value==='legacy';onLegacy(next);if(next)onBackend('CPU');}}><NativeSelectOption value="modern">现代 Windows</NativeSelectOption><NativeSelectOption value="legacy">Windows 7 · legacy</NativeSelectOption></NativeSelect><p className="sp-caption">仅用于检查条件显示。正式应用自动选择运行时，没有此开关。</p></details>
      </>}
      {section==='background'&&<>
        {android?<><Note>来源运行时保持前台服务，退到后台或锁屏后推理继续。运行意图按来源保存，进程被系统回收后按意图恢复。</Note><Button variant="outline" onClick={()=>nativeBoundary('系统应用设置','原生应用会打开当前应用的 Android 系统设置，相机与通知权限需要本机用户授权；不会自行改变锁屏或唤醒设置。')}><ExternalLink/>系统应用设置</Button></>:<><div className="sp-actions"><Button variant="outline" onClick={()=>nativeBoundary('刷新驻留状态','原生端会读取独立驻留进程；未运行时尝试拉起，再次核验实际状态。')}><RefreshCw/>刷新驻留状态</Button></div><Note>关闭主窗口不等于完整退出。完整退出需要先确认驻留已关闭；若关闭失败，主体保持运行并报告原因。</Note></>}
      </>}
      {section==='maintenance'&&<>
        <Row label="客户端版本"><span>0.7.8 · 源码快照</span><Button variant="outline" disabled={updateBusy} onClick={()=>{setUpdateBusy(true);later(()=>{setUpdateBusy(false);notify(onlineRef.current?'示例结果：暂无可用更新。未查询线上版本，也未下载或安装。':'检查失败：示例服务未连接。恢复连接后可重试。');},1100);}}><RefreshCw/>{updateBusy?'检查中…':'检查更新'}</Button></Row>
        <p className="sp-caption">正式应用从官方稳定版入口下载更新，系统安装流程不在 HTML 中执行。</p>
        {android?<><div className="sp-divider"/><h3>临时缓存</h3><p className="sp-caption">仅当前账号的过期诊断导出（24 小时）与已知安装包暂存（7 天）；模型、账号、配置与业务数据不在清理范围。</p><div className="sp-actions"><Button variant="outline" disabled={cacheBusy||!loggedIn} onClick={()=>maintainCache(false)}>{cacheBusy?'处理中…':'缓存盘点'}</Button><Button variant="outline" disabled={cacheBusy||!loggedIn} onClick={()=>setPrompt({title:'清理临时缓存？',body:'只清理已过期、未受保护的当前账号诊断导出与安装包暂存。示例操作不删除真实文件。',label:'清理示例缓存',danger:true,action:()=>maintainCache(true)})}><Trash2/>清理临时缓存</Button></div>{cacheReport&&<div className="sp-cache-report"><Row label="诊断导出"><span>{cacheCleaned?'1 项 / 0.8 MiB · 可清理 0 项':'3 项 / 2 MiB · 可清理 2 项 / 1.2 MiB'}</span></Row><Row label="安装包暂存"><span>{cacheCleaned?'0 项 · 可清理 0 项':'1 项 / 4 MiB · 可清理 1 项 / 4 MiB'}</span></Row></div>}<Note>按当前原生实现，缓存操作校验有效会话及运行中的来源；没有运行来源时会取消，不返回清理成功。</Note>
          <div className="sp-divider"/><h3>崩溃与退出记录</h3><div className="sp-actions"><Button variant="outline" onClick={()=>notify('示例记录已刷新；未读取 Android 系统日志')}><RefreshCw/>刷新</Button><Button variant="outline" disabled={!crashSample} onClick={()=>nativeBoundary('写入日志','原生端会将当前崩溃与系统退出原因写入本机日志。')}>写入日志</Button></div>{crashSample?<button className="sp-crash-record" aria-expanded={crashExpanded} onClick={()=>setCrashExpanded(!crashExpanded)}><strong>10-17 14:20 · 示例退出记录</strong><span>系统内存回收</span>{crashExpanded&&<span className="sp-caption">样例详情：系统终止后台进程。真实记录来自本机异常处理器及系统退出原因，不代表当前设备出现故障。</span>}</button>:<p className="sp-caption">没有崩溃或异常退出记录 · 空态演示</p>}<details className="sp-review"><summary>评审场景</summary><label className="sp-checkbox"><Checkbox checked={crashSample} onCheckedChange={checked=>setCrashSample(checked===true)} aria-label="显示示例退出记录"/>显示一条示例退出记录</label></details>
        </>:<><div className="sp-divider"/><Row label="退出应用"><span>停止全部检测，并关闭后台驻留</span><Button variant="outline" onClick={()=>nativeBoundary('完整退出','原生端先关闭驻留程序，成功后退出主程序；关闭失败时保留主体。此草稿不会关闭页面或停止真实设备。')}>完整退出</Button></Row><Note>Windows 本机设置没有“清理缓存”按钮。缓存盘点与清理属于控制台远程维护，不在此伪造本机功能。</Note></>}
      </>}
      {section==='performance'&&!android&&<>
        <p className="sp-caption">先启动需要检查的来源，再采样 10 秒。结果按来源汇总，不把一个来源的耗时套用到整个节点。</p><div className="sp-actions"><Button disabled={sampleSeconds!==null} onClick={measure}><Activity/>{sampleSeconds!==null?`正在采样 · 剩余 ${sampleSeconds} 秒`:'开始性能检测'}</Button>{sampleSeconds===null&&samples!==null&&<span className="sp-caption" role="status">{samples.length?`演示完成 · ${samples.length} 路 / ${samples.reduce((sum,sample)=>sum+sample.frames,0)} 个样例帧`:'没有采到处理帧'}</span>}</div>
        {sampleSeconds!==null&&<progress className="sp-progress" max={10} value={10-sampleSeconds} aria-label="性能采样进度"/>}{samples?.length===0&&<Note>请先启动来源，并确认相机、窗口或远程画面可用。无样本时不生成性能结论。</Note>}
        {samples?.map(sample=><div className="sp-performance" key={sample.id}><h3>{sample.name}</h3><p className="sp-caption">{sample.activeBackend||'未提供实际后端'} · {sample.frames} 个示例帧 · 样例实际 {(sample.frames/10).toFixed(1)} / 目标 {sample.rate??'—'} FPS · 帧预算 {sample.rate?(1000/sample.rate).toFixed(1):'—'} ms · {sample.push?'平均处理 6.6 ms · 主要耗时：编码':'平均处理 31.8 ms · 主要耗时：模型推理'}</p><div className="sp-stage-heading"><span>环节</span><span>平均 / P95 / 最大 · ms</span></div>{(sample.push?[['采集','4.0 / 6 / 7'],['编码','2.0 / 3 / 4'],['发送入队','0.6 / 1 / 2']]:[['采集 / 解码','4.0 / 6 / 7'],['遮罩','0.8 / 1 / 2'],['预处理','2.0 / 3 / 3'],['模型推理','24.0 / 28 / 31'],['结果解析','0.6 / 1 / 1'],['告警处理','0.4 / 1 / 1']]).map(([label,value])=><Row label={label} key={label}><span className="sp-mono">{value}</span></Row>)}</div>)}
        <Note>计时和结果仅演示交互，不是浏览器或设备实测。原生检测统计真实处理帧；推流态另统计编码与发送入队，入队耗时不代表网络往返或远端处理。</Note><details className="sp-review"><summary>评审场景</summary><label className="sp-checkbox"><Checkbox checked={noFrames} disabled={sampleSeconds!==null} onCheckedChange={checked=>setNoFrames(checked===true)} aria-label="演示采样期间无处理帧"/>采样期间无处理帧</label></details>
      </>}
    </section>
    <Dialog open={prompt!==null} onOpenChange={open=>{if(!open)setPrompt(null);}}><DialogContent><DialogHeader><DialogTitle>{prompt?.title}</DialogTitle><DialogDescription>{prompt?.body}</DialogDescription></DialogHeader><DialogFooter>{prompt?.action&&<Button variant="outline" onClick={()=>setPrompt(null)}>取消</Button>}<Button variant={prompt?.danger?'destructive':'default'} onClick={()=>{const action=prompt?.action;setPrompt(null);action?.();}}>{prompt?.label||'知道了'}</Button></DialogFooter></DialogContent></Dialog>
    <Dialog open={passwordDialog} onOpenChange={setPasswordDialog}><DialogContent><DialogHeader><DialogTitle>修改密码 · 样例流程</DialogTitle><DialogDescription>真实改密会让所有设备重新登录。这里不输入、不提交真实密码，仅演示结果。</DialogDescription></DialogHeader><label className="field"><span>当前示例密码</span><Input type="password" value={SAMPLE_PASSWORD} readOnly aria-label="当前示例密码" autoComplete="off"/></label><label className="field"><span>新示例密码（≥8 字符）</span><Input type="password" value="demo5678" readOnly aria-label="新示例密码" autoComplete="off"/></label><DialogFooter><Button variant="outline" onClick={()=>setPasswordDialog(false)}>取消</Button><Button onClick={()=>{setPasswordDialog(false);setPrompt({title:'确认演示修改密码？',body:'将结束当前示例会话并停止全部示例来源，返回登录状态。不会修改任何真实账号密码。',label:'确认演示',action:()=>{onOnline(false);onLogin(false);notify('改密流程演示结束，全部示例来源已停止；登录仍使用 demo / demo1234。');}});}}>继续</Button></DialogFooter></DialogContent></Dialog>
  </div>;
}
