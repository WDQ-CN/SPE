import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  // 必须显式 loadEnv：Vite 把 .env 的解析结果作为返回值给出（只回写 NODE_ENV），
  // 而本文件在 .env 加载前求值，所以直接读 process.env 只能拿到 shell 环境变量。
  const env = loadEnv(mode, process.cwd())

  return {
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
      // 镜像 Nginx 反代行为：开发期只代理 /api（09 D4）。
      // 目标默认 18080（宿主直跑后端的端口，见 09 D3）；shell 环境变量优先于 frontend/.env。
      proxy: {
        '/api': {
          target: process.env.VITE_API_TARGET ?? env.VITE_API_TARGET ?? 'http://localhost:18080',
          changeOrigin: true,
        },
      },
    },
  }
})
