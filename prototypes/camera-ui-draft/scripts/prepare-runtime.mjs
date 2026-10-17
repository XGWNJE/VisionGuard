import { existsSync, symlinkSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const dependencies = fileURLToPath(new URL('../../../receiver/web/node_modules', import.meta.url));
const destination = fileURLToPath(new URL('../node_modules', import.meta.url));
if (!existsSync(dependencies)) throw new Error('先运行 npm ci --prefix receiver/web。');
if (!existsSync(destination)) symlinkSync(dependencies, destination, process.platform === 'win32' ? 'junction' : 'dir');
console.log('静态渲染器复用控制台依赖。');
