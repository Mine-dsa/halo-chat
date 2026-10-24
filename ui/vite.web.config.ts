import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Standalone front-site chat page. The plugin serves index.html from the
 * classpath and exposes the hashed assets through Halo's ReverseProxy at
 * /plugins/halo-chat/assets/web/**.
 */
export default defineConfig({
  root: fileURLToPath(new URL('./src/web', import.meta.url)),
  base: '/plugins/halo-chat/assets/web/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: fileURLToPath(new URL('./build/web', import.meta.url)),
    emptyOutDir: true,
    target: 'es2020',
    cssCodeSplit: false,
  },
})
