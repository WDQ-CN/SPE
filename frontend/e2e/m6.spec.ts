import { expect, test } from '@playwright/test'

import { login } from './support'

/**
 * M6 端到端验收（23-M6详细计划）：ADMIN 生成 Token → 真实 Nginx 链路用它访问开放 API →
 * 停用后立即 401；非 ADMIN 看不到入口、直连路由被守卫挡回。
 *
 * <p>Token 明文只在本页出现一次；用例立即消费，不落到文件/日志。停用不可逆，产生一条
 * DISABLED 演示数据（无删除接口，与其它 e2e 探针数据同族）。
 */
const PASSWORD = 'Admin@12345'

test.describe('M6 开放 API Token', () => {
  test('ADMIN 生成/停用 Token，Token 经 Nginx 可访问开放 API', async ({ page }) => {
    const suffix = String(Date.now()).slice(-6)
    const name = `M6-E2E-${suffix}`

    await login(page, 'admin', PASSWORD)
    await expect(page.locator('.el-menu-item', { hasText: '开放 API' })).toBeVisible()
    await page.goto('/api-tokens')
    await expect(page.getByRole('button', { name: '生成 Token' })).toBeVisible()

    await page.getByRole('button', { name: '生成 Token' }).click()
    await page.getByPlaceholder('例如：ERP 生产环境').fill(name)
    await page.locator('.el-dialog').getByRole('button', { name: '生成', exact: true }).click()

    const tokenDialog = page.locator('.el-dialog', { hasText: 'Token 已生成' })
    await expect(tokenDialog).toBeVisible()
    const token = (await tokenDialog.locator('.token__value').innerText()).trim()
    expect(token, 'Token 明文应以 spet_ 开头').toMatch(/^spet_[A-Za-z0-9_-]{43}$/)
    await tokenDialog.getByRole('button', { name: '我已保存' }).click()

    const row = page.locator('.el-table__row').filter({ hasText: name })
    await expect(row).toBeVisible()
    await expect(row).toContainText('启用')
    await expect(row).toContainText('从未使用')

    // 真实 Nginx 链路：Token 读 active（demo Q4 进行中）；不再使用页面登录态
    const active = await page.request.get('/open/v1/tasks/active?page=1&size=10', {
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(active.status(), '有效 Token 应通过安全链').toBe(200)
    const activeBody = await active.json()
    expect(activeBody.code).toBe(0)
    expect(Array.isArray(activeBody.data.list)).toBe(true)

    const firstTask = activeBody.data.list[0] as { taskId: number } | undefined
    if (firstTask) {
      const indicators = await page.request.get(
        `/open/v1/tasks/${firstTask.taskId}/indicators`,
        { headers: { Authorization: `Bearer ${token}` } },
      )
      expect(indicators.status()).toBe(200)
      expect((await indicators.json()).code).toBe(0)
    }

    await row.getByRole('button', { name: '停用' }).click()
    await page.getByRole('button', { name: '确定' }).click()
    await expect(page.locator('.el-message').filter({ hasText: '已停用' })).toBeVisible()
    await expect(row).toContainText('已停用')

    const revoked = await page.request.get('/open/v1/tasks/active', {
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(revoked.status(), '停用后旧 Token 立即失效').toBe(401)
    expect((await revoked.json()).code).toBe(1001)
  })

  test('非 ADMIN：菜单无开放 API，直连路由被守卫挡回首页', async ({ page }) => {
    await login(page, 'demo-manager1', PASSWORD)
    // login() 只负责点击；先等认证态渲染完成，否则直接 goto 会撞上「未登录 → 回登录页」
    await expect(page.locator('.el-menu-item').first()).toBeVisible()
    await expect(page.locator('.el-menu-item', { hasText: '开放 API' })).toHaveCount(0)
    await page.goto('/api-tokens')
    await expect(page).toHaveURL(/localhost:18081\/$/)
  })
})
