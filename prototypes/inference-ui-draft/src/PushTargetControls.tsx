import {useEffect, useRef, useState} from 'react';
import {Link2Off, RefreshCw} from 'lucide-react';
import {Button} from '@/components/ui/button';
import {NativeSelect, NativeSelectOption} from '@/components/ui/native-select';
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from '@/components/ui/dialog';
import './PushTargetControls.css';

export type PushTargetStatus = {
  phase: 'loading' | 'binding' | 'unbinding' | 'ready' | 'empty' | 'error' | 'auth';
  tone: 'neutral' | 'success' | 'warning' | 'error';
  text: string;
  targetId: string;
  targetName: string;
  targetOnline: boolean | null;
  targetPublishing: boolean;
  candidateCount: number;
};
export type PushTargetControlsProps = {
  loggedIn: boolean;
  online: boolean;
  scenario: string;
  boundTarget: string;
  onBinding: (id: string) => void;
  notify: (message: string) => void;
  /** Render once in the parent's unified workspace status region, not in navigation. */
  onStatus?: (status: PushTargetStatus) => void;
};
type Target = {id: string; name: string; online: boolean; publishing: boolean};
const SAMPLE_TARGETS: Target[] = [
  {id: 'demo-inference-win-01', name: '值守推理节点 · 示例 Windows', online: true, publishing: false},
  {id: 'demo-inference-android-02', name: '移动推理节点 · 示例 Android', online: true, publishing: true},
  {id: 'demo-inference-win-offline', name: '备用推理节点 · 示例 Windows', online: false, publishing: false},
];
const displayName = (target: Target) => target.name + (target.publishing ? '（推流中·暂时收不了）' : target.online ? '' : '（离线）');
const isAuthError = (scenario: string) => scenario === 'targets-auth';

/** HTML-only simulation of MultiSourceViewModel 415–550 and StreamTargetService.
 * No fetch, browser storage, real identity, or native device/credential access.
 * Offline and publishing candidates remain selectable, as in the actual binding API.
 */
