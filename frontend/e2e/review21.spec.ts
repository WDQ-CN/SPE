import { expect, request, test, type Page } from '@playwright/test'

import { ADMIN, login, watchConsole } from './support'

/**
 * `21-深审修复方案` 的运行态验收：审批按钮按 actableNow 显隐、4003 走全局提示、
 * 驳回后「第 0 级」消失、时间轴显示本地时间、当前轮占位、EVALUATOR 报告页返回链、
 * ADMIN 写按钮隐藏、任务详情分页、审批人下拉只剩 MANAGER/ADMIN。
 *
 * <p>种子数据走真实 API（建任务/发布/改派/写分/提交），UI 只驱动被验收的交互；
 * 每次跑用**唯一供应商 + 唯一用户名**（时间戳后缀），因此同一个 `year` 不触发 J4 唯一性。
 */
const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:18081'
const PASSWORD = 'W6Check@12345'

type Api = Awaited<ReturnType<typeof request.newContext>>
interface Ctx {
  api: Api
  headers: Record<string, string>
}

async function adminCtx(): Promise<Ctx> {
  const api = await request.newContext({ baseURL: BASE_URL })
  const body = await (
    await api.post('/api/auth/login', {
      data: { username: ADMIN.username, password: ADMIN.password },
    })
  ).json()
  expect(body.code, 'admin 登录').toBe(0)
  return { api, headers: { Authorization: `Bearer ${body.data.token}` } }
}

async function loginApi(username: string): Promise<Ctx> {
  const api = await request.newContext({ baseURL: BASE_URL })
  const body = await (
    await api.post('/api/auth/login', { data: { username, password: PASSWORD } })
  ).json()
  expect(body.code, `${username} 登录`).toBe(0)
  return { api, headers: { Authorization: `Bearer ${body.data.token}` } }
}

async function findUser(
  admin: Ctx,
  username: string,
): Promise<{ id: number; username: string; status: string; version: number } | null> {
  // 账号总数早已超过一页（分享库 + 每次 e2e 新增）⇒ 必须翻页找，不能只看 page=1
  for (let pageNo = 1; pageNo <= 20; pageNo += 1) {
    const listed = await admin.api.get(`/api/users?page=${pageNo}&size=100`, {
      headers: admin.headers,
    })
    const rows: { id: number; username: string; status: string; version: number }[] = (
      await listed.json()
    ).data.list
    const found = rows.find((row) => row.username === username)
    if (found) {
      return found
    }
    if (rows.length < 100) {
      return null
    }
  }
  return null
}

async function ensureUser(admin: Ctx, username: string, role: string): Promise<number> {
  const existing = await findUser(admin, username)
  if (existing) {
    return existing.id
  }
  const created = await admin.api.post('/api/users', {
    headers: admin.headers,
    data: {
      username,
      password: PASSWORD,
      name: `E2E ${username}`,
      role,
      department: role === 'EVALUATOR' ? 'QUALITY' : 'PURCHASE',
    },
  })
  expect((await created.json()).code, `创建用户 ${username}`).toBe(0)
  const fresh = await findUser(admin, username)
  expect(fresh, `创建后必须能找到用户 ${username}`).toBeTruthy()
  return fresh!.id
}

