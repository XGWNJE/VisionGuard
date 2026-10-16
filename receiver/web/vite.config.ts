import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
export default defineConfig({ plugins: [react(), tailwindcss()], resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } }, base: '/console/', build: { outDir: '../../server/dist/console', emptyOutDir: true }, server: { proxy: { '/ws': { target: 'ws://127.0.0.1:4318', ws: true }, '/api': 'http://127.0.0.1:4318', '/screenshots': 'http://127.0.0.1:4318' } } });
