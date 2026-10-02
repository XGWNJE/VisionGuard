import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({ plugins: [react()], base: '/console/', build: { outDir: '../../server/dist/console', emptyOutDir: true }, server: { proxy: { '/ws': { target: 'ws://127.0.0.1:4318', ws: true }, '/api': 'http://127.0.0.1:4318', '/screenshots': 'http://127.0.0.1:4318' } } });