async function submitAllSheets(manager: Ctx, evaluator: Ctx, taskId: number): Promise<void> {
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
      rawValue:
        indicator.type === 'QUAL'
          ? 4
          : indicator.scoreMode === 'TARGET'
            ? Number(((indicator.targetValue as number) * 0.8).toFixed(2))
            : 80,
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

const pad = (value: number): string => String(value).padStart(2, '0')
const formatUtcLocal = (value: string): string => {
  const parsed = new Date(`${value}Z`)
  return (
    `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ` +
    `${pad(parsed.getHours())}:${pad(parsed.getMinutes())}:${pad(parsed.getSeconds())}`
  )
}

async function openDetail(page: Page, taskId: number): Promise<void> {
  await page.goto(`/tasks/${taskId}`)
  await expect(page.getByText('进度看板')).toBeVisible()
}

/** support.login() 只提交表单、不等登录完成（p4 的教训）：必须等认证态再 goto。 */
async function loginUi(page: Page, username: string, password: string): Promise<void> {
  await login(page, username, password)
  await expect(page.getByRole('button', { name: '退出' })).toBeVisible()
}

test('21 修复批五个点的运行态验收', async ({ page }) => {
  const problems = watchConsole(page)
  const admin = await adminCtx()
  const suffix = String(Date.now()).slice(-6)
  const evaluatorName = `w6-check-eval-${suffix}`
  const m1Name = `w6-check-mgr1-${suffix}`
  const m2Name = `w6-check-mgr2-${suffix}`
  const m1Id = await ensureUser(admin, m1Name, 'MANAGER')
  const m2Id = await ensureUser(admin, m2Name, 'MANAGER')
  const evaluatorId = await ensureUser(admin, evaluatorName, 'EVALUATOR')
  const m1 = await loginApi(m1Name)
  const m2 = await loginApi(m2Name)
  const evaluator = await loginApi(evaluatorName)

  // ---- 种子：任务（审批链 = M1 → M2）→ 发布 → 改派 → 全部提交 ----
  const supplier = await (
    await m1.api.post('/api/suppliers', {
      headers: m1.headers,
      data: { code: `W6-CHK-${suffix}`, name: `W6 核查供应商 ${suffix}`, category: 'W6' },
    })
  ).json()
  expect(supplier.code, '创建供应商').toBe(0)
  const supplierId: number = supplier.data.id
  const templates = await (
    await m1.api.get('/api/templates?page=1&size=100', { headers: m1.headers })
  ).json()
  // 按名称取默认模板（M5 的 V999 会新增「演示模板（M5）」，list[0] 不再稳定）
  const template = (templates.data.list as { id: number; name: string }[]).find(
    (item) => item.name === '季度绩效默认模板',
  )
  expect(template, '默认模板必须存在').toBeTruthy()
  const templateId: number = template!.id
  const versions = await (
    await m1.api.get(`/api/templates/${templateId}/published-versions`, { headers: m1.headers })
  ).json()
  const versionId: number = versions.data[0].versionId
  const year = 2085
  const created = await (
    await m1.api.post('/api/tasks', {
      headers: m1.headers,
      data: {
        name: `W6 核查任务 ${suffix}`,
        year,
        quarter: 1,
        templateId,
        templateVersionId: versionId,
        deadline: `${year}-12-31`,
        supplierIds: [supplierId],
        approverIds: [m1Id, m2Id],
      },
    })
  ).json()
  expect(created.code, '创建任务').toBe(0)
  const taskId: number = created.data
  const published = await (
    await m1.api.post(`/api/tasks/${taskId}/publish`, {
      headers: m1.headers,
      data: { version: 0 },
    })
  ).json()
  expect(published.code, '发布任务').toBe(0)
  const sheets = (
    await (await m1.api.get(`/api/tasks/${taskId}/sheets`, { headers: m1.headers })).json()
  ).data as { sheetId: number; taskSupplierId: number; version: number }[]
  for (const sheet of sheets) {
    const assigned = await (
      await m1.api.put(`/api/sheets/${sheet.sheetId}/assign`, {
        headers: m1.headers,
        data: { assigneeId: evaluatorId, version: sheet.version },
      })
    ).json()
    expect(assigned.code, '改派').toBe(0)
  }
  const tsId = sheets[0].taskSupplierId
  await submitAllSheets(m1, evaluator, taskId)

  // ---- ① 审批人下拉只剩 MANAGER/ADMIN（打开下拉后按关键字过滤，定位到它自己的 listbox） ----
  await loginUi(page, m1Name, PASSWORD)
  await page.goto('/tasks')
  await page.getByRole('button', { name: '新建任务' }).click()
  await expect(page.locator('.el-dialog:visible')).toBeVisible()
  const approverItem = page.locator('.el-form-item:has-text("审批链")').first()
  await approverItem.locator('.el-select__wrapper').first().click({ force: true })
  const approverInput = approverItem.locator('.el-select input').first()
  await approverInput.fill('w6-check')
  const listboxId = await approverInput.getAttribute('aria-controls')
  expect(listboxId, '审批链 select 必须暴露 aria-controls').toBeTruthy()
  const options = page.locator(`#${listboxId} .el-select-dropdown__item`)
  await expect(options.filter({ hasText: m1Name })).toBeVisible()
  await expect(options.filter({ hasText: evaluatorName })).toHaveCount(0)
  await page.getByRole('button', { name: '取消' }).click()

  // ---- ② 非当前审批人（M2）打开审批页：按钮隐藏 + 提示 ----
  await page.getByRole('button', { name: '退出' }).click()
  await loginUi(page, m2Name, PASSWORD)
  await page.goto(`/approvals/${tsId}`)
  await expect(page.getByText('当前节点不由你审批')).toBeVisible()
  await expect(page.getByRole('button', { name: '通过' })).toHaveCount(0)

  // ---- ③ 当前审批人（M1）：按钮可见 ----
  await page.getByRole('button', { name: '退出' }).click()
  await loginUi(page, m1Name, PASSWORD)
  await page.goto(`/approvals/${tsId}`)
  await expect(page.getByRole('button', { name: '通过' })).toBeVisible()
  const report = (
    await (
      await m1.api.get(`/api/task-suppliers/${tsId}/report`, { headers: m1.headers })
    ).json()
  ).data

  // ---- ④ 4003 的反馈：http 拦截器对 4xxx 走全局提示（页面内 alert 只服务 2xxx，见 useSubmit） ----
  const approved = await (
    await m1.api.post(`/api/task-suppliers/${tsId}/approve`, {
      headers: m1.headers,
      data: { version: report.version },
    })
  ).json()
  expect(approved.code, 'M1 通过').toBe(0)
  await page.getByRole('button', { name: '通过' }).click()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(
    page.locator('.el-message').filter({ hasText: '当前节点不属于你' }),
  ).toBeVisible()

  // ---- ④b 时间轴显示本地时间（记录只在通过/驳回后才产生） ----
  const afterApprove = (
    await (
      await m1.api.get(`/api/task-suppliers/${tsId}/report`, { headers: m1.headers })
    ).json()
  ).data
  expect(afterApprove.timeline, '第一级通过后应有一条记录').toHaveLength(1)
  const expectedTime = formatUtcLocal(afterApprove.timeline[0].createdAt)
  await page.reload()
  await expect(page.locator('.approval-timeline').first()).toContainText(expectedTime)

  // ---- ⑤ M2 驳回 → 「第 0 级」不再出现；任务详情当前节点显示 — ----
  const reportAtM2 = (
    await (
      await m2.api.get(`/api/task-suppliers/${tsId}/report`, { headers: m2.headers })
    ).json()
  ).data
  const rejected = await (
    await m2.api.post(`/api/task-suppliers/${tsId}/reject`, {
      headers: m2.headers,
      data: { version: reportAtM2.version, comment: 'W6 核查：请复核' },
    })
  ).json()
  expect(rejected.code, 'M2 驳回').toBe(0)
  await page.goto(`/approvals/${tsId}`)
  await expect(page.getByText('第 2 轮')).toBeVisible()
  await expect(page.getByText('第 0 级')).toHaveCount(0)
  await openDetail(page, taskId)
  const row = page.locator('.el-table__row').filter({ hasText: `W6 核查供应商 ${suffix}` }).first()
  await expect(row.locator('td').nth(2)).toHaveText('—')
  await expect(page.locator('.el-pagination')).toHaveCount(2)

  // ---- ⑥ 重提后当前轮无记录：时间轴出现占位 ----
  await submitAllSheets(m1, evaluator, taskId)
  await page.goto(`/approvals/${tsId}`)
  await expect(page.getByText('本轮尚无审批记录')).toBeVisible()

  // ---- ⑦ EVALUATOR 报告页：返回按钮不再跳 /results ----
  await page.getByRole('button', { name: '退出' }).click()
  await loginUi(page, evaluatorName, PASSWORD)
  await page.goto(`/reports/${tsId}`)
  await expect(page.getByRole('button', { name: '返回', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '返回结果列表' })).toHaveCount(0)
  await page.getByRole('button', { name: '返回', exact: true }).click()
  await expect(page).not.toHaveURL(/\/results/)

  // ---- ⑧ ADMIN 任务页：无 MANAGER-only 写按钮 ----
  await page.getByRole('button', { name: '退出' }).click()
  await loginUi(page, ADMIN.username, ADMIN.password)
  await page.goto('/tasks')
  await expect(page.getByText('任务列表')).toBeVisible()
  await expect(page.getByRole('button', { name: '新建任务' })).toHaveCount(0)

  expect(problems.all, '控制台不应有错误').toEqual([])
  expect(problems.csp, '不应有 CSP 违规').toEqual([])
})
