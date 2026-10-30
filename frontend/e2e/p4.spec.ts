import { expect, request, test, type Page } from '@playwright/test'

import { ADMIN, login, menuItem, watchConsole } from './support'

/**
 * P4-W6 的端到端验收：**M4 闭环**（评分→驳回→重提→两级通过→结果/报告）。
 *
 * <p>种子数据走真实 API（建任务/发布/改派/写分/提交），UI 只驱动本阶段新增的三处：
 * 审批页（驳回、通过、时间线）、结果列表、评估报告（雷达图/来源/快照口径）。
 *
 * <p>每次用全新周期（2045 起顺延），避免 J4 唯一性与历史评分单干扰；累积数据留在 dev 库（11 §9）。
 */
const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:18081'
const PASSWORD = 'E2eP4@12345'
const M1 = 'e2e-p4-manager1'
const M2 = 'e2e-p4-manager2'
const EVALUATOR = 'e2e-p4-evaluator'
const VIEWER = 'e2e-p4-viewer'
const SUPPLIER_CODE = 'E2E-P4-SUP-001'
const TASK_PREFIX = 'E2E-P4 审批闭环'

type Api = Awaited<ReturnType<typeof request.newContext>>

interface ApiContext {
  api: Api
  headers: Record<string, string>
}

interface Seed extends ApiContext {
  taskId: number
  taskSupplierId: number
  taskName: string
}

async function adminApi(): Promise<ApiContext> {
  const api = await request.newContext({ baseURL: BASE_URL })
  const response = await api.post('/api/auth/login', {
    data: { username: ADMIN.username, password: ADMIN.password },
  })
  const body = await response.json()
  expect(body.code, 'admin 登录').toBe(0)
  return { api, headers: { Authorization: `Bearer ${body.data.token}` } }
}

async function loginAs(username: string): Promise<ApiContext> {
  const api = await request.newContext({ baseURL: BASE_URL })
  const response = await api.post('/api/auth/login', {
    data: { username, password: PASSWORD },
  })
  const body = await response.json()
  expect(body.code, `${username} 登录`).toBe(0)
  return { api, headers: { Authorization: `Bearer ${body.data.token}` } }
}

async function ensureUser(
  context: ApiContext,
  username: string,
  role: string,
  department: string,
): Promise<number> {
  const listed = await context.api.get('/api/users?page=1&size=100', { headers: context.headers })
  const rows: { id: number; username: string; status: string; version: number }[] = (
    await listed.json()
  ).data.list
  const existing = rows.find((row) => row.username === username)
  if (!existing) {
    const created = await context.api.post('/api/users', {
      headers: context.headers,
      data: { username, password: PASSWORD, name: `E2E ${username}`, role, department },
    })
    expect((await created.json()).code, `创建用户 ${username}`).toBe(0)
    const again = await context.api.get('/api/users?page=1&size=100', { headers: context.headers })
    const fresh: { id: number; username: string }[] = (await again.json()).data.list
    return fresh.find((row) => row.username === username)!.id
  }
  if (existing.status !== 'ENABLED') {
    await context.api.put(`/api/users/${existing.id}/status`, {
      headers: context.headers,
      data: { status: 'ENABLED', version: existing.version },
    })
  }
  await context.api.put(`/api/users/${existing.id}/password`, {
    headers: context.headers,
    data: { password: PASSWORD },
  })
  return existing.id
}

async function ensureSupplier(context: ApiContext): Promise<number> {
  const found = await context.api.get(`/api/suppliers?page=1&size=10&keyword=${SUPPLIER_CODE}`, {
    headers: context.headers,
  })
  const rows: { id: number; code: string }[] = (await found.json()).data.list
  const existing = rows.find((row) => row.code === SUPPLIER_CODE)
  if (existing) {
    return existing.id
  }
  const created = await context.api.post('/api/suppliers', {
    headers: context.headers,
    data: { code: SUPPLIER_CODE, name: 'E2E-P4 审批闭环供应商', category: 'E2E' },
  })
  const body = await created.json()
  expect(body.code, '创建供应商').toBe(0)
  return body.data.id
}

async function nextYear(context: ApiContext): Promise<number> {
  // 同 p3：本 spec 复用同一个供应商，J4 要求周期在其历史任务里唯一；
  // 只扫第一页会在任务超过 100 条后挑到已用年份（04-042）⇒ 翻页取全量。
  const used = new Set<number>()
  for (let page = 1; ; page += 1) {
    const listed = await context.api.get(`/api/tasks?page=${page}&size=100`, {
      headers: context.headers,
    })
    const body = await listed.json()
    const tasks: { year: number }[] = body.data.list
    tasks.forEach((task) => used.add(task.year))
    if (tasks.length < 100 || page * 100 >= body.data.total) {
      break
    }
  }
  let year = 2045
  while (used.has(year)) {
    year += 1
  }
  return year
}

