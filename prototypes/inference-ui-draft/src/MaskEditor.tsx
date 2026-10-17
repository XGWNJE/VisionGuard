import {useEffect, useId, useRef, useState, type PointerEvent} from 'react';
import {MousePointer2, Plus, ScanLine, Trash2, Undo2} from 'lucide-react';
import {Button} from '@/components/ui/button';
import {Input} from '@/components/ui/input';
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from '@/components/ui/dialog';
import {Preview} from './Preview';
import './MaskEditor.css';

/** Coordinates are relative to the complete source frame, never CSS pixels. */
export type Rect = {x:number; y:number; width:number; height:number};
type Point = {x:number; y:number};
type Snapshot = {rects:Rect[]; selected:number|null};
type Gesture = {pointerId:number; start:Point; original:Rect[]; index:number|null; rect:Rect|null};
type Coordinate = keyof Rect;
const MIN_SIZE = 0.02;
const copy = (rects:Rect[]) => rects.map(rect => ({...rect}));
const clamp = (value:number, min:number, max:number) => Math.max(min, Math.min(max, value));
const round = (value:number) => Math.round(value * 10000) / 10000;
const equal = (a:Rect[], b:Rect[]) => JSON.stringify(a) === JSON.stringify(b);

function normalize(rect:Rect):Rect {
  const width = round(clamp(Number.isFinite(rect.width) ? rect.width : MIN_SIZE, MIN_SIZE, 1));
  const height = round(clamp(Number.isFinite(rect.height) ? rect.height : MIN_SIZE, MIN_SIZE, 1));
  return {
    x:round(clamp(Number.isFinite(rect.x) ? rect.x : 0, 0, 1 - width)),
    y:round(clamp(Number.isFinite(rect.y) ? rect.y : 0, 0, 1 - height)),
    width, height,
  };
}

function CoordinateInput({label, value, min, max, onCommit}: {
  label:string; value:number; min:number; max:number; onCommit:(value:number)=>void;
}) {
  const [text, setText] = useState(value.toFixed(4));
  useEffect(() => setText(value.toFixed(4)), [value]);
  const commit = () => {
    const parsed = Number(text);
    if (!text.trim() || !Number.isFinite(parsed)) {
      setText(value.toFixed(4));
      return;
    }
    const next = round(clamp(parsed, min, max));
    setText(next.toFixed(4));
    onCommit(next);
  };
  return <label className="mask-editor-field"><span>{label}</span>
    <Input type="number" inputMode="decimal" step="0.01" min={round(min)} max={round(max)}
      aria-label={`遮罩${label}（归一化坐标）`} value={text}
      onChange={event => setText(event.target.value)} onBlur={commit}
      onKeyDown={event => {
        if (event.key === 'Enter') {event.preventDefault(); event.currentTarget.blur();}
        if (event.key === 'Escape') {event.stopPropagation(); setText(value.toFixed(4));}
      }}/>
  </label>;
}

