import { expect, request, test, type Page } from '@playwright/test'

import { ADMIN, login, watchConsole } from './support'

/**
 * M5（`22`）端到端验收：分析看板、结果导出、报告完整性、审计页、预警处理、
 * 以及「定稿 → 解锁 → 重提 → 再定稿 → 新预警」闭环。
 *
 * <p>演示数据来自 `V999`（demo profile）；闭环用例自己建供应商/任务，不复用演示行，
 * 因此可重复运行。所有写操作只落在本用例新建的数据上。
 */
const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:18081'
const DEMO_PASSWORD = 'Admin@12345'
const DEMO_TASK = '演示 2026 Q3'
const DEMO_PERIOD = '2026 Q3'

type Api = Awaited<ReturnType<typeof request.newContext>>
interface Ctx {
  api: Api
  headers: Record<string, string>
}

async function loginApi(username: string, password = DEMO_PASSWORD): Promise<Ctx> {
  const api = await request.newContext({ baseURL: BASE_URL })
  const body = await (
    await api.post('/api/auth/login', { data: { username, password } })
  ).json()
  expect(body.code, `${username} 登录`).toBe(0)
  return { api, headers: { Authorization: `Bearer ${body.data.token}` } }
}

async function adminCtx(): Promise<Ctx> {
  return loginApi(ADMIN.username, ADMIN.password)
}

async function userId(ctx: Ctx, username: string): Promise<number> {
  const body = await (
    await ctx.api.get('/api/users', {
      headers: ctx.headers,
      params: { page: 1, size: 100, keyword: username },
    })
  ).json()
  const row = (body.data.list as { id: number; username: string }[]).find(
    (item) => item.username === username,
  )
  expect(row, `用户必须存在：${username}`).toBeTruthy()
  return row!.id
}

async function defaultTemplate(ctx: Ctx): Promise<{ templateId: number; versionId: number }> {
  const templates = await (
    await ctx.api.get('/api/templates?page=1&size=100', { headers: ctx.headers })
  ).json()
  const template = (templates.data.list as { id: number; name: string }[]).find(
    (item) => item.name === '季度绩效默认模板',
  )
  expect(template, '默认模板必须存在').toBeTruthy()
  const versions = await (
    await ctx.api.get(`/api/templates/${template!.id}/published-versions`, {
      headers: ctx.headers,
    })
  ).json()
  return { templateId: template!.id, versionId: versions.data[0].versionId }
}

async function submitAllSheets(
  manager: Ctx,
  evaluator: Ctx,
  taskId: number,
): Promise<void> {
  const sheets = (
    await (
      await manager.api.get(`/api/tasks/${taskId}/sheets`, { headers: manager.headers })
    ).json()
  ).data as { sheetId: number; status: string }[]
  for (const sheet of sheets) {
    if (sheet.status === 'SUBMITTED') {
      continue
    }
    const detail = await (
      await evaluator.api.get(`/api/sheets/${sheet.sheetId}/scores`, {
        headers: evaluator.headers,
      })
    ).json()
    const entries = (
      detail.data.indicators as {
        indicatorId: number
        type: string
        scoreMode: string | null
        targetValue: number | null
      }[]
    ).map((indicator) => ({
      indicatorId: indicator.indicatorId,
      // 故意压低分数，确保触发「总分低于阈值」预警（默认阈值 70）
      rawValue:
        indicator.type === 'QUAL'
          ? 1
          : indicator.scoreMode === 'TARGET'
            ? Number(((indicator.targetValue as number) * 0.5).toFixed(2))
            : 50,
    }))
    const saved = await (
      await evaluator.api.put(`/api/sheets/${sheet.sheetId}/scores`, {
        headers: evaluator.headers,
        data: { scores: entries },
      })
    ).json()
    expect(saved.code, `保存评分单 ${sheet.sheetId}`).toBe(0)
    const submitted = await (
      await evaluator.api.post(`/api/sheets/${sheet.sheetId}/submit`, {
        headers: evaluator.headers,
        data: { version: detail.data.version },
      })
    ).json()
    expect(submitted.code, `提交评分单 ${sheet.sheetId}`).toBe(0)
  }
}

async function approveTwice(
  manager1: Ctx,
  manager2: Ctx,
  tsId: number,
): Promise<void> {
  let report = (
    await (
      await manager1.api.get(`/api/task-suppliers/${tsId}/report`, { headers: manager1.headers })
    ).json()
  ).data
  let approved = await (
    await manager1.api.post(`/api/task-suppliers/${tsId}/approve`, {
      headers: manager1.headers,
      data: { version: report.version },
    })
  ).json()
  expect(approved.code, '第一级通过').toBe(0)
  report = (
    await (
      await manager2.api.get(`/api/task-suppliers/${tsId}/report`, { headers: manager2.headers })
    ).json()
  ).data
  approved = await (
    await manager2.api.post(`/api/task-suppliers/${tsId}/approve`, {
      headers: manager2.headers,
      data: { version: report.version },
    })
  ).json()
  expect(approved.code, '末级通过（定稿）').toBe(0)
}

