import { useRef } from 'react';
import { NODE_DIRECTORY_DEFAULT_WIDTH, NODE_DIRECTORY_MIN_WIDTH, NODE_DIRECTORY_MAX_WIDTH, clampNodeDirectoryWidth, nodeDirectoryKeyStep, saveNodeDirectoryWidth } from './nodeDirectory';

/** 目录与详情之间的分隔条：拖动、方向键微调、双击复位，宽度记录在本机。 */
export function NodeDirectoryResizer({width,container,onChange}:{
  width:number; container:React.RefObject<HTMLElement|null>; onChange:(width:number)=>void;
}) {
  const drag=useRef<{startX:number;startWidth:number;containerWidth:number}|null>(null);
  const containerWidth=()=>container.current?.getBoundingClientRect().width ?? 0;
  return <div className="master-resizer" role="separator" aria-orientation="vertical" aria-label="调整节点目录宽度"
    aria-valuenow={width} aria-valuemin={NODE_DIRECTORY_MIN_WIDTH} aria-valuemax={NODE_DIRECTORY_MAX_WIDTH} tabIndex={0}
    onPointerDown={event=>{
      event.preventDefault();
      drag.current={startX:event.clientX,startWidth:width,containerWidth:containerWidth()};
      event.currentTarget.setPointerCapture(event.pointerId);
    }}
    onPointerMove={event=>{
      const state=drag.current; if(!state)return;
      onChange(clampNodeDirectoryWidth(state.startWidth+event.clientX-state.startX,state.containerWidth));
    }}
    onPointerUp={event=>{
      if(!drag.current)return;
      drag.current=null;
      event.currentTarget.releasePointerCapture(event.pointerId);
      saveNodeDirectoryWidth(window.localStorage,width);
    }}
    onKeyDown={event=>{
      const step=nodeDirectoryKeyStep(event.key,event.shiftKey); if(step===null)return;
      event.preventDefault();
      const next=clampNodeDirectoryWidth(width+step,containerWidth());
      onChange(next); saveNodeDirectoryWidth(window.localStorage,next);
    }}
    onDoubleClick={()=>{ onChange(NODE_DIRECTORY_DEFAULT_WIDTH); saveNodeDirectoryWidth(window.localStorage,NODE_DIRECTORY_DEFAULT_WIDTH); }}
  ><span aria-hidden="true"/></div>;
}
