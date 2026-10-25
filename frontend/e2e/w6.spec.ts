import { expect, test, type Page } from '@playwright/test'

import { ADMIN, login, menuItem, watchConsole } from './support'

/**
 * 并发 401 的跳转时序（03 §21 / 04-050）：两个 API 请求在 token 失效后**错开**返回 401，
 * 只有第一次能带 `redirect`；后到的那次不得把来源路径覆盖成 `/login`。
 *
 * <p>断言挑的是**不变式**而不是某次观测值（04-050）：`redirect` 必须是受保护路径，
 * 且第二个 401 处理完之后**不再被改写**——并发下具体落在 `/users` 还是 `/suppliers`
 * 取决于两次响应的时序，那不是缺陷。
 *
 * <p>⚠️ 2026-10-25 事故：本文件曾被一次错误的 `mv` 覆盖（未提交 ⇒ 无法从 git 恢复）。
 * 现内容按 `03 §21` 与 `04-050` 的完整描述**重建**，行为等价；原始字节无法找回。
 */
const USERS_DELAY_MS = 400
const SUPPLIERS_DELAY_MS = 900

async function delay(ms: number): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, ms))
}

/** 让匹配的 API 请求延迟 `ms` 后返回 401（token 失效的形态，与拦截器约定一致）。 */
async function unauthorizedAfter(page: Page, urlPattern: string, ms: number): Promise<void> {
  await page.route(urlPattern, async (route) => {
    await delay(ms)
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ code: 1001, message: '未认证或登录已过期', data: null }),
    })
  })
}

test('并发 401：只跳一次登录页，redirect 保持最初的受保护路径', async ({ page }) => {
  const problems = watchConsole(page)
  await login(page, ADMIN.username, ADMIN.password)
  await expect(menuItem(page, '用户管理')).toBeVisible()

  await unauthorizedAfter(page, '**/api/users*', USERS_DELAY_MS)
  await unauthorizedAfter(page, '**/api/suppliers*', SUPPLIERS_DELAY_MS)

  // 用 waitForRequest 等请求真的发出，不用 sleep（sleep 依赖"组件挂载够快"这个不稳定假设）
  const usersRequest = page.waitForRequest(
    (request) => request.url().includes('/api/users?') && request.method() === 'GET',
  )
  const usersResponse = page.waitForResponse((response) =>
    response.url().includes('/api/users?'),
  )
  await menuItem(page, '用户管理').click()
  await usersRequest

  // 第一个请求还在飞就切到供应商页：制造"两个 401 错开到达"的并发窗口
  const suppliersRequest = page.waitForRequest(
    (request) => request.url().includes('/api/suppliers?') && request.method() === 'GET',
  )
  const suppliersResponse = page.waitForResponse((response) =>
    response.url().includes('/api/suppliers?'),
  )
  await menuItem(page, '供应商').click()
  await suppliersRequest

  // 第一个 401（400ms）到达：跳登录页，redirect = 401 到达那一刻的路由
  await expect(page).toHaveURL(/\/login\?redirect=/)
  const firstUrl = page.url()
  const redirect = new URL(firstUrl).searchParams.get('redirect')
  expect(redirect, 'redirect 必须存在').toBeTruthy()
  expect(redirect!.startsWith('/'), 'redirect 必须是站内路径').toBe(true)
  expect(redirect!.startsWith('/login'), 'redirect 不能变成登录页自身').toBe(false)

  // 第二个 401（900ms）到达并处理完：redirect 不得被改写
  await usersResponse
  await suppliersResponse
  await delay(200)
  expect(page.url(), '后到的 401 不得改写 redirect').toBe(firstUrl)

  expect(problems.all, '控制台不应有错误（401 已在 support 里豁免）').toEqual([])
  expect(problems.csp, '不应有 CSP 违规').toEqual([])
})