export function PushTargetControls(props: PushTargetControlsProps) {
  const {loggedIn, online, scenario, boundTarget} = props;
  const [targets, setTargets] = useState<Target[]>([]);
  const [phase, setPhase] = useState<PushTargetStatus['phase']>('loading');
  const [message, setMessage] = useState('');
  const [pendingTarget, setPendingTarget] = useState('');
  const [confirmUnbind, setConfirmUnbind] = useState(false);
  const context = useRef(props); context.current = props;
  const alive = useRef(false), version = useRef(0), busy = useRef(false);
  const timers = useRef(new Set<ReturnType<typeof setTimeout>>());

  function cancelPending() {
    version.current += 1;
    timers.current.forEach(clearTimeout);
    timers.current.clear();
    busy.current = false;
  }
  function later(action: () => void, delay = 650) {
    const ticket = version.current;
    const snapshot = {loggedIn, online, scenario};
    const timer = setTimeout(() => {
      timers.current.delete(timer);
      const current = context.current;
      if (!alive.current || ticket !== version.current || current.loggedIn !== snapshot.loggedIn || current.online !== snapshot.online || current.scenario !== snapshot.scenario) return;
      action();
    }, delay);
    timers.current.add(timer);
  }
  function fail(text: string, nextPhase: 'auth' | 'error' = 'error', announce = true) {
    busy.current = false;
    setPendingTarget('');
    setPhase(nextPhase);
    setMessage(text);
    if (announce) context.current.notify(text);
  }
  function refresh(announce = false) {
    if (busy.current) return;
    if (!loggedIn || isAuthError(scenario)) {
      fail(loggedIn ? '登录已失效，请重新登录后读取推流目标。' : '尚未登录账号，暂时无法选择推流目标。', 'auth', announce);
      return;
    }
    busy.current = true;
    setPhase('loading'); setMessage(''); setPendingTarget('');
    later(() => {
      if (!online || scenario === 'targets-error') {
        fail('无法连接统一服务，推流目标与关联关系未刷新。请检查网络后重试。', 'error', announce);
        return;
      }
      const candidates = scenario === 'targets-empty' ? [] : SAMPLE_TARGETS.map(target => ({...target}));
      setTargets(candidates);
      busy.current = false;
      setPhase(candidates.length ? 'ready' : 'empty');
      if (announce) context.current.notify(candidates.length ? '已刷新示例账号 demo 的推流目标；未连接真实服务。' : '示例账号 demo 暂无其他推理节点；节点上线后可刷新。');
    });
  }

  // Invalidate every delayed operation on logout, connection/scenario change and unmount.
  // The ref check also guards the interval before a changed-prop effect has executed.
  useEffect(() => {
    alive.current = true;
    cancelPending();
    // Native RefreshPushTargets retains the last successful directory on a load error.
    // Only logout clears account-scoped candidates; successful refresh replaces them.
    if (!loggedIn) setTargets([]);
    setMessage(''); setPendingTarget(''); setConfirmUnbind(false);
    refresh();
    return () => {alive.current = false; cancelPending();};
  }, [loggedIn, online, scenario]);

  useEffect(()=>{if(!loggedIn||targets.length||phase==='loading'||phase==='binding'||phase==='unbinding')return;const timer=setTimeout(()=>refresh(),10000);return()=>clearTimeout(timer)},[loggedIn,online,scenario,targets.length,phase]);
  const target = targets.find(item => item.id === boundTarget);
  const targetName = target?.name || (boundTarget ? '已关联（目标不在当前列表里）' : '未关联');
  useEffect(() => {
    let text = message, tone: PushTargetStatus['tone'] = 'neutral';
    if (phase === 'loading') text = '读取同账号目标…';
    else if (phase === 'binding') text = `正在关联「${targets.find(item => item.id === pendingTarget)?.name || '示例目标'}」…`;
    else if (phase === 'unbinding') text = '正在解除本节点的推流目标…';
    else if (phase === 'auth' || phase === 'error') tone = 'error';
    else if (phase === 'empty') {text = boundTarget ? '同账号暂时没有其他候选推理节点；保留已有绑定，目标不在当前列表。' : '同账号没有候选目标 · 可刷新重试'; tone = 'warning';}
    else if (!boundTarget) text = '目标未关联 · 选择即关联';
    else if (!target) {text = '已关联目标不在当前列表 · 绑定保留'; tone = 'warning';}
    else if (target.publishing) {text = `已关联「${target.name}」，但目标自身正在推流，暂时不能接收画面。`; tone = 'warning';}
    else if (!target.online) {text = `已关联「${target.name}」，目标当前离线；关联不代表已开始推流。`; tone = 'warning';}
    else {text = `已关联「${target.name}」 · 未自动启动来源`; tone = 'success';}
    context.current.onStatus?.({phase, tone, text, targetId: boundTarget, targetName, targetOnline: target?.online ?? null, targetPublishing: target?.publishing ?? false, candidateCount: targets.length});
  }, [phase, message, targets, pendingTarget, boundTarget, targetName, target]);

  function bind(id: string) {
    if (!id || id === boundTarget || busy.current) return;
    if (!loggedIn || isAuthError(scenario)) {fail('尚未登录有效账号，暂时无法关联推流目标。', 'auth'); return;}
    const next = targets.find(item => item.id === id);
    if (!next) {fail('目标推理节点不可用或不在同一示例账号。'); return;}
    busy.current = true; setPendingTarget(id); setPhase('binding'); setMessage('');
    later(() => {
      if (!online || scenario === 'targets-error') {fail('关联失败：无法连接统一服务，保留原推流目标。'); return;}
      if (scenario === 'target-forbidden') {fail('关联失败：服务端不允许本节点关联推流目标，保留原推流目标。'); return;}
      if (scenario === 'target-missing') {fail('关联失败：目标推理节点不可用或不在同一账号，保留原推流目标。'); return;}
      if (scenario === 'target-full') {fail('关联失败：该推理节点的来源已达上限，保留原推流目标。'); return;}
      busy.current = false; setPendingTarget(''); setPhase('ready');
      context.current.onBinding(id);
      context.current.notify(`已关联示例目标「${next.name}」；未开始任何真实推流。`);
    });
  }
  function unbind() {
    setConfirmUnbind(false);
    if (busy.current || !boundTarget) return;
    if (!loggedIn || isAuthError(scenario)) {fail('尚未登录有效账号，暂时无法解除推流目标。', 'auth'); return;}
    const previousBinding = boundTarget;
    busy.current = true; setPhase('unbinding'); setMessage('');
    later(() => {
      if (context.current.boundTarget !== previousBinding) {
        busy.current = false; setPhase('ready');
        context.current.notify('关联关系已变化，本次解除操作已取消。');
        return;
      }
      if (!online || scenario === 'targets-error') {fail('解除失败：无法连接统一服务，原推流关联保持不变。'); return;}
      busy.current = false; setPhase(targets.length ? 'ready' : 'empty');
      context.current.onBinding('');
      context.current.notify('已解除示例关联；本节点所有本地来源不再推给原目标，来源配置保留。');
    });
  }
  const pending = phase === 'loading' || phase === 'binding' || phase === 'unbinding';
  const unavailable = !loggedIn || isAuthError(scenario);
  const selection = pendingTarget || boundTarget;
  return <div className="push-target-controls" aria-label="推流目标操作" aria-busy={pending}>
    <label className="push-target-select"><span>推流目标 <small>同账号 · 示例</small></span>
      <NativeSelect aria-label="推流目标" value={selection} disabled={unavailable || pending || !targets.length} onChange={event => bind(event.target.value)}>
        <NativeSelectOption value="" disabled>{unavailable ? '请先登录' : phase === 'loading' ? '正在读取目标…' : !targets.length ? '暂无可选目标' : '选择推理节点（选中即关联）'}</NativeSelectOption>
        {selection && !targets.some(item => item.id === selection) && <NativeSelectOption value={selection}>已关联（目标不在当前列表里）</NativeSelectOption>}
        {targets.map(item => <NativeSelectOption key={item.id} value={item.id}>{displayName(item)}</NativeSelectOption>)}
      </NativeSelect>
    </label>
    <div className="push-target-actions"><Button variant="outline" disabled={pending || unavailable} onClick={() => refresh(true)} title="刷新同账号目标与关联关系（示例）"><RefreshCw className={phase === 'loading' ? 'push-target-refreshing' : ''}/>刷新</Button>
      <Button variant="outline" disabled={!boundTarget || pending || unavailable} onClick={() => setConfirmUnbind(true)}><Link2Off/>解除关联</Button></div>
    <Dialog open={confirmUnbind} onOpenChange={setConfirmUnbind}><DialogContent><DialogHeader><DialogTitle>解除推流目标关联？</DialogTitle><DialogDescription>将解除本节点与「{targetName}」的关联。本节点所有本地来源都会停止推给该目标，来源身份、采集配置及参数保留。这是示例操作，不会修改真实设备。</DialogDescription></DialogHeader><DialogFooter><Button autoFocus variant="outline" onClick={() => setConfirmUnbind(false)}>取消</Button><Button variant="destructive" onClick={unbind}>确认解除推流关联</Button></DialogFooter></DialogContent></Dialog>
  </div>;
}
