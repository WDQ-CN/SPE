import { expect, test, type Page } from '@playwright/test'

import { installAuth, installVisualMocks } from './visual-mocks'
import { watchConsole } from './support'

/**
 * 像素级视觉回归：固定数据 + 固定视口 + 系统 Chrome。
 *
 * <p>基线生成：`pnpm test:visual:update`；日常校验：`pnpm test:visual`。
 * 截图前统一等待自托管字体与（如有）ECharts 画布稳定；CSS 动画由
 * `toHaveScreenshot.animations = disabled` 快进。
 */

async function settle(page: Page, hasChart = false): Promise<void> {
  await page.evaluate(async () => {
    await document.fonts.ready
  })
  if ((await page.locator('.layout__content').count()) > 0) {
    await page.locator('.layout__content').evaluate((element) => {
      element.scrollTop = 0
    })
  }
  await page.evaluate(() => {
    const active = document.activeElement as HTMLElement | null
    active?.blur()
  })
  if (hasChart) {
    await page.locator('canvas').first().waitFor({ state: 'visible' })
    // ECharts 默认 1s 入场动画；等它走完再截 canvas，避免抓到中间帧。
    await page.waitForTimeout(1200)
  }
}

async function scrollToBottom(page: Page): Promise<void> {
  await page.locator('.layout__content').evaluate((element) => {
    element.scrollTop = element.scrollHeight
  })
  await page.waitForTimeout(300)
}

async function openPage(page: Page, pathname: string, options: { roles?: string[]; chart?: boolean } = {}): Promise<void> {
  await installAuth(page, options.roles ?? ['ADMIN'])
  await installVisualMocks(page)
  await page.goto(pathname, { waitUntil: 'networkidle' })
  await settle(page, options.chart)
}

test('登录页', async ({ page }) => {
  const problems = watchConsole(page)
  await page.goto('/login', { waitUntil: 'networkidle' })
  await settle(page)
  await expect(page.locator('.login__brand-note')).toContainText('Internal access only')
  await expect(page).toHaveScreenshot('login.png')
  expect(problems.all).toEqual([])
})

test('工作台', async ({ page }) => {
  const problems = watchConsole(page)
  await openPage(page, '/')
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible()
  await expect(page).toHaveScreenshot('home.png')
  expect(problems.all).toEqual([])
})

test('供应商列表', async ({ page }) => {
  const problems = watchConsole(page)
  await openPage(page, '/suppliers')
  await expect(page.getByText('华东精密制造')).toBeVisible()
  await expect(page).toHaveScreenshot('suppliers.png')
  expect(problems.all).toEqual([])
})

test('任务详情', async ({ page }) => {
  const problems = watchConsole(page)
  await openPage(page, '/tasks/9')
  await expect(page.getByText('进度看板')).toBeVisible()
  await expect(page).toHaveScreenshot('task-detail.png')
  await scrollToBottom(page)
  await expect(page).toHaveScreenshot('task-detail-bottom.png')
  expect(problems.all).toEqual([])
})

test('打分页', async ({ page }) => {
  const problems = watchConsole(page)
  await openPage(page, '/sheets/501', { roles: ['EVALUATOR'] })
  await expect(page.getByText('评分未齐').first()).toBeVisible()
  await expect(page).toHaveScreenshot('scoring.png')
  expect(problems.all).toEqual([])
})

test('分析看板', async ({ page }) => {
  const problems = watchConsole(page)
  await openPage(page, '/analytics', { chart: true })
  await expect(page.locator('.analytics-chart canvas')).toHaveCount(3)
  await expect(page).toHaveScreenshot('analytics.png')
  await scrollToBottom(page)
  await expect(page).toHaveScreenshot('analytics-bottom.png')
  expect(problems.all).toEqual([])
})

test('评估报告', async ({ page }) => {
  const problems = watchConsole(page)
  await openPage(page, '/reports/21', { chart: true })
  await expect(page.getByText('定稿快照').first()).toBeVisible()
  await expect(page).toHaveScreenshot('report.png')
  expect(problems.all).toEqual([])
})

test('模板编辑器', async ({ page }) => {
  const problems = watchConsole(page)
  await openPage(page, '/templates/31')
  await expect(page.getByText('制造类供应商评估')).toBeVisible()
  await expect(page).toHaveScreenshot('template-editor.png')
  expect(problems.all).toEqual([])
})

test('系统设置', async ({ page }) => {
  const problems = watchConsole(page)
  await openPage(page, '/settings')
  await expect(page.getByText('预警规则').first()).toBeVisible()
  await expect(page).toHaveScreenshot('settings.png')
  expect(problems.all).toEqual([])
})

test('移动端菜单', async ({ page }) => {
  const problems = watchConsole(page)
  await page.setViewportSize({ width: 800, height: 1000 })
  await openPage(page, '/')
  await page.getByRole('button', { name: '菜单' }).click()
  await page.locator('.el-dropdown-menu').waitFor({ state: 'visible' })
  await expect(page.locator('.el-dropdown-menu__item.is-active')).toContainText('首页')
  await page.mouse.move(20, 500)
  await page.evaluate(() => {
    const active = document.activeElement as HTMLElement | null
    active?.blur()
  })
  await expect(page).toHaveScreenshot('mobile-menu.png')
  expect(problems.all).toEqual([])
})
