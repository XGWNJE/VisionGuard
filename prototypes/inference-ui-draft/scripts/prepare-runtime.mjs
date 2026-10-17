import {existsSync,symlinkSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
const source=fileURLToPath(new URL('../../../receiver/web/node_modules',import.meta.url));
const destination=fileURLToPath(new URL('../node_modules',import.meta.url));
if(!existsSync(source))throw new Error('先运行 npm ci --prefix receiver/web');
if(!existsSync(destination))symlinkSync(source,destination,process.platform==='win32'?'junction':'dir');
