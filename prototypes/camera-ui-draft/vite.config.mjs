import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

const consoleStylesheet = fileURLToPath(new URL('../../receiver/web/src/style.css', import.meta.url)).replaceAll('\\', '/');

export default defineConfig({
  plugins: [{
    name: 'camera-draft-console-theme',
    enforce: 'pre',
    transform(code, id) {
      if (id.split('?')[0].replaceAll('\\', '/') !== consoleStylesheet) return;
      const layoutStart = code.indexOf('/* Application layout.');
      if (layoutStart < 0) throw new Error('控制台主题边界已变化，请核对草稿样式入口。');
      return code.slice(0, layoutStart) + '\n@source "./components";\n@source "../../../prototypes/camera-ui-draft/src";\n';
    },
  }, react(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('../../receiver/web/src', import.meta.url)),
      '@draft': fileURLToPath(new URL('./src', import.meta.url)),
    },
    dedupe: ['react', 'react-dom'],
  },
  publicDir: false,
  server: { fs: { allow: [
    fileURLToPath(new URL('.', import.meta.url)),
    fileURLToPath(new URL('../../receiver/web/node_modules/@fontsource-variable/geist/files', import.meta.url)),
  ] } },
  build: { outDir: '../../.local/camera-ui-draft/site', emptyOutDir: true },
});
