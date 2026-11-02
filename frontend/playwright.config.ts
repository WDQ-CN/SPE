import { defineConfig } from '@playwright/test'

/**
 * 端到端测试（W4）——用**系统已安装的 Chrome**（`channel: 'chrome'`），不下载 Playwright 自带浏览器。
 *
 * <p>它补的是单测与接口测试都看不到的那一层：真实浏览器里的**渲染、菜单、跳转、对话框交互**，
 * 以及控制台错误（尤其是 CSP 违规——那是 SEC-4 唯一只能在运行时验证的东西）。
 *
 * <p>前置条件：全栈在跑（`docker compose up -d --build`），前端由 Nginx 提供在 18081。
 * 跑法：`pnpm test:e2e`；换地址用 `E2E_BASE_URL`。
 */
export default defineConfig({
  testDir: './e2e',
  // 视觉回归由 playwright.visual.config.ts 独立运行；主 e2e 不能按默认视口跑截图用例
  testIgnore: '**/visual.spec.ts',
  timeout: 60_000,
  expect: { timeout: 8_000 },
  // 用例之间有登录态与建号的先后关系（且共用一个后端库），串行跑更稳也更好定位
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:18081',
    channel: 'chrome',
    locale: 'zh-CN',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
})
