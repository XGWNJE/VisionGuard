import { useEffect, useState } from 'react';
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
export function AppearanceSelector({ preference }: { preference: ReturnType<typeof useAppearance> }) {
  return <label className="appearance-selector">外观<select aria-label="外观" value={preference.mode} onChange={e => preference.select(e.target.value as Appearance)}><option value="system">跟随系统</option><option value="light">浅色</option><option value="dark">深色</option></select>{preference.error && <small role="status">{preference.error}</small>}</label>;
}