async function seedTask(
  manager: ApiContext,
  approver1: number,
  approver2: number,
  evaluatorId: number,
): Promise<Seed> {
  const supplierId = await ensureSupplier(manager)
  const year = await nextYear(manager)
  const templates = await manager.api.get('/api/templates?page=1&size=100', {
    headers: manager.headers,
  })
  const templateList = (await templates.json()).data.list as { id: number; name: string }[]
  const template = templateList.find((item) => item.name === '季度绩效默认模板')
  if (!template) {
    throw new Error('默认模板「季度绩效默认模板」不存在，无法建任务')
  }
  const templateId: number = template.id
  const versions = await manager.api.get(`/api/templates/${templateId}/published-versions`, {
    headers: manager.headers,
  })
  const versionId: number = (await versions.json()).data[0].versionId

  const name = `${TASK_PREFIX} ${year}Q1`
  const created = await manager.api.post('/api/tasks', {
    headers: manager.headers,
    data: {
      name,
      year,
      quarter: 1,
      templateId,
      templateVersionId: versionId,
      deadline: `${year}-12-31`,
      supplierIds: [supplierId],
      approverIds: [approver1, approver2],
    },
  })
  const createdBody = await created.json()
  expect(createdBody.code, '创建任务').toBe(0)
  const taskId: number = createdBody.data

  const published = await manager.api.post(`/api/tasks/${taskId}/publish`, {
    headers: manager.headers,
    data: { version: 0 },
  })
  expect((await published.json()).code, '发布任务').toBe(0)

  const sheetsResponse = await manager.api.get(`/api/tasks/${taskId}/sheets`, {
    headers: manager.headers,
  })
  const sheets: {
    sheetId: number
    taskSupplierId: number
    status: string
    version: number
  }[] = (await sheetsResponse.json()).data
  for (const sheet of sheets) {
    const assigned = await manager.api.put(`/api/sheets/${sheet.sheetId}/assign`, {
      headers: manager.headers,
      data: { assigneeId: evaluatorId, version: sheet.version },
    })
    expect((await assigned.json()).code, '改派给评估人').toBe(0)
  }
  return {
    ...manager,
    taskId,
    taskSupplierId: sheets[0].taskSupplierId,
    taskName: name,
  }
}

/** 评估人对全部评分单写分并提交（保存→提交；驳回后重提复用同一函数）。 */
async function submitAllSheets(manager: ApiContext, evaluator: ApiContext, taskId: number): Promise<void> {
  const sheetsResponse = await manager.api.get(`/api/tasks/${taskId}/sheets`, {
    headers: manager.headers,
  })
  const sheets: { sheetId: number; status: string; version: number }[] = (
    await sheetsResponse.json()
  ).data
  expect(sheets.length, '至少一张评分单').toBeGreaterThan(0)

  for (const sheet of sheets) {
    if (sheet.status === 'SUBMITTED') {
      continue
    }
    const detail = await evaluator.api.get(`/api/sheets/${sheet.sheetId}/scores`, {
      headers: evaluator.headers,
    })
    const detailBody = await detail.json()
    expect(detailBody.code, '读评分单').toBe(0)
    const indicators: {
      indicatorId: number
      type: string
      scoreMode: string | null
      targetValue: number | null
    }[] = detailBody.data.indicators
    const entries = indicators.map((indicator) => ({
      indicatorId: indicator.indicatorId,
      rawValue:
        indicator.type === 'QUAL'
          ? 4
          : indicator.scoreMode === 'TARGET'
            ? Number(((indicator.targetValue as number) * 0.8).toFixed(2))
            : 80,
    }))
    const saved = await evaluator.api.put(`/api/sheets/${sheet.sheetId}/scores`, {
      headers: evaluator.headers,
      data: { scores: entries },
    })
    expect((await saved.json()).code, `保存评分单 ${sheet.sheetId}`).toBe(0)
    const submitted = await evaluator.api.post(`/api/sheets/${sheet.sheetId}/submit`, {
      headers: evaluator.headers,
      data: { version: detailBody.data.version },
    })
    expect((await submitted.json()).code, `提交评分单 ${sheet.sheetId}`).toBe(0)
  }
}

async function taskSupplierStatus(context: ApiContext, taskId: number): Promise<string> {
  const progress = await context.api.get(`/api/tasks/${taskId}/progress`, {
    headers: context.headers,
  })
  const rows: { status: string }[] = (await progress.json()).data.rows
  return rows[0].status
}

async function openApprovalRow(page: Page, taskName: string): Promise<void> {
  await page.goto('/approvals')
  const row = page.locator('.el-table__row').filter({ hasText: taskName }).first()
  await expect(row, '我的审批里应有本轮任务').toBeVisible()
  await row.getByRole('button', { name: '去审批' }).click()
  await expect(page).toHaveURL(/\/approvals\/\d+/)
}

