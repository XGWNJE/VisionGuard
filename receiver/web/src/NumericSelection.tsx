import { Minus, Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Slider } from '@/components/ui/slider';
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group';

export function NumericSelection({ id, label, value, min, max, step, disabled, onChange, kind }: { id: string; label: string; value: string; min: number; max: number; step: number; disabled: boolean; onChange: (value: string) => void; kind: string }) {
  const number = Number(value);
  const selected = Number.isFinite(number) ? Math.min(max, Math.max(min, number)) : min;
  function choose(next: number) { onChange(String(Number(Math.min(max, Math.max(min, next)).toFixed(8)))); }
  if (kind === 'targetSamplingRate') return <ToggleGroup id={id} type="single" variant="outline" value={String(number)} disabled={disabled} onValueChange={next => { if (next) choose(Number(next)); }} aria-label={label}>{[1, 2, 3, 4, 5].map(fps => <ToggleGroupItem key={fps} value={String(fps)}>{fps} FPS</ToggleGroupItem>)}</ToggleGroup>;
  return <div className="range-control"><output htmlFor={id}>{kind === 'confidence' ? `${Math.round(selected * 100)}%` : `${selected} 秒`}</output><div className="range-adjust"><Button type="button" variant="outline" size="icon" aria-label={`减少${label}`} disabled={disabled || selected <= min} onClick={() => choose(selected - step)}><Minus /></Button><Slider id={id} aria-label={label} min={min} max={max} step={step} value={[selected]} disabled={disabled} onValueChange={next => choose(next[0])}/><Button type="button" variant="outline" size="icon" aria-label={`增加${label}`} disabled={disabled || selected >= max} onClick={() => choose(selected + step)}><Plus /></Button></div>{kind === 'cooldown' && <ToggleGroup type="single" variant="outline" value={String(number)} disabled={disabled} onValueChange={next => { if (next) choose(Number(next)); }} aria-label="常用冷却时间">{[5, 10, 30, 60].map(seconds => <ToggleGroupItem key={seconds} value={String(seconds)}>{seconds} 秒</ToggleGroupItem>)}</ToggleGroup>}</div>;
}
