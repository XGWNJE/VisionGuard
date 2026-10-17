import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('../../receiver/web/src', import.meta.url)) },
    dedupe: ['react', 'react-dom'],
  },
  base: '/camera/',
  build: { outDir: '../../server/dist/camera', emptyOutDir: true },
  server: { fs: { allow: [fileURLToPath(new URL('../..', import.meta.url))] }, proxy: {
    '/ws': { target: 'ws://127.0.0.1:4318', ws: true },
    '/media/ws': { target: 'ws://127.0.0.1:4318', ws: true },
    '/api': 'http://127.0.0.1:4318', '/health': 'http://127.0.0.1:4318',
  } },
});
