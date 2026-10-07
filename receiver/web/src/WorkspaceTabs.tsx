import { useRef } from 'react';

/** Manual-activation tabs: arrows move focus, Enter/Space selects a workspace. */
export function WorkspaceTabs<T extends string>({id,label,tabs,value,onChange}: {
  id:string; label:string; tabs:readonly {value:T;label:string}[]; value:T; onChange:(value:T)=>void;
}) {
  const ref=useRef<HTMLDivElement>(null);
  return <div ref={ref} className="workspace-tabs" role="tablist" aria-label={label} onKeyDown={event=>{
    const buttons=Array.from(ref.current?.querySelectorAll<HTMLButtonElement>('[role="tab"]') ?? []);
    const index=buttons.indexOf(document.activeElement as HTMLButtonElement);
    if(index<0)return;
    const next=event.key==='ArrowRight'?(index+1)%buttons.length:event.key==='ArrowLeft'?(index+buttons.length-1)%buttons.length:event.key==='Home'?0:event.key==='End'?buttons.length-1:-1;
    if(next>=0){event.preventDefault();buttons[next]?.focus();}
  }}>{tabs.map(tab=><button key={tab.value} type="button" role="tab" id={`${id}-tab-${tab.value}`} aria-controls={`${id}-panel-${tab.value}`} aria-selected={value===tab.value} tabIndex={value===tab.value?0:-1} onClick={()=>onChange(tab.value)}>{tab.label}</button>)}</div>;
}
