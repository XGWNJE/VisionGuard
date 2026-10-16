import { Monitor, Moon, Sun } from 'lucide-react';
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group';
import { readTheme, useTheme, type Theme } from '@/components/theme-provider';
export type Appearance = Theme;
export const readAppearance = readTheme;
export function useAppearance() { const { theme, setTheme, error } = useTheme(); return { mode: theme, select: setTheme, error }; }
export function AppearanceSelector({ preference, showIcons = true }: { preference: ReturnType<typeof useAppearance>; showIcons?: boolean }) {
  return <div className="appearance-selector"><ToggleGroup type="single" variant="outline" value={preference.mode} onValueChange={value => { if (value) preference.select(value as Appearance); }} aria-label="外观">
    {([{ value: 'system', label: '跟随系统', Icon: Monitor }, { value: 'light', label: '浅色', Icon: Sun }, { value: 'dark', label: '深色', Icon: Moon }] as const).map(({ value, label, Icon }) => <ToggleGroupItem key={value} value={value} aria-label={label}>{showIcons && <Icon aria-hidden="true" />}{label}</ToggleGroupItem>)}
  </ToggleGroup>{preference.error && <small role="status">{preference.error}</small>}</div>;
}
