import { expect, test } from '@playwright/test'

import { loginAsAdmin, menuItem, watchConsole } from './support'

/**
 * W4 整改闭环的功能冒烟（真实全栈）。
 *
 * <p>完整「低分定稿 → 创建 → 提交 → 下期验证」依赖已定稿数据，放在 W5 的闭环用例；
 * 这里只钉住菜单/路由可达、列表/空态渲染，以及 2038 的页面出口，避免把 UI 接线漏掉。
 */
test('ADMIN 从菜单进入整改任务列表，控制台干净', async ({ page }) => {
  const problems = watchConsole(page)
  await loginAsAdmin(page)

  await menuItem(page, '整改任务').click()
  await expect(page).toHaveURL(/\/actions$/)
  await expect(page.getByRole('heading', { name: '整改任务', exact: true })).toBeVisible()
  await expect(page.getByText('全部状态')).toBeVisible()
  await expect(page.locator('.el-table, .el-empty').first()).toBeVisible()

  expect(problems.all).toEqual([])
})

test('整改详情不存在：页面显示 2038 文案', async ({ page }) => {
  const problems = watchConsole(page, {
    ignoreResponse: (status, url) =>
      status === 400 && url.includes('/api/corrective-actions/999999'),
  })
  await loginAsAdmin(page)
  await page.goto('/actions/999999')

  await expect(page.getByText('整改任务不存在')).toBeVisible()
  expect(problems.all).toEqual([])
})