async function loginUi(page: Page, username: string, password: string): Promise<void> {
  await login(page, username, password)
  await expect(page.getByRole('button', { name: '退出' })).toBeVisible()
}

test.describe('M5 演示数据与分析', () => {
  test('分析看板：选演示周期 → 排行/趋势/对比渲染；导出结果并可读报告', async ({ page }) => {
    const problems = watchConsole(page)
    await loginUi(page, ADMIN.username, ADMIN.password)

    await page.goto('/analytics')
    const periodSelect = page.locator('.analytics__period .el-select__wrapper').first()
    await periodSelect.click()
    await page.locator('.el-select-dropdown__item').filter({ hasText: DEMO_PERIOD }).first().click()
    await expect(
      page.locator('.el-table__row').filter({ hasText: 'DEMO-001' }).first(),
    ).toBeVisible()

    // 趋势：选 DEMO-001，图表容器渲染出 canvas
    // ⚠️ 下拉面板挂在 body 上、前一个 select 的面板仍留在 DOM（task-ui 的教训）⇒ 用输入过滤 + 键盘选中
    const trendInput = page.locator('.analytics__supplier input').first()
    await page.locator('.analytics__supplier .el-select__wrapper').first().click()
    await trendInput.fill('DEMO-001')
    await trendInput.press('ArrowDown')
    await trendInput.press('Enter')
    await expect(page.locator('.analytics-chart canvas').first()).toBeVisible()

    // 对比：默认取当前排行前两家（都必然有生效结果），随周期切换自动重取
    await expect(page.locator('.analytics-chart canvas')).toHaveCount(3)

    // 导出：结果入口按名称找到演示任务 → 导出 xlsx
    await page.goto('/results')
    await page.getByPlaceholder('按任务名称搜索').fill(DEMO_TASK)
    await page.getByRole('button', { name: '查询' }).click()
    const row = page.locator('.el-table__row').filter({ hasText: DEMO_TASK }).first()
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '查看结果' }).click()
    await expect(page).toHaveURL(/\/results\/\d+/)
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByRole('button', { name: '导出 Excel' }).click(),
    ])
    expect(download.suggestedFilename()).toMatch(/^结果-.+\.xlsx$/)

    // 演示报告非空：定稿快照 + 指标明细 + 时间线
    await page.locator('.el-table__row').first().getByRole('button', { name: '评估报告' }).click()
    await expect(page).toHaveURL(/\/reports\/\d+/)
    await expect(page.getByText('定稿快照').first()).toBeVisible()
    await page.locator('.el-collapse-item__header').first().click()
    await expect(page.getByText('演示指标').first()).toBeVisible()
    await expect(page.locator('.approval-timeline').first()).toContainText('通过')

    expect(problems.all, '控制台不应有错误').toEqual([])
    expect(problems.csp).toEqual([])
  })
})

