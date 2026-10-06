export const MAX_AUDIO_BYTES = 512 * 1024;
export const MAX_AUDIO_ENTRIES = 100;
export type AudioEntry = {id:string;name:string;mutable:boolean};
export type RemoteConfig = {cameraResolution?:'480p'|'720p';cameraDimScreen?:boolean;cameraHidePreview?:boolean;soundLoopCount?:number;soundSelection?:string;audioEntries?:AudioEntry[];audioPreview?:string;audioLibraryFull?:boolean};
export function audioImportMetadata(file: {name:string;size:number;type:string}) {
  if (!file.size || file.size > MAX_AUDIO_BYTES) throw new Error('音频文件须为 1 字节至 512 KiB');
  const extension=file.name.split('.').pop()?.toLowerCase();
  const mime=({mp3:'audio/mpeg',wav:'audio/wav',ogg:'audio/ogg',m4a:'audio/mp4'} as Record<string,string>)[extension ?? ''];
  if (!mime) throw new Error('请选择 MP3、WAV、OGG 或 M4A 音频');
  const base=file.name.replace(/\.[^.]+$/,'').replace(/[\x00-\x1f\x7f]/g,'').trim();
  let name='';
  for(const character of base) {if(name.length+character.length>64)break;name+=character;}
  return {mime,name:name || '导入音频'};
}
export function validAudioName(name:string) { return !!name.trim() && name.trim().length <= 64 && !/[\x00-\x1f]/.test(name); }
