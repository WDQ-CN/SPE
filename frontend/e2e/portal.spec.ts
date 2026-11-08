import { expect, request, test } from '@playwright/test'

import { ADMIN } from './support'

const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:18081'

/** 门户登录：独立身份、独立令牌，不依赖内部菜单与内部 token。 */
test('供应商门户：API 建账号后登录、看到空竞价列表、退出', async ({ page }) => {
  const api = await request.newContext({ baseURL: BASE_URL })
  const adminLogin = await api.post('/api/auth/login', { data: ADMIN })
  const adminToken = (await adminLogin.json()).data.token
  const headers = { Authorization: `Bearer ${adminToken}` }
  const suffix = Date.now()
  const supplierCode = `E2E-PORTAL-${suffix}`
  const loginName = `portal-${suffix}`

  const supplier = await api.post('/api/suppliers', {
    headers,
    data: { code: supplierCode, name: `门户测试供应商 ${suffix}`, category: '电子' },
  })
  expect(supplier.ok()).toBeTruthy()
  const supplierId = (await supplier.json()).data.id as number

  const portalUser = await api.post(`/api/suppliers/${supplierId}/portal-user`, {
    headers,
    data: { loginName, password: 'Portal@12345' },
  })
  expect(portalUser.ok()).toBeTruthy()

  await page.goto('/portal/login')
  await page.getByPlaceholder('门户登录名').fill(loginName)
  await page.getByPlaceholder('口令').fill('Portal@12345')
  await page.getByRole('button', { name: '登录' }).click()

  await expect(page).toHaveURL(/\/portal\/auctions$/)
  await expect(page.locator('h1', { hasText: '我的竞价' })).toBeVisible()
  await expect(page.locator('.portal-brand')).toContainText('SPE Supplier Portal')
  await expect(page.locator('.portal-user')).toContainText(loginName)

  await page.getByRole('button', { name: '退出' }).click()
  await expect(page).toHaveURL(/\/portal\/login$/)
  await api.dispose()
})
