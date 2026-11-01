import { expect, test } from '@playwright/test'

import { loginAsAdmin, menuItem, watchConsole } from './support'

/**
 * P2-8 的用户可见面：管理员停用自己会被后端 3002 拒绝，前端按 3xxx 统一提示并刷新列表。
 *
 * <p>这里验的是「拒绝真的落到了界面上」：确认框、警告文案、状态仍为启用、控制台无异常。
 * 守卫本身与并发不变量由后端的单测/集成用例覆盖（`26` §3.4）。
 */
test('用户页：管理员停用自己 → 警告提示、状态不变、控制台无错误', async ({ page }) => {
  // 409 是本用例主动制造的预期结果（守卫拒绝），只豁免这一条 URL；其它非 2xx 仍按失败处理
  const console_ = watchConsole(page, {
    ignoreResponse: (status, url) => status === 409 && /\/api\/users\/\d+\/status$/.test(url),
  })

  await loginAsAdmin(page)
  await menuItem(page, '用户管理').click()
  await expect(page).toHaveURL(/\/users$/)

  // 种子管理员在列表第一页（按 id 升序、id=1）；用姓名定位，避免与其它账号的子串冲突
  const row = page.locator('.el-table__row', { hasText: '系统管理员' }).first()
  await expect(row).toContainText('启用')

  // 拒绝后拦截器必须触发一次列表刷新：只断言"状态仍是启用"无法区分"刷新过"与"没刷新"（03 §50 P3-9）。
  const usersReloaded = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      /\/api\/users(?:\?|$)/.test(response.url()) &&
      response.status() === 200,
  )
  await row.getByRole('button', { name: '停用' }).click()
  await expect(page.locator('.el-message-box')).toBeVisible()
  await page.getByRole('button', { name: '确定' }).click()

  // 3xxx 走全局警告提示（文案来自 ErrorCode.SELF_DISABLE_FORBIDDEN）
  await expect(page.locator('.el-message--warning')).toContainText('不能停用当前登录账号')
  await usersReloaded
  // 刷新后状态必须仍是启用
  await expect(page.locator('.el-table__row', { hasText: '系统管理员' }).first()).toContainText('启用')

  expect(console_.all, `控制台报错：\n${console_.all.join('\n')}`).toEqual([])
})
