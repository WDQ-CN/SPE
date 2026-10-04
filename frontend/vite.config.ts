import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { loadEnv } from 'vite'
// vitest/config 的 defineConfig 是 vite 的扩展：同一份配置里既能写构建，也能写测试，
// 避免再维护一个 vitest.config.ts 与它重复声明 alias / 插件（两份配置必然漂移）
import { defineConfig } from 'vitest/config'

export default defineConfig(({ mode }) => {
  // 必须显式 loadEnv：Vite 把 .env 的解析结果作为返回值给出（只回写 NODE_ENV），
  // 而本文件在 .env 加载前求值，所以直接读 process.env 只能拿到 shell 环境变量。
  const env = loadEnv(mode, process.cwd())

  return {
    plugins: [
      vue(),
      // Element Plus 按需引入：组件走 Components，ElMessage 等函数式 API 走 AutoImport（含样式）
      AutoImport({ resolvers: [ElementPlusResolver()], dts: 'src/types/auto-imports.d.ts' }),
      Components({
        resolvers: [ElementPlusResolver()],
        // 自家通用组件也自动导入（2026-10-04）：`shared/components` 下的组件（如 PageHeader）
        // 各页直接用、不写 import —— 否则"抽组件"只解决了一半问题，每个页面仍要重复一行 import，
        // 而且新增页面时漏掉 import 是编译期错误（比样式漏改好，但依然是重复）。
        dirs: ['src/shared/components'],
        dts: 'src/types/components.d.ts',
      }),
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
    test: {
      // happy-dom 比 jsdom 轻，够用：这些用例只碰 DOM 与事件，不碰布局计算
      environment: 'happy-dom',
      include: ['src/**/*.spec.ts'],
      // 不开 globals：从 'vitest' 显式导入 describe/it/expect，读代码时不必猜哪些是全局注入的
      globals: false,
      // element-plus 的入口会 import 自己的 CSS。node_modules 默认被 externalize（不走 Vite 管线），
      // Node 直接解析 .css 会报「Unknown file extension ".css"」；inline 进来后 CSS 会被正常 stub。
      server: { deps: { inline: ['element-plus'] } },
    },
  }
})
