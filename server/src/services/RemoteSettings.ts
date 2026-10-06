export const MAX_AUDIO_BYTES = 512 * 1024;
export const MAX_AUDIO_ENTRIES = 100;
export interface AudioEntry { id: string; name: string; mutable: boolean }
export interface RemoteSettings {
  cameraResolution?: '480p' | '720p'; cameraDimScreen?: boolean; cameraHidePreview?: boolean;
  soundLoopCount?: number; soundSelection?: string; audioEntries?: AudioEntry[]; audioPreview?: string;
  audioLibraryFull?: boolean;
}
const CAMERA_KEYS = ['cameraResolution', 'cameraDimScreen', 'cameraHidePreview'];
const SOUND_KEYS = ['soundLoopCount', 'soundSelection', 'audioRename', 'audioDelete', 'audioPreview', 'audioStopPreview', 'audioImport'];
export function remoteConfigRole(key: string): 'android-camera' | 'android-notifier' | undefined {
  return CAMERA_KEYS.includes(key) ? 'android-camera' : SOUND_KEYS.includes(key) ? 'android-notifier' : undefined;
}
export function validAudioId(value: unknown): value is string {
  return typeof value === 'string' && /^(system-default|system-current|silent|preset:[a-z0-9_]{1,64}|library:[a-f0-9]{64})$/.test(value);
}
function validName(value: unknown): value is string {
  return typeof value === 'string' && !!value.trim() && value.trim().length <= 64 && !/[\x00-\x1f]/.test(value);
}
export function sanitizeRemoteSettings(value: unknown, component: string): RemoteSettings | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const v = value as RemoteSettings;
  if (component === 'android-camera') return (v.cameraResolution === '480p' || v.cameraResolution === '720p') &&
    typeof v.cameraDimScreen === 'boolean' && typeof v.cameraHidePreview === 'boolean' ? {
      cameraResolution: v.cameraResolution, cameraDimScreen: v.cameraDimScreen, cameraHidePreview: v.cameraHidePreview } : undefined;
  if (component !== 'android-notifier' || !Number.isInteger(v.soundLoopCount) || v.soundLoopCount! < 1 || v.soundLoopCount! > 10 ||
      !validAudioId(v.soundSelection) || !Array.isArray(v.audioEntries) || v.audioEntries.length > MAX_AUDIO_ENTRIES + 16) return undefined;
  const entries: AudioEntry[] = [];
  for (const e of v.audioEntries) {
    if (!e || !validAudioId(e.id) || !validName(e.name) || typeof e.mutable !== 'boolean' || e.mutable !== e.id.startsWith('library:') || entries.some(x => x.id === e.id)) return undefined;
    entries.push({ id: e.id, name: e.name.trim(), mutable: e.mutable });
  }
  const libraryCount = entries.filter(e=>e.mutable).length;
  if (libraryCount > MAX_AUDIO_ENTRIES || !entries.some(e => e.id === v.soundSelection) || typeof v.audioPreview !== 'string' || v.audioPreview && !entries.some(e => e.id === v.audioPreview)) return undefined;
  return {soundLoopCount:v.soundLoopCount, soundSelection:v.soundSelection, audioEntries:entries, audioPreview:v.audioPreview, audioLibraryFull:!!v.audioLibraryFull || libraryCount >= MAX_AUDIO_ENTRIES};
}
export function validateRemoteConfig(key: string, raw: unknown, state: RemoteSettings | undefined, monitoring: boolean): {ok:true;value:string}|{ok:false;reason:string} {
  const fail = (reason:string): {ok:false;reason:string} => ({ok:false,reason});
  if (typeof raw !== 'string' || !state) return fail('节点未报告当前配置');
  if (key === 'cameraResolution') return monitoring ? fail('请先停止推流再修改规格') : ['480p','720p'].includes(raw) ? {ok:true,value:raw} : fail('规格只能选择 480p 或 720p');
  if (key === 'cameraDimScreen' || key === 'cameraHidePreview') return ['true','false'].includes(raw) ? {ok:true,value:raw} : fail('必须为布尔值');
  if (key === 'soundLoopCount') return /^(?:[1-9]|10)$/.test(raw) ? {ok:true,value:raw} : fail('播放次数须为 1–10 的整数');
  const find = (id:string) => state.audioEntries?.find(e=>e.id===id);
  if (key === 'soundSelection' || key === 'audioPreview') return validAudioId(raw) && find(raw) && (key !== 'audioPreview' || raw.startsWith('preset:') || raw.startsWith('library:')) ? {ok:true,value:raw} : fail('音频条目不存在或不支持试听');
  if (key === 'audioStopPreview') return raw === '' ? {ok:true,value:raw} : fail('停止试听不接受附加数据');
  if (key === 'audioDelete') return find(raw)?.mutable && state.soundSelection !== raw ? {ok:true,value:raw} : fail('只能删除未被选用的自定义音频');
  if (key === 'audioRename') {
    try { const v=JSON.parse(raw); return find(v.id)?.mutable && validName(v.name) ? {ok:true,value:JSON.stringify({id:v.id,name:v.name.trim()})} : fail('仅自定义音频可改名，名称最多 64 个字符'); } catch { return fail('无效的音频名称'); }
  }
  if (key === 'audioImport') {
    if (state.audioLibraryFull) return fail('音频库最多 100 项，请先删除不用的条目');
    if (raw.length > Math.ceil(MAX_AUDIO_BYTES / 3) * 4 + 1024) return fail('音频文件最多 512 KiB');
    try {
      const v=JSON.parse(raw);
      if (!validName(v.name) || !['audio/mpeg','audio/wav','audio/ogg','audio/mp4'].includes(v.mime) || typeof v.data !== 'string' || !/^[A-Za-z0-9+/]+={0,2}$/.test(v.data)) return fail('音频名称、格式或数据无效');
      const bytes=Buffer.from(v.data,'base64');
      if (!bytes.length || bytes.length > MAX_AUDIO_BYTES || bytes.toString('base64') !== v.data) return fail('音频文件为空、超限或编码无效');
      const match=v.mime==='audio/wav' ? bytes.toString('ascii',0,4)==='RIFF' && bytes.toString('ascii',8,12)==='WAVE'
        : v.mime==='audio/ogg' ? bytes.toString('ascii',0,4)==='OggS'
        : v.mime==='audio/mp4' ? bytes.toString('ascii',4,8)==='ftyp'
        : bytes.toString('ascii',0,3)==='ID3' || bytes[0]===0xff && (bytes[1]&0xe0)===0xe0;
      return match ? {ok:true,value:JSON.stringify({name:v.name.trim(),mime:v.mime,data:v.data})} : fail('音频内容与格式不符');
    } catch { return fail('音频导入数据无效'); }
  }
  return fail('不支持的远程配置');
}
