import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vite';

export default defineConfig({
  base: '/airband/',
  plugins: [svelte()],
  build: {
    outDir: '../src/main/resources/airband',
    emptyOutDir: true
  }
});
