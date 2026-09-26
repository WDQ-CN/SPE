import { expect, test } from '@playwright/test'

import { loginAsAdmin, menuItem, watchConsole } from './support'

/**
 * 补上 11 §9 里挂了很久的「未专门复现」项：**并发 401 时的跳转时序**。
 *
 * <p>场景：一个页面并发多个请求，token 在中途失效，几个 401 几乎同时回来。要求是——
 * **只跳一次登录页，且 `redirect` 指向最初那个受保护路径**。若没有并发守卫，后到的 401 会拿着
 * 「当前已经在 /login」去覆盖 `redirect`，用户重新登录后被送回登录页（来源丢失）。
 *
 * <p>代码侧的守卫在 `app/router/index.ts` 的 `setNavigate` 里（`loginNavigationPending` +
 * 「已经在 /login 就不再导航」双重判断）。本用例的价值在于：那条守卫是**并发时序**相关的，
 * 单测里没有真实路由与真实响应时序，很难证明它真的有效。
 *
 * <p>怎么制造并发：用**路由拦截**让两个 API 请求都在「token 已失效」的前提下返回 401，并给出时间差——
 * A（`/api/users`）先回、触发跳转，B（`/api/suppliers`）晚回，正是可能覆盖 `redirect` 的那一次。
 * 不用「篡改 localStorage」是因为这里要验的是**拦截器在并发下的处理**，而不是「没 token 会不会跳」。
 */
test('并发 401：只跳一次登录页，redirect 保持最初的受保护路径', async ({ page }) => {
  const issues = watchConsole(page)

  await loginAsAdmin(page)

  // 登录成功之后才挂拦截，别把登录请求也算进去
  await page.route('**/api/users**', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 400))
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ code: 1001, message: '未认证或登录已过期' }),
    })
  })
  await page.route('**/api/suppliers**', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 900))
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ code: 1001, message: '未认证或登录已过期' }),
    })
  })

  // 两次 SPA 路由切换：等第一个请求真的发出去了再切第二次。
  // 用 waitForRequest 而不是 sleep——sleep 依赖"组件挂载够快"这个不稳定假设。
  const usersRequest = page.waitForRequest('**/api/users**')
  await menuItem(page, '用户管理').click()
  await usersRequest
  await menuItem(page, '供应商').click()

  // 第一个 401（400ms）已处理：应当跳到登录页并带上来源
  await expect(page).toHaveURL(/\/login\?redirect=/)
  const first = new URL(page.url()).searchParams.get('redirect')
  // 并发下 redirect 取的是「401 到达那一刻的路由」——可能是 /users 也可能是 /suppliers
  // （本例两次请求延迟不同，落在哪个页面取决于时序）。**要断言的不是具体哪一个**，
  // 而是它必须是受保护路径，且之后不会再被改写。
  expect(['/users', '/suppliers']).toContain(first)

  // 等第二个 401（900ms）也处理完。没有并发守卫时，这一次会拿「当前已经在 /login」去覆盖 redirect，
  // 变成 /login?redirect=%2Flogin —— 那才是这条用例要拦住的行为。
  await page.waitForTimeout(1200)
  const settled = new URL(page.url()).searchParams.get('redirect')
  expect(settled).not.toContain('login')
  expect(settled).toBe(first)

  expect(issues.all).toEqual([])
})
