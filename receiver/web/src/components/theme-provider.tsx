import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';

export type Theme = 'dark' | 'light' | 'system';
type ThemeState = { theme: Theme; setTheme: (theme: Theme) => void; error: string };
const ThemeContext = createContext<ThemeState | undefined>(undefined);
export function readTheme(value: string | null): Theme { return value === 'dark' || value === 'light' ? value : 'system'; }

// shadcn/ui Vite ThemeProvider: CSS variables and the root .dark class.
export function ThemeProvider({ children, defaultTheme = 'system', storageKey = 'vg.appearance' }: { children: ReactNode; defaultTheme?: Theme; storageKey?: string }) {
  const [theme, updateTheme] = useState<Theme>(() => { try { return readTheme(localStorage.getItem(storageKey)); } catch { return defaultTheme; } });
  const [error, setError] = useState('');
  useEffect(() => {
    const query = window.matchMedia('(prefers-color-scheme: dark)');
    const apply = () => { const root = document.documentElement; root.classList.remove('light', 'dark'); root.classList.add(theme === 'system' ? query.matches ? 'dark' : 'light' : theme); };
    apply(); query.addEventListener('change', apply);
    return () => query.removeEventListener('change', apply);
  }, [theme]);
  useEffect(() => { const changed = (event: StorageEvent) => { if (event.key === storageKey) updateTheme(readTheme(event.newValue)); }; window.addEventListener('storage', changed); return () => window.removeEventListener('storage', changed); }, [storageKey]);
  const setTheme = (value: Theme) => { updateTheme(value); try { localStorage.setItem(storageKey, value); setError(''); } catch { setError('外观无法保存'); } };
  return <ThemeContext.Provider value={{ theme, setTheme, error }}>{children}</ThemeContext.Provider>;
}
export function useTheme() { const context = useContext(ThemeContext); if (!context) throw new Error('ThemeProvider is required'); return context; }
