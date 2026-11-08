import { expect, test } from '@playwright/test'

import { loginAsAdmin, menuItem, watchConsole } from './support'

/** ERP 首版入口：菜单、路由与各页首个真实数据请求（docs/erp/51 §3.1）。 */
test('ERP 九个入口可进入且无控制台错误', async ({ page }) => {
  const consoleIssues = watchConsole(page)
  await loginAsAdmin(page)

  const pages = [
    ['主数据', '主数据'],
    ['寻源', '寻源'],
    ['库存', '库存存量'],
    ['销售订单', '销售订单'],
    ['采购订单', '采购订单'],
    ['生产工单', '生产工单'],
    ['质量管理', '质量管理'],
    ['资产项目', '资产与项目'],
    ['报表中心', '报表中心'],
  ] as const

  for (const [menu, title] of pages) {
    await menuItem(page, menu).click()
    await expect(page.locator('h1', { hasText: title })).toBeVisible()
  }

  // 主数据五个切换项都要真实渲染
  await menuItem(page, '主数据').click()
  await expect(page.getByRole('radio', { name: '公司' })).toBeVisible()
  await expect(page.getByRole('radio', { name: '工厂' })).toBeVisible()
  await expect(page.getByRole('radio', { name: '仓库' })).toBeVisible()
  await expect(page.getByRole('radio', { name: '物料' })).toBeVisible()
  await expect(page.getByRole('radio', { name: '客户' })).toBeVisible()

  // 寻源页必须真实请求 RFQ 列表并渲染新建入口
  await menuItem(page, '寻源').click()
  await expect(page.getByRole('button', { name: '新建 RFQ' })).toBeVisible()
  await page.getByRole('tab', { name: '竞价' }).click()
  await expect(page.getByRole('button', { name: '新建竞价' })).toBeVisible()
  await page.getByRole('tab', { name: '合同 / 框架协议' }).click()
  await expect(page.getByRole('button', { name: '新建合同' })).toBeVisible()
  await page.getByRole('tab', { name: '供应商价格库' }).click()
  await expect(page.getByRole('button', { name: '维护价格' })).toBeVisible()

  // 库存作业是 /inventory 下的隐藏入口：从存量页按钮进入，验证路由与前缀权限继承
  await menuItem(page, '库存').click()
  await page.getByRole('button', { name: '库存作业' }).click()
  await expect(page.locator('h1', { hasText: '库存作业' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '调拨' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '盘点' })).toBeVisible()

  // 采购执行同样挂在采购订单之下：验收收货/发票/付款三个页签
  // 资产与项目三个页签
  await menuItem(page, '资产项目').click()
  await expect(page.getByRole('tab', { name: '固定资产' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '项目', exact: true })).toBeVisible()
  await expect(page.getByRole('tab', { name: '工时' })).toBeVisible()
  await page.getByRole('tab', { name: '固定资产' }).click()
  await expect(page.getByRole('button', { name: '新建资产' })).toBeVisible()
  await page.getByRole('tab', { name: '项目', exact: true }).click()
  await expect(page.getByRole('button', { name: '新建项目' })).toBeVisible()
  await page.getByRole('tab', { name: '工时' }).click()
  await expect(page.getByRole('button', { name: '新建工时单' })).toBeVisible()

  // 质量管理新增检验计划与供应商证书页签
  await menuItem(page, '质量管理').click()
  await expect(page.getByRole('tab', { name: '检验计划' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '供应商证书' })).toBeVisible()
  await page.getByRole('tab', { name: '检验计划' }).click()
  await expect(page.getByRole('button', { name: '新建检验计划' })).toBeVisible()
  await page.getByRole('tab', { name: '供应商证书' }).click()
  await expect(page.getByRole('button', { name: '登记证书' })).toBeVisible()

  // 生产工单下的隐藏 BOM 维护入口
  await menuItem(page, '生产工单').click()
  await page.getByRole('button', { name: 'BOM 维护' }).click()
  await expect(page.locator('h1', { hasText: 'BOM 维护' })).toBeVisible()
  await expect(page.getByRole('button', { name: '新建 BOM' })).toBeVisible()

  await menuItem(page, '采购订单').click()
  await page.getByRole('button', { name: '采购执行' }).click()
  await expect(page.locator('h1', { hasText: '采购执行' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '请购' })).toBeVisible()
  await page.getByRole('tab', { name: '请购' }).click()
  await expect(page.getByRole('button', { name: '新建请购' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '收货' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '供应商发票' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '付款' })).toBeVisible()

  // 销售执行挂在销售订单之下：验收发货/发票/收款/退货四个页签
  await menuItem(page, '销售订单').click()
  await page.getByRole('button', { name: '销售执行' }).click()
  await expect(page.locator('h1', { hasText: '销售执行' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '发货' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '销售发票' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '收款' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '退货' })).toBeVisible()

  // 报表目录由种子数据提供：证明页面确实取到了后端数据，而不只是渲染标题
  await menuItem(page, '报表中心').click()
  await expect(page.locator('.el-table', { hasText: 'INVENTORY_STOCK' })).toBeVisible()

  expect(consoleIssues.csp, '不允许出现 CSP 违规').toEqual([])
  expect(consoleIssues.all, 'ERP 页面不允许出现控制台错误或非预期 4xx/5xx').toEqual([])
})