test.describe('P4 审批闭环（M4）', () => {
  test('驳回 → 重提 → 两级通过 → 结果列表 → 报告快照', async ({ page }) => {
    const problems = watchConsole(page)
    const admin = await adminApi()
    const manager1Id = await ensureUser(admin, M1, 'MANAGER', 'PURCHASE')
    const manager2Id = await ensureUser(admin, M2, 'MANAGER', 'PURCHASE')
    const evaluatorId = await ensureUser(admin, EVALUATOR, 'EVALUATOR', 'QUALITY')
    await ensureUser(admin, VIEWER, 'VIEWER', 'OTHER')
    const manager1 = await loginAs(M1)
    const evaluator = await loginAs(EVALUATOR)

    const seed = await seedTask(manager1, manager1Id, manager2Id, evaluatorId)
    await submitAllSheets(manager1, evaluator, seed.taskId)
    expect(await taskSupplierStatus(manager1, seed.taskId), '全部提交后进入待审批').toBe(
      'PENDING_APPROVAL',
    )

    // ---- 第一级：驳回 ----
    await login(page, M1, PASSWORD)
    await expect(menuItem(page, '我的审批')).toBeVisible()
    await openApprovalRow(page, seed.taskName)
    await expect(page.getByText(/试算口径/).first()).toBeVisible()
    await page.getByRole('button', { name: '驳回' }).click()
    await page.getByPlaceholder('必填：说明需要评估人修改什么').fill('质量数据需复核')
    await page.getByRole('button', { name: '确认驳回' }).click()
    await expect(page.locator('.el-message').first()).toContainText('已驳回')
    await expect(page.getByText('评分中').first()).toBeVisible()
    await expect(page.locator('.approval-timeline').first()).toContainText('驳回')
    expect(await taskSupplierStatus(manager1, seed.taskId), '驳回回到评分中').toBe('SCORING')

    // ---- 重提（API）后第一级通过 ----
    await submitAllSheets(manager1, evaluator, seed.taskId)
    expect(await taskSupplierStatus(manager1, seed.taskId), '重提后回到待审批').toBe(
      'PENDING_APPROVAL',
    )
    await openApprovalRow(page, seed.taskName)
    await page.getByRole('button', { name: '通过' }).click()
    await page.getByRole('button', { name: '确定' }).click()
    await expect(page.locator('.el-message').first()).toContainText('已通过')
    await expect(page.getByText('审批中').first()).toBeVisible()
    await expect(page.locator('.approval-timeline').first()).toContainText('第 2 轮')

    // ---- 第二级（末级）通过 → 定稿 ----
    await page.getByRole('button', { name: '退出' }).click()
    await login(page, M2, PASSWORD)
    // support.login() 只负责提交表单，不等登录完成；这里必须等认证态，否则下一次 goto 会把在飞的登录请求掐掉
    await expect(menuItem(page, '我的审批')).toBeVisible()
    await openApprovalRow(page, seed.taskName)
    await page.getByRole('button', { name: '通过' }).click()
    await page.getByRole('button', { name: '确定' }).click()
    await expect(page.locator('.el-message').first()).toContainText('已通过')
    await expect(page.getByText('已生效').first()).toBeVisible()
    await expect(page.getByText('该评估单已生效')).toBeVisible()

    // ---- VIEWER：结果列表 → 报告快照 + 雷达图 + 来源 ----
    await page.getByRole('button', { name: '退出' }).click()
    await login(page, VIEWER, PASSWORD)
    await expect(menuItem(page, '结果报表')).toBeVisible()
    await menuItem(page, '结果报表').click()
    await expect(page).toHaveURL(/\/results$/)
    const taskRow = page.locator('.el-table__row').filter({ hasText: seed.taskName }).first()
    await expect(taskRow).toBeVisible()
    await taskRow.getByRole('button', { name: '查看结果' }).click()
    await expect(page).toHaveURL(/\/results\/\d+/)
    await expect(page.getByText('80.00').first()).toBeVisible()
    // R2 的排序/筛选：先筛到空，再清空恢复（场景 9）
    const supplierFilter = page.getByPlaceholder('搜索供应商')
    await supplierFilter.fill('不存在的供应商')
    await expect(page.getByText('没有匹配的供应商')).toBeVisible()
    await supplierFilter.clear()
    await expect(page.getByText('80.00').first()).toBeVisible()
    await page.getByRole('button', { name: '评估报告' }).first().click()
    await expect(page).toHaveURL(/\/reports\/\d+/)
    await expect(page.getByText('定稿快照').first()).toBeVisible()
    await expect(page.locator('.score-radar canvas').first()).toBeVisible()
    // 指标明细折叠着，展开后应能看到来源列
    await page.locator('.el-collapse-item__header').first().click()
    await expect(page.getByText('手工录入').first()).toBeVisible()

    expect(problems.csp, '不应有 CSP 违规').toEqual([])
    expect(problems.all, '控制台不应有错误').toEqual([])
  })
})
