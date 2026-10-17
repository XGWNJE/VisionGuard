import {readFileSync,writeFileSync} from 'node:fs';
let s=readFileSync('dist/index.html','utf8');
s=s.replace(/<script type="module" crossorigin src="([^"]+)"><\/script>/,(_,p)=>`<script type="module">${readFileSync('dist/'+p.replace(/^\.\//,''),'utf8').replace(/<\/script/gi,'<\\/script')}</script>`);
s=s.replace(/<link rel="stylesheet" crossorigin href="([^"]+)">/,(_,p)=>`<style>${readFileSync('dist/'+p.replace(/^\.\//,''),'utf8')}</style>`);
writeFileSync('dist/VisionGuard-inference-draft.html',s);
