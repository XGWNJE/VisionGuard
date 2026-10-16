
import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { AlertDialog, AlertDialogContent, AlertDialogHeader, AlertDialogTitle, AlertDialogDescription, AlertDialogFooter } from '@/components/ui/alert-dialog';
type Draft = {
    dirty: boolean;
    canSave: boolean;
    pending: boolean;
    save: () => void;
    discard: () => void;
};
type Guard = {
    register: (key: string, read: () => Draft) => () => void;
    changed: () => void;
    navigate: (action: () => void) => void;
};
const Context = createContext<Guard | null>(null);
export function DraftNavigation({ children }: {
    children: React.ReactNode;
}) {
    const entries = useRef(new Map<string, () => Draft>());
    const [revision, update] = useState(0), [leaving, setLeaving] = useState<null | (() => void)>(null), [saving, setSaving] = useState(false);
    const changed = useCallback(() => update(n => n + 1), []);
    const register = useCallback((key: string, read: () => Draft) => { entries.current.set(key, read); changed(); return () => { entries.current.delete(key); changed(); }; }, [changed]);
    const dirty = Array.from(entries.current.values()).map(read => read()).filter(draft => draft.dirty);
    const navigate = useCallback((action: () => void) => { if (Array.from(entries.current.values()).some(read => read().dirty)) {
        setSaving(false);
        setLeaving(() => action);
    }
    else
        action(); }, []);
    const guard = useMemo(() => ({ register, changed, navigate }), [register, changed, navigate]);
    useEffect(() => { if (saving && leaving && !dirty.length) {
        setLeaving(null);
        setSaving(false);
        leaving();
    } }, [revision, saving, leaving]);
    useEffect(() => { const warn = (event: BeforeUnloadEvent) => { if (Array.from(entries.current.values()).some(read => read().dirty)) {
        event.preventDefault();
        event.returnValue = '';
    } }; window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn); }, []);
    return <Context.Provider value={guard}>{children}<AlertDialog open={!!leaving} onOpenChange={open => { if (!open) {
        setLeaving(null);
        setSaving(false);
    } }}><AlertDialogContent><AlertDialogHeader><AlertDialogTitle>保留修改？</AlertDialogTitle><AlertDialogDescription>检测规则尚未保存</AlertDialogDescription></AlertDialogHeader><AlertDialogFooter><Button variant="outline" autoFocus onClick={() => { setLeaving(null); setSaving(false); }}>继续编辑</Button><Button variant="outline" disabled={dirty.some(d => d.pending)} onClick={() => { dirty.forEach(d => d.discard()); const action = leaving; setLeaving(null); action?.(); }}>放弃修改</Button><Button disabled={!dirty.length || dirty.some(d => !d.canSave)} onClick={() => { setSaving(true); dirty.forEach(d => d.save()); }}>{dirty.some(d => d.pending) ? '等待执行结果…' : '保存并离开'}</Button></AlertDialogFooter></AlertDialogContent></AlertDialog></Context.Provider>;
}
export function useDraftNavigation() { return useContext(Context)?.navigate ?? ((action: () => void) => action()); }
export function useNavigationDraft(key: string, draft: Draft) {
    const guard = useContext(Context), read = useRef(draft);
    read.current = draft;
    useEffect(() => guard?.register(key, () => read.current), [guard, key]);
    useEffect(() => guard?.changed(), [guard, draft.dirty, draft.canSave, draft.pending]);
}