export function MaskEditor({value, onChange}: {value:Rect[]; onChange:(rects:Rect[])=>void}) {
  const [selected, setSelected] = useState<number|null>(value.length ? 0 : null);
  const [tool, setTool] = useState<'draw'|'select'>(value.length ? 'select' : 'draw');
  const [preview, setPreview] = useState<Rect[]|null>(null);
  const [undoCount, setUndoCount] = useState(0);
  const [confirmClear, setConfirmClear] = useState(false);
  const [notice, storeNotice] = useState('');
  const [noticeRevision,setNoticeRevision]=useState(0);
  const setNotice=(message:string)=>{storeNotice(message);setNoticeRevision(n=>n+1)};
  useEffect(()=>{if(!notice)return;const timer=setTimeout(()=>storeNotice(''),3200);return()=>clearTimeout(timer)},[notice,noticeRevision]);
  const history = useRef<Snapshot[]>([]);
  const gesture = useRef<Gesture|null>(null);
  const valueRef = useRef(value);
  const canvasRef = useRef<HTMLDivElement>(null);
  const helpId = useId();
  const rects = preview || value;
  const selectedRect = selected === null ? null : rects[selected];
  valueRef.current = value;

  useEffect(() => {
    if (selected !== null && selected >= value.length) setSelected(value.length ? value.length - 1 : null);
  }, [selected, value.length]);

  const commit = (next:Rect[], nextSelected:number|null = selected) => {
    const normalized = next.map(normalize);
    if (equal(valueRef.current, normalized)) return;
    history.current.push({rects:copy(valueRef.current), selected});
    valueRef.current = normalized;
    setUndoCount(history.current.length);
    setSelected(nextSelected);
    onChange(normalized);
  };

  const undo = () => {
    const snapshot = history.current.pop();
    if (!snapshot) return;
    valueRef.current = copy(snapshot.rects);
    onChange(copy(snapshot.rects));
    setSelected(snapshot.selected);
    setUndoCount(history.current.length);
    setNotice('已撤销上一步修改。');
  };

  const removeSelected = () => {
    if (selected === null) return;
    const next = value.filter((_, index) => index !== selected);
    commit(next, next.length ? Math.min(selected, next.length - 1) : null);
    setNotice('已删除所选区域，可撤销。');
  };

  const point = (event:PointerEvent<HTMLDivElement>):Point => {
    const bounds = event.currentTarget.getBoundingClientRect();
    return {
      x:clamp((event.clientX - bounds.left) / bounds.width, 0, 1),
      y:clamp((event.clientY - bounds.top) / bounds.height, 0, 1),
    };
  };

  const updateGesture = (event:PointerEvent<HTMLDivElement>) => {
    const active = gesture.current;
    if (!active || active.pointerId !== event.pointerId) return;
    const position = point(event);
    if (active.index === null) {
      active.rect = {
        x:Math.min(position.x, active.start.x), y:Math.min(position.y, active.start.y),
        width:Math.abs(position.x - active.start.x), height:Math.abs(position.y - active.start.y),
      };
      setPreview([...active.original, active.rect]);
    } else {
      const before = active.original[active.index];
      active.rect = normalize({...before,
        x:clamp(before.x + position.x - active.start.x, 0, 1 - before.width),
        y:clamp(before.y + position.y - active.start.y, 0, 1 - before.height),
      });
      setPreview(active.original.map((rect, index) => index === active.index ? active.rect! : rect));
    }
  };

  const cancelGesture = () => {
    const active = gesture.current;
    gesture.current = null;
    setPreview(null);
    if (active && canvasRef.current?.hasPointerCapture(active.pointerId)) canvasRef.current.releasePointerCapture(active.pointerId);
  };

  const startGesture = (event:PointerEvent<HTMLDivElement>) => {
    if (!event.isPrimary || event.button !== 0 || gesture.current) return;
    const target = (event.target as HTMLElement).closest<HTMLElement>('[data-mask-index]');
    const index = tool === 'select' && target ? Number(target.dataset.maskIndex) : null;
    if (tool === 'select' && index === null) {setSelected(null); return;}
    event.preventDefault();
    event.currentTarget.focus();
    event.currentTarget.setPointerCapture(event.pointerId);
    gesture.current = {pointerId:event.pointerId, start:point(event), original:copy(value), index, rect:null};
    if (index !== null) setSelected(index);
    setNotice('');
  };

  const finishGesture = (event:PointerEvent<HTMLDivElement>) => {
    const active = gesture.current;
    if (!active || active.pointerId !== event.pointerId) return;
    updateGesture(event);
    if (active.rect) {
      if (active.index === null) {
        const bounds = event.currentTarget.getBoundingClientRect();
        // Ignore accidental taps, then use the native editor's minimum relative size.
        if (active.rect.width * bounds.width >= 4 && active.rect.height * bounds.height >= 4) {
          commit([...active.original, normalize(active.rect)], active.original.length);
          setTool('select');
          setNotice('已添加区域，可拖动移动或输入坐标调整。');
        } else setNotice('请拖出矩形区域；单击不会添加遮罩。');
      } else commit(active.original.map((rect, index) => index === active.index ? active.rect! : rect), active.index);
    }
    cancelGesture();
  };

  const editCoordinate = (coordinate:Coordinate, number:number) => {
    if (selected === null) return;
    commit(valueRef.current.map((rect, index) => index === selected ? {...rect, [coordinate]:number} : rect));
    setNotice('区域坐标已更新，可撤销。');
  };

  return <div className="mask-editor">
    <div className="mask-editor-toolbar">
      <div className="mask-editor-tools" role="group" aria-label="遮罩编辑工具">
        <Button type="button" variant={tool === 'draw' ? 'secondary' : 'outline'} aria-pressed={tool === 'draw'} onClick={() => setTool('draw')}><Plus/>绘制矩形</Button>
        <Button type="button" variant={tool === 'select' ? 'secondary' : 'outline'} aria-pressed={tool === 'select'} onClick={() => setTool('select')}><MousePointer2/>选择 / 移动</Button>
      </div>
      <div className="mask-editor-tools">
        <Button type="button" variant="ghost" disabled={!undoCount} onClick={undo}><Undo2/>撤销</Button>
        <Button type="button" variant="ghost" disabled={!value.length} onClick={() => setConfirmClear(true)}><Trash2/>清空</Button>
      </div>
    </div>
    <div className="mask-editor-frame">
      <div className="mask-editor-background" aria-hidden="true"><Preview/></div>
      <div ref={canvasRef} className={`mask-editor-canvas is-${tool}`} tabIndex={0} role="group"
        aria-label="遮罩编辑画布" aria-describedby={helpId}
        onPointerDown={startGesture} onPointerMove={updateGesture} onPointerUp={finishGesture}
        onPointerCancel={cancelGesture} onLostPointerCapture={() => {if (gesture.current) cancelGesture();}}
        onKeyDown={event => {
          if (event.key === 'Escape' && gesture.current) {event.preventDefault(); event.stopPropagation(); cancelGesture();}
          if ((event.key === 'Delete' || event.key === 'Backspace') && selected !== null) {event.preventDefault(); removeSelected();}
          if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') {event.preventDefault(); undo();}
        }}>
        {rects.map((rect, index) => <div key={index} data-mask-index={index}
          className={`mask-editor-rect${index === selected ? ' is-selected' : ''}${preview && index === value.length ? ' is-drawing' : ''}`}
          style={{left:`${rect.x * 100}%`, top:`${rect.y * 100}%`, width:`${rect.width * 100}%`, height:`${rect.height * 100}%`}}>
          <span>{String(index + 1).padStart(2, '0')}</span>
        </div>)}
      </div>
    </div>
    <p id={helpId} className="mask-editor-hint">{tool === 'draw' ? '鼠标或单指拖动绘制矩形；可叠加多个区域。' : '点击区域后拖动位置；也可在下方选择并调整坐标。'} 黑色区域在推理前遮黑，报警图片同样遮黑。</p>
    <div className="mask-editor-properties">
      <div className="mask-editor-region-heading"><span>区域 <strong>{value.length}</strong></span><span>坐标 0–1 · 最小宽高 0.02</span></div>
      <div className="mask-editor-region-list" role="group" aria-label="选择遮罩区域">
        {value.map((_, index) => <Button key={index} type="button" size="sm" variant={selected === index ? 'secondary' : 'outline'} aria-pressed={selected === index}
          onClick={() => {setSelected(index); setTool('select');}}><ScanLine/>区域 {String(index + 1).padStart(2, '0')}</Button>)}
        {!value.length && <p>暂无区域，点击「绘制矩形」后在画面上拖动。</p>}
      </div>
      {selectedRect && selected !== null ? <div className="mask-editor-coordinate-row" key={selected}>
        <CoordinateInput label="X" value={selectedRect.x} min={0} max={1 - selectedRect.width} onCommit={number => editCoordinate('x', number)}/>
        <CoordinateInput label="Y" value={selectedRect.y} min={0} max={1 - selectedRect.height} onCommit={number => editCoordinate('y', number)}/>
        <CoordinateInput label="宽度" value={selectedRect.width} min={MIN_SIZE} max={1 - selectedRect.x} onCommit={number => editCoordinate('width', number)}/>
        <CoordinateInput label="高度" value={selectedRect.height} min={MIN_SIZE} max={1 - selectedRect.y} onCommit={number => editCoordinate('height', number)}/>
        <Button type="button" variant="destructive" onClick={removeSelected}><Trash2/>删除所选</Button>
      </div> : value.length > 0 && <p className="mask-editor-selection-hint">选择一个区域后可调整位置和尺寸。</p>}
    </div>
    <span className="mask-editor-feedback" role="status" aria-live="polite">{notice}</span>
    <Dialog open={confirmClear} onOpenChange={setConfirmClear}>
      <DialogContent><DialogHeader><DialogTitle>清空全部遮罩？</DialogTitle>
        <DialogDescription>将移除草稿中的全部 {value.length} 个区域。此操作可撤销；只有保存遮罩后，来源配置才会改变。</DialogDescription>
      </DialogHeader><DialogFooter>
        <Button type="button" autoFocus variant="outline" onClick={() => setConfirmClear(false)}>取消</Button>
        <Button type="button" variant="destructive" onClick={() => {commit([], null); setConfirmClear(false); setTool('draw'); setNotice('已清空草稿区域，可撤销。');}}>清空遮罩</Button>
      </DialogFooter></DialogContent>
    </Dialog>
  </div>;
}
