import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

/** 源码保持 bundler 风格的无扩展名导入；测试时把整个源码目录转成 CommonJS，再用 .js 别名补全无扩展名导入。 */
export function loadTypescriptModule(relativeRoot, relativePath) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), relativeRoot);
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-web-camera-test-'));
  for (const source of sourceFiles(root, path.resolve(root, relativePath))) {
    const relative = source.slice(root.length + 1).replace(/\.tsx?$/, '');
    const outputPath = path.join(directory, `${relative}.cjs`);
    fs.mkdirSync(path.dirname(outputPath), { recursive: true });
    const javascript = ts.transpileModule(fs.readFileSync(source, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true },
      fileName: source,
    }).outputText;
    fs.writeFileSync(outputPath, javascript.replace(/require\("(\.[^"]*)"\)/g, (match, specifier) => {
      if (path.extname(specifier)) return match;
      const resolved = path.resolve(path.dirname(source), specifier);
      const candidate = fs.existsSync(`${resolved}.ts`) ? `${resolved}.ts`
        : fs.existsSync(path.join(resolved, 'index.ts')) ? path.join(resolved, 'index.ts') : `${resolved}.ts`;
      const alias = path.join(directory, path.relative(root, candidate).replace(/\.ts$/, '.js'));
      fs.mkdirSync(path.dirname(alias), { recursive: true });
      fs.writeFileSync(alias, `module.exports = require(${JSON.stringify(`./${path.basename(alias).replace(/\.js$/, '.cjs')}`)});\n`, 'utf8');
      return `require(${JSON.stringify(specifier)})`;
    }), 'utf8');
  }
  return createRequire(import.meta.url)(path.join(directory, relativePath.replace(/\.tsx?$/, '.cjs')));
}

/** 先转入口，再跟随它的相对导入把整棵依赖树转好，避免别名指向尚未生成的文件。 */
function sourceFiles(root, entry) {
  const files = [];
  const seen = new Set();
  const queue = [entry];
  while (queue.length) {
    const file = queue.shift();
    if (seen.has(file) || !fs.existsSync(file)) continue;
    seen.add(file);
    files.push(file);
    const source = fs.readFileSync(file, 'utf8');
    for (const match of source.matchAll(/from\s+'(\.[^']+)'|import\('(\.[^')]+)'\)/g)) {
      const specifier = match[1] ?? match[2];
      if (!specifier || path.extname(specifier)) continue;
      const resolved = path.resolve(path.dirname(file), specifier);
      queue.push(fs.existsSync(`${resolved}.ts`) ? `${resolved}.ts` : path.join(resolved, 'index.ts'));
    }
  }
  return files;
}

/** 最小可用的基线 JPEG（16×16），供帧打包与服务端契约测试使用。 */
export const SAMPLE_JPEG_BASE64 = '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAQABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwCnRRRX0x88f//Z';

export function sampleJpeg() {
  return new Uint8Array(Buffer.from(SAMPLE_JPEG_BASE64, 'base64'));
}
