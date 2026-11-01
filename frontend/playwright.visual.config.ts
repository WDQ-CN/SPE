import { defineConfig } from '@playwright/test'

/**
 * 像素级视觉回归：**不依赖后端/Docker**。
 *
 * <p>页面通过固定的 API mock 渲染，基线绑定 1440×1000、系统 Chrome、deviceScaleFactor=1。
 * 基线是平台相关的（Playwright 默认在文件名里带 `-win32` 等后缀），
 * 换浏览器大版本或换平台需要重新生成，不能用旧基线强行判绿。
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'visual.spec.ts',
  timeout: 60_000,
  expect: {
    timeout: 10_000,
    toHaveScreenshot: {
      animations: 'disabled',
      caret: 'hide',
      maxDiffPixelRatio: 0.0002,
    },
  },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:4173',
    channel: 'chrome',
    locale: 'zh-CN',
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 1,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'pnpm dev --port 4173 --strictPort',
    url: 'http://localhost:4173',
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
