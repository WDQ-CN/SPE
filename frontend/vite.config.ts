import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入：组件走 Components，ElMessage 等函数式 API 走 AutoImport（含样式）
    AutoImport({ resolvers: [ElementPlusResolver()], dts: 'src/types/auto-imports.d.ts' }),
    Components({ resolvers: [ElementPlusResolver()], dts: 'src/types/components.d.ts' }),
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    // 镜像 Nginx 反代行为：开发期只代理 /api（09 D4）
    proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } },
  },
})
