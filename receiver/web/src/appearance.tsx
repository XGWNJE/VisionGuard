import { useEffect, useState } from 'react';
import { Monitor, Moon, Sun } from 'lucide-react';
export type Appearance = 'system' | 'light' | 'dark';
export function readAppearance(value: string | null): Appearance { return value === 'light' || value === 'dark' ? value : 'system'; }
export function useAppearance() {
  const [mode, setMode] = useState<Appearance>(() => { try { return readAppearance(localStorage.getItem('vg.appearance')); } catch { return 'system'; } });
  const [error, setError] = useState('');
  useEffect(() => {
    const query = typeof matchMedia === 'function' ? matchMedia('(prefers-color-scheme: dark)') : null;
    const apply = () => { document.documentElement.dataset.theme = mode === 'system' ? query?.matches ? 'dark' : 'light' : mode; };
    apply(); query?.addEventListener('change', apply);
    return () => query?.removeEventListener('change', apply);
  }, [mode]);
  useEffect(() => { const changed = (e: StorageEvent) => { if (e.key === 'vg.appearance') setMode(readAppearance(e.newValue)); }; window.addEventListener('storage', changed); return () => window.removeEventListener('storage', changed); }, []);
  return { mode, error, select(value: Appearance) { setMode(value); try { localStorage.setItem('vg.appearance', value); setError(''); } catch { setError('外观已切换，当前浏览器无法保存选择'); } } };
}
export function AppearanceSelector({ preference, showIcons = true }: { preference: ReturnType<typeof useAppearance>; showIcons?: boolean }) {
  return <div className="appearance-selector"><div className="choice-group appearance-choices" role="group" aria-label="外观">{([{value:'system',label:'跟随系统',Icon:Monitor},{value:'light',label:'浅色',Icon:Sun},{value:'dark',label:'深色',Icon:Moon}] as const).map(({value,label,Icon})=><button type="button" key={value} className="choice" aria-pressed={preference.mode===value} onClick={()=>preference.select(value)}>{showIcons && <Icon size={16} aria-hidden="true"/>}{label}</button>)}</div>{preference.error && <small role="status">{preference.error}</small>}</div>;
}
