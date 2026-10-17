import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import {fileURLToPath} from 'node:url';
export default defineConfig({plugins:[{name:'draft-theme-sources',enforce:'pre',transform(code,id){if(id.endsWith('/receiver/web/src/theme.css'))return code+'\n@source \"'+fileURLToPath(new URL('../../receiver/web/src/components/ui',import.meta.url))+'\";\n@source \"'+fileURLToPath(new URL('./src',import.meta.url))+'\";';}},react(),tailwindcss()],resolve:{alias:{'@':fileURLToPath(new URL('../../receiver/web/src',import.meta.url))},dedupe:['react','react-dom']},base:'./',build:{assetsInlineLimit:2000000,outDir:'dist'},server:{host:'127.0.0.1'}});
