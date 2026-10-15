import React, {createContext, useCallback, useContext, useEffect, useMemo, useRef, useState} from 'react';

type Draft = {dirty:boolean;canSave:boolean;pending:boolean;save:()=>void;discard:()=>void};
type Guard = {register:(key:string,read:()=>Draft)=>()=>void;changed:()=>void;navigate:(action:()=>void)=>void};
const Context=createContext<Guard|null>(null);

/** Local navigation only: execution still uses each field's existing command and receipt. */
export function DraftNavigation({children}:{children:React.ReactNode}) {
  const entries=useRef(new Map<string,()=>Draft>());
  const [revision,update]=useState(0), [leaving,setLeaving]=useState<null|(()=>void)>(null), [saving,setSaving]=useState(false);
  const changed=useCallback(()=>update(n=>n+1),[]);
  const register=useCallback((key:string,read:()=>Draft)=>{entries.current.set(key,read);changed();return()=>{entries.current.delete(key);changed();};},[changed]);
  const dirty=Array.from(entries.current.values()).map(read=>read()).filter(draft=>draft.dirty);
  const navigate=useCallback((action:()=>void)=>{
    if(Array.from(entries.current.values()).some(read=>read().dirty)){setSaving(false);setLeaving(()=>action);}else action();
  },[]);
  const guard=useMemo(()=>({register,changed,navigate}),[register,changed,navigate]);
  useEffect(()=>{
    if(!saving||!leaving)return;
    if(!dirty.length){setLeaving(null);setSaving(false);leaving();}
  },[revision,saving,leaving]);
  useEffect(()=>{const warn=(event:BeforeUnloadEvent)=>{if(Array.from(entries.current.values()).some(read=>read().dirty)){event.preventDefault();event.returnValue='';}};window.addEventListener('beforeunload',warn);return()=>window.removeEventListener('beforeunload',warn);},[]);
  const dialog=useRef<HTMLDialogElement>(null);
  useEffect(()=>{if(leaving&&!dialog.current?.open)dialog.current?.showModal();},[leaving]);
  return <Context.Provider value={guard}>{children}{leaving&&<dialog ref={dialog} aria-labelledby="draft-navigation-title" onCancel={event=>{event.preventDefault();setLeaving(null);setSaving(false);}}><div className="dialog-body"><h2 id="draft-navigation-title">保留修改？</h2><p>检测规则尚未保存。保存后将等待执行端回执，失败时保留当前页面和草稿。</p><div className="dialog-actions"><button className="button secondary" autoFocus onClick={()=>{setLeaving(null);setSaving(false);}}>继续编辑</button><button className="button secondary" disabled={dirty.some(d=>d.pending)} onClick={()=>{dirty.forEach(d=>d.discard());const action=leaving;setLeaving(null);action();}}>放弃修改</button><button className="button" disabled={!dirty.length||dirty.some(d=>!d.canSave)} onClick={()=>{setSaving(true);dirty.forEach(d=>d.save());}}>{dirty.some(d=>d.pending)?'等待执行结果…':'保存并离开'}</button></div>{dirty.some(d=>!d.canSave)&&<p className="subtle">运行中、离线、保存中或输入无效时，请继续编辑并处理后再保存。</p>}</div></dialog>}</Context.Provider>;
}
export function useDraftNavigation() {return useContext(Context)?.navigate ?? ((action:()=>void)=>action());}
export function useNavigationDraft(key:string, draft:Draft) {
  const guard=useContext(Context), read=useRef(draft);read.current=draft;
  useEffect(()=>guard?.register(key,()=>read.current),[guard,key]);
  useEffect(()=>guard?.changed(),[guard,draft.dirty,draft.canSave,draft.pending]);
}
