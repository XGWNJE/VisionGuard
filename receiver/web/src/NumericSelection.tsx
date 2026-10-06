import { Minus, Plus } from 'lucide-react';

/** Finite settings remain selectable without reducing their supported range. */
export function NumericSelection({id, label, value, min, max, step, disabled, onChange, kind}: {
  id: string; label: string; value: string; min: number; max: number; step: number;
  disabled: boolean; onChange: (value: string) => void; kind: string;
}) {
  const number = Number(value);
  const selected = Number.isFinite(number) ? Math.min(max, Math.max(min, number)) : min;
  const percent = kind === 'confidence';
  const display = percent ? `${Math.round(selected * 100)}%` : `${selected}${kind === 'cooldown' ? ' 秒' : ' FPS'}`;
  function choose(next: number) { onChange(String(Number(Math.min(max, Math.max(min, next)).toFixed(8)))); }
  if (kind === 'targetSamplingRate') return <div className="choice-group" role="group" aria-label={label}>
    {[1, 2, 3, 4, 5].map(fps => <button key={fps} id={fps === 1 ? id : undefined} autoFocus={fps === selected} type="button" className="choice" aria-pressed={number === fps} disabled={disabled} onClick={() => choose(fps)}>{fps} FPS</button>)}
  </div>;
  const range = <div className="range-control">
    <output htmlFor={id} className="setting-value">{display}</output>
    <div className="range-adjust"><button type="button" className="icon-button" aria-label={`减少${label}`} disabled={disabled || selected <= min} onClick={() => choose(selected - step)}><Minus size={20}/></button>
      <input autoFocus={kind !== 'cooldown'} id={id} aria-label={label} type="range" min={min} max={max} step={step} value={selected} disabled={disabled} onChange={event => choose(Number(event.target.value))}/>
      <button type="button" className="icon-button" aria-label={`增加${label}`} disabled={disabled || selected >= max} onClick={() => choose(selected + step)}><Plus size={20}/></button></div>
    <small>{percent ? `${Math.round(min * 100)}–${Math.round(max * 100)}%，每次调整 ${Math.round(step * 100)}%` : `${min}–${max} 秒，每次调整 ${step} 秒`}</small>
  </div>;
  return kind === 'cooldown' ? <><div className="choice-group" role="group" aria-label="常用冷却时间">{[5, 10, 30, 60].map(seconds => <button key={seconds} autoFocus={seconds === 5} type="button" className="choice" aria-pressed={number === seconds} disabled={disabled} onClick={() => choose(seconds)}>{seconds} 秒</button>)}</div><details className="range-more"><summary>完整范围 · 当前 {display}</summary>{range}</details></> : range;
}