test.describe('M5 预警与解锁闭环', () => {
  test('定稿触发预警 → 管理员解锁 → 重提再定稿 → 处理预警', async ({ page }) => {
    const suffix = String(Date.now()).slice(-6)
    const admin = await adminCtx()
    const manager1 = await loginApi('demo-manager1')
    const manager2 = await loginApi('demo-manager2')
    const evaluator = await loginApi('demo-eval-quality')
    const manager1Id = await userId(admin, 'demo-manager1')
    const manager2Id = await userId(admin, 'demo-manager2')
    const evaluatorId = await userId(admin, 'demo-eval-quality')
    const { templateId, versionId } = await defaultTemplate(manager1)

    const supplier = await (
      await manager1.api.post('/api/suppliers', {
        headers: manager1.headers,
        data: {
          code: `M5-E2E-${suffix}`,
          name: `M5 闭环供应商 ${suffix}`,
          category: 'E2E',
        },
      })
    ).json()
    expect(supplier.code, '创建供应商').toBe(0)
    const supplierId: number = supplier.data.id

    const created = await (
      await manager1.api.post('/api/tasks', {
        headers: manager1.headers,
        data: {
          name: `M5 闭环任务 ${suffix}`,
          year: 2099,
          quarter: 1,
          templateId,
          templateVersionId: versionId,
          deadline: '2099-12-31',
          supplierIds: [supplierId],
          approverIds: [manager1Id, manager2Id],
        },
      })
    ).json()
    expect(created.code, '创建任务').toBe(0)
    const taskId: number = created.data
    const published = await (
      await manager1.api.post(`/api/tasks/${taskId}/publish`, {
        headers: manager1.headers,
        data: { version: 0 },
      })
    ).json()
    expect(published.code, '发布任务').toBe(0)

    const sheets = (
      await (
        await manager1.api.get(`/api/tasks/${taskId}/sheets`, { headers: manager1.headers })
      ).json()
    ).data as { sheetId: number; taskSupplierId: number; version: number }[]
    for (const sheet of sheets) {
      const assigned = await (
        await manager1.api.put(`/api/sheets/${sheet.sheetId}/assign`, {
          headers: manager1.headers,
          data: { assigneeId: evaluatorId, version: sheet.version },
        })
      ).json()
      expect(assigned.code, '改派').toBe(0)
    }
    const tsId = sheets[0].taskSupplierId

    await submitAllSheets(manager1, evaluator, taskId)
    await approveTwice(manager1, manager2, tsId)

    // 定稿即预警（默认总分阈值 70；用例把分数压到 ≤50）
    const alerts = await (
      await manager1.api.get('/api/alerts', {
        headers: manager1.headers,
        params: { page: 1, size: 100, status: 'UNREAD' },
      })
    ).json()
    const mine = (alerts.data.list as { supplierId: number; type: string }[]).filter(
      (alert) => alert.supplierId === supplierId,
    )
    expect(mine.map((alert) => alert.type)).toContain('TOTAL_LOW')

    // 管理员在报告页解锁重评
    await loginUi(page, ADMIN.username, ADMIN.password)
    await page.goto(`/reports/${tsId}`)
    await expect(page.getByText('定稿快照').first()).toBeVisible()
    await page.getByRole('button', { name: '解锁重评' }).click()
    await page.locator('.el-message-box textarea').fill('E2E 复核：解锁重评')
    await page.getByRole('button', { name: '确认解锁' }).click()
    await expect(page.locator('.el-message').filter({ hasText: '已解锁' })).toBeVisible()
    await expect(page.getByText('评分中').first()).toBeVisible()

    // 重提 → 两级通过 → 再定稿（回归：解锁删快照后重定稿不撞 uk_ts_dimension）
    await submitAllSheets(manager1, evaluator, taskId)
    await approveTwice(manager1, manager2, tsId)
    const effective = (
      await (
        await manager1.api.get(`/api/task-suppliers/${tsId}/report`, { headers: manager1.headers })
      ).json()
    ).data
    expect(effective.status, '解锁后重定稿应再次生效').toBe('EFFECTIVE')
    expect(effective.round, '重审必须开新一轮').toBe(2)

    // 重定稿后旧预警 superseded、新预警 UNREAD：经理在列表里处理
    const refreshed = await (
      await manager1.api.get('/api/alerts', {
        headers: manager1.headers,
        params: { page: 1, size: 100, status: 'UNREAD' },
      })
    ).json()
    expect(
      (refreshed.data.list as { supplierId: number; type: string }[]).some(
        (alert) => alert.supplierId === supplierId && alert.type === 'TOTAL_LOW',
      ),
      '重定稿应重新生成 TOTAL_LOW 预警（旧预警的 SUPERSEDED 由后端用例断言）',
    ).toBe(true)

    await page.getByRole('button', { name: '退出' }).click()
    await loginUi(page, 'demo-manager1', DEMO_PASSWORD)
    await page.goto('/alerts')
    await page.locator('.el-radio-button', { hasText: '未读' }).click()
    const alertRow = page
      .locator('.el-table__row')
      .filter({ hasText: `M5-E2E-${suffix}` })
      .first()
    await expect(alertRow).toBeVisible()
    await alertRow.getByRole('button', { name: '处理' }).click()
    await page.getByPlaceholder('可选：记录处理方式或结论').fill('E2E 已处理')
    await page.getByRole('button', { name: '确认处理' }).click()
    await expect(page.locator('.el-message').filter({ hasText: '已处理' })).toBeVisible()
    await expect(alertRow).toHaveCount(0)
  })
})

test.describe('M5 操作日志与设置', () => {
  test('操作日志：过滤登录动作并展开 detail；预警规则可保存', async ({ page }) => {
    const problems = watchConsole(page)
    await loginUi(page, ADMIN.username, ADMIN.password)
    await page.goto('/audit')
    await expect(page.locator('.el-table__row').first()).toBeVisible()

    await page.locator('.audit__action .el-select__wrapper').first().click()
    await page
      .locator('.el-select-dropdown__item')
      .filter({ hasText: 'AUTH_LOGIN_SUCCESS' })
      .first()
      .click()
    await page.getByRole('button', { name: '查询' }).click()
    await expect(
      page.locator('.el-table__row').filter({ hasText: 'AUTH_LOGIN_SUCCESS' }).first(),
    ).toBeVisible()
    await page.locator('.el-table__expand-icon').first().click()
    await expect(page.locator('.audit__detail').first()).toBeVisible()

    // 设置页：预警规则保存（值不变，不污染全局配置）
    await page.goto('/settings')
    await expect(page.getByText('预警规则').first()).toBeVisible()
    await page.getByRole('button', { name: '保存' }).last().click()
    await expect(
      page.locator('.el-message').filter({ hasText: '已保存预警规则' }),
    ).toBeVisible()

    expect(problems.all, '控制台不应有错误').toEqual([])
    expect(problems.csp).toEqual([])
  })
})
