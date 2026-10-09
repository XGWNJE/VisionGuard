import { COCO } from './coco.mjs';

export const VERSION = '0.6.11';
export const LIMITS = Object.freeze({ confidence: [10, 95], fps: [1, 5], cooldown: [1, 300], name: 64, targets: 4096, sources: 16, maskEdge: .02 });
export const MODEL_KEYS = ['yolo26n_320', 'yolo26n_640', 'yolo26s_320', 'yolo26s_640', 'yolo26m_320', 'yolo26m_640'];
export const modelName = key => key.replace('yolo26', 'YOLO26 ').replace('_', ' · ');
export const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
export const bounded = (v, lo, hi) => clamp(Math.round(v), lo, hi);
export const validateName = value => !value.trim() ? '名称不能为空' : value.trim().length > 64 ? '最多 64 个 UTF-16 单元' : /[\u0000-\u001f]/.test(value.trim()) ? '名称不能包含控制字符' : null;
export function validateParams(p, models) {
  for (const [key, range] of [['confidence', LIMITS.confidence], ['fps', LIMITS.fps], ['cooldown', LIMITS.cooldown]]) {
    if (!Number.isInteger(p[key]) || p[key] < range[0] || p[key] > range[1]) return `${{ confidence:'置信度', fps:'采样频率', cooldown:'冷却时间' }[key]}需为 ${range[0]}–${range[1]} 的整数`;
  }
  if (!models.some(m => m.key === p.model && m.downloaded)) return '当前模型未下载';
  if (!Array.isArray(p.targets) || p.targets.length === 0) return '至少选择一个检测目标';
  if (p.targets.length > 256 || p.targets.join(',').length > 4096) return '目标集合超出契约上限';
  if (p.targets.some(key => !COCO.some(c => c.key === key))) return '存在不支持的检测目标';
  return null;
}
export function validateMask(r) {
  return [r.x, r.y, r.width, r.height].every(Number.isFinite) && r.x >= 0 && r.y >= 0 && r.width >= .02 && r.height >= .02 && r.x + r.width <= 1.0001 && r.y + r.height <= 1.0001;
}
export function normalizeMask(x1,y1,x2,y2) {
  const x=clamp(Math.min(x1,x2),0,.98), y=clamp(Math.min(y1,y2),0,.98);
  return { x, y, width:clamp(Math.abs(x2-x1),.02,1-x), height:clamp(Math.abs(y2-y1),.02,1-y) };
}
export function coldError(source) { return source?.running || source?.busy ? '请先停止该来源；参数、镜头和遮罩是冷改动' : null; }
export const statusText = source => source.busy ? '处理中…' : source.error ? source.error : source.running ? '推理中' : source.ready ? '已停止' : '等待绑定';
export const statusTone = source => source.error ? 'error' : source.running ? 'success' : source.ready ? 'neutral' : 'warning';
export function createSeed() {
  const params={ confidence:65, fps:3, cooldown:30, model:'yolo26n_320', targets:['person'] };
  return {
    sources:[
      {id:'camera',name:'本机后置',type:'local',lens:'back',running:true,ready:true,error:null,busy:false,params:structuredClone(params),masks:[],hasFrame:true,backend:'NNAPI'},
      {id:'warehouse',name:'仓库入口',type:'remote',lens:null,running:false,ready:true,error:null,busy:false,params:structuredClone(params),masks:[],hasFrame:true,backend:'CPU'},
      {id:'corridor',name:'走廊',type:'remote',lens:null,running:false,ready:false,error:'连接中断',busy:false,params:structuredClone(params),masks:[],hasFrame:false,backend:null},
    ],
    models:MODEL_KEYS.map((key,i)=>({key,downloaded:i===0||i===2,progress:null})),
  };
}
export function applyScenario(name) {
  const seed=createSeed();
  if(name==='empty')seed.sources=[];
  if(name==='stopped')seed.sources.forEach(s=>s.running=false);
  if(name==='unready'){seed.sources[0].running=false;seed.sources[0].ready=false;seed.sources[0].error='相机权限未授予';seed.sources[0].hasFrame=false;}
  if(name==='offline')seed.sources.forEach(s=>{if(s.type==='remote'){s.running=false;s.ready=false;s.error='连接中断';s.hasFrame=false;}});
  if(name==='long')seed.sources[0].name='几何空间'.repeat(16);
  if(name==='failed'){seed.sources[0].running=false;seed.sources[0].error='模型会话创建失败';seed.sources[0].backend='CPU';}
  return seed;
}
