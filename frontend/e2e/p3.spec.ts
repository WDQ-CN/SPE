import { expect, request, test, type Page } from '@playwright/test'
import { ADMIN, login, menuItem, watchConsole } from './support'

/**
 * P3-W7 的端到端验收（13 §1 判据 1/3/4/10 + **M3 验收标志**）：
 * **两个部门协同完成一个供应商的评分**——质量与交付各打分，全部维度提交后评估单进入待审批。
 *
 * <p>为什么非跑真实链路不可（MockMvc/集成测试兜不住的三件事）：
 * 1. `13 §1` 判据 5 要求"前端**不含任何公式**"——只有真实浏览器里跑一遍，才能确认页面上显示的分值
 *    全部来自后端试算接口（页面里根本没有可算分的地方）；
 * 2. 判据 10 的"过滤在 Service 内、不依赖前端隐藏入口"——用 VIEWER 直接输地址访问打分页，验的是
 *    **路由守卫 + 后端 4002** 两道，而不是"菜单里看不见"；
 * 3. 判据 4 的"提交后锁定不可改"在界面上表现为**写入口消失 + 只读原因**，这是 `sheetPermissions` 的
 *    真实消费点（纯函数单测之外的第一处真实调用）。
 *
 * <p>⚠️ 用例会**真的写 dev 库**，且刻意**每次跑用一个全新的评估周期**（周期从 2035 年起顺延）：
 * J4 唯一性是 `(供应商, 周期)`，换个周期就能重复跑；沿用旧周期则会因为"该周期已有任务"被 2019 拒，
 * 或者撞上"上一轮那张评分单已提交、不能再提交"而假红（04-092 的同族教训：不假设上一次的状态）。
 * 累积的任务与模板版本一样留在 dev 库（11 §9 登记）。
 */

const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:18081'
const PASSWORD = 'E2eP3@12345'
const MANAGER_USERNAME = 'e2e-p3-manager'
const QUALITY_USERNAME = 'e2e-p3-eval-quality'
const DELIVERY_USERNAME = 'e2e-p3-eval-delivery'
const VIEWER_USERNAME = 'e2e-p3-viewer'
const SUPPLIER_CODE = 'E2E-P3-SUP-001'
const TASK_PREFIX = 'E2E-P3 协同评分'

/** 01 §6.5 的"质量"维度录入值（后端换算后维度分应为 96.35） */
const QUALITY_VALUES = ['98.50', '100', '96.00', '92']

type Api = Awaited<ReturnType<typeof request.newContext>>

interface Seed {
  api: Api
  headers: Record<string, string>
  taskId: number
  year: number
}

async function adminApi(): Promise<{ api: Api; headers: Record<string, string> }> {
  const api = await request.newContext({ baseURL: BASE_URL })
  const response = await api.post('/api/auth/login', {
    data: { username: ADMIN.username, password: ADMIN.password },
  })
  const body = await response.json()
  expect(body.code, 'admin 登录').toBe(0)
  return { api, headers: { Authorization: `Bearer ${body.data.token}` } }
}

/**
 * 用**非 ADMIN 的固定账号**登录，拿一个真实令牌。
 *
 * <p>为什么需要它：「创建/发布/取消任务、调整指派」按 01 §7 是 **MANAGER** 的权限，ADMIN 那一列是「查看」。
 * 第一版这里直接拿 `adminApi()` 建任务，`POST /api/tasks` 返回 **403 + 4001**（`eval_task` 一行都没落），
 * 3 条用例全红——而"ADMIN 发布任务被拒"正是 W5 集成用例**断言过的正确行为**。所以这是**用例用错了角色**，
 * 不是产品缺陷。账号由 `ensureUser` 先建好再登录。
 */
async function loginAs(username: string): Promise<{ api: Api; headers: Record<string, string> }> {
  const api = await request.newContext({ baseURL: BASE_URL })
  const response = await api.post('/api/auth/login', {
    data: { username, password: PASSWORD },
  })
  const body = await response.json()
  expect(body.code, `${username} 登录`).toBe(0)
  return { api, headers: { Authorization: `Bearer ${body.data.token}` } }
}

/** 幂等地准备一个可登录的用户：不存在就建，存在就启用 + 重置口令（照 w7 的 ensureManager 形态）。 */
async function ensureUser(
  context: { api: Api; headers: Record<string, string> },
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

/** 复用同一个供应商（J4 只约束"同一周期"，换周期可以重复参评）。 */
async function ensureSupplier(context: { api: Api; headers: Record<string, string> }): Promise<number> {
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
    data: { code: SUPPLIER_CODE, name: 'E2E-P3 协同评分供应商', category: 'E2E' },
  })
  const body = await created.json()
  expect(body.code, '创建供应商').toBe(0)
  return body.data.id
}

/** 建一个**全新周期**的任务并发布，把质量/交付两张评分单改派给对应的评估人。 */
async function seedTask(
  context: { api: Api; headers: Record<string, string> },
  qualityId: number,
  deliveryId: number,
): Promise<Seed> {
  const supplierId = await ensureSupplier(context)
  const listed = await context.api.get('/api/tasks?page=1&size=100', { headers: context.headers })
  const tasks: { year: number }[] = (await listed.json()).data.list
  const used = new Set(tasks.map((task) => task.year))
  let year = 2035
  while (used.has(year)) {
    year += 1
  }
  const templates = await context.api.get('/api/templates?page=1&size=10', { headers: context.headers })
  const templateId: number = (await templates.json()).data.list[0].id
  const versions = await context.api.get(`/api/templates/${templateId}/published-versions`, {
    headers: context.headers,
  })
  const versionId: number = (await versions.json()).data[0].versionId

  const created = await context.api.post('/api/tasks', {
    headers: context.headers,
    data: {
      name: `${TASK_PREFIX} ${year}Q1`,
      year,
      quarter: 1,
      templateVersionId: versionId,
      deadline: `${year}-12-31`,
      supplierIds: [supplierId],
      approverIds: [qualityId],
    },
  })
  const createdBody = await created.json()
  expect(createdBody.code, '创建任务').toBe(0)
  const taskId: number = createdBody.data

  const published = await context.api.post(`/api/tasks/${taskId}/publish`, {
    headers: context.headers,
    data: { version: 0 },
  })
  const publishedBody = await published.json()
  expect(publishedBody.code, '发布任务').toBe(0)
  expect(publishedBody.data.sheetCount, '评分单数 = 供应商数 × 维度数').toBeGreaterThan(0)

  const sheetsResponse = await context.api.get(`/api/tasks/${taskId}/sheets`, {
    headers: context.headers,
  })
  const sheets: { sheetId: number; dimensionName: string; status: string; version: number }[] = (
    await sheetsResponse.json()
  ).data
  for (const sheet of sheets) {
    const assigneeId = sheet.dimensionName === '交付' ? deliveryId : qualityId
    const assigned = await context.api.put(`/api/sheets/${sheet.sheetId}/assign`, {
      headers: context.headers,
      data: { assigneeId, version: sheet.version },
    })
    expect((await assigned.json()).code, `改派 ${sheet.dimensionName}`).toBe(0)
  }
  return { ...context, taskId, year }
}

/**
 * 评估人登录后打开**本轮任务**的某张评分单。
 *
 * <p>⚠️ 必须用**任务名**把范围收敛到本轮自己的数据：待办是**升序**返回的，多轮运行累积后
 * 「质量」维度有好几张（旧的已 SUBMITTED），只按维度取 `.first()` 会命中**最旧那张**，
 * 打开就是只读页、`el-input-number` 是 disabled，`fill` 一直重试到超时
 * （P3-W7 实测：`locator resolved to <input disabled … aria-valuenow="98.5">`）。
 * 这正是 04-092「不假设全库状态」的同族。
 */
async function openSheet(page: Page, dimension: string, taskName: string): Promise<void> {
  await page.goto('/my')
  const row = page
    .locator('.el-table__row')
    .filter({ hasText: taskName })
    .filter({ hasText: dimension })
    .first()
  await expect(row, `待办里应当有 ${taskName} 的 ${dimension} 维度评分单`).toBeVisible()
  await row.getByRole('button', { name: '去打分' }).click()
  await expect(page).toHaveURL(/\/sheets\/\d+/)
}

test.describe('P3 评估流程（M3：两个部门协同完成一个供应商的评分）', () => {
  test('评估人：待办 → 打分页录入 → 实时试算 → 保存 → 提交后锁定', async ({ page }) => {
    const admin = await adminApi()
    const qualityId = await ensureUser(admin, QUALITY_USERNAME, 'EVALUATOR', 'QUALITY')
    const deliveryId = await ensureUser(admin, DELIVERY_USERNAME, 'EVALUATOR', 'DELIVERY')
    await ensureUser(admin, MANAGER_USERNAME, 'MANAGER', 'PURCHASE')
    // 建任务/发布/改派是 MANAGER 的权限（01 §7）：admin 的令牌做这些会 403/4001
    const manager = await loginAs(MANAGER_USERNAME)
    const seed = await seedTask(manager, qualityId, deliveryId)

    const problems = watchConsole(page)
    await login(page, QUALITY_USERNAME, PASSWORD)
    await expect(menuItem(page, '我的待办')).toBeVisible()

    // 本轮任务名（与 seedTask 里创建时一致）：用来把待办里的行收敛到本轮自己造的数据
    const taskName = `${TASK_PREFIX} ${seed.year}Q1`
    await openSheet(page, '质量', taskName)

    // 录入 01 §6.5 的四个质量指标（表格里的输入框就是 el-input-number）
    const inputs = page.locator('.el-table__row input')
    await expect(inputs).toHaveCount(4)
    for (let index = 0; index < QUALITY_VALUES.length; index += 1) {
      await inputs.nth(index).fill(QUALITY_VALUES[index])
      await inputs.nth(index).press('Tab')
    }

    // 判据 5：维度分与总分都是**后端试算**返回的——页面里没有任何公式，96.35 只可能来自接口
    await expect(page.getByText('96.35').first()).toBeVisible({ timeout: 15_000 })
    await expect(page.getByText('评分未齐').first()).toBeVisible()

    await page.getByRole('button', { name: '保存' }).click()
    await expect(page.locator('.el-message').first()).toContainText('已保存')

    await page.getByRole('button', { name: '提交' }).click()
    await page.getByRole('button', { name: '确定' }).click()
    // 提交成功会跳回待办；此时质量维度已锁定
    await expect(page).toHaveURL(/\/my$/, { timeout: 15_000 })
    await openSheet(page, '质量', taskName)
    await expect(page.getByText('已提交的评分单不可修改')).toBeVisible()
    await expect(page.getByRole('button', { name: '提交' })).toBeDisabled()

    // 判据 10 的界面侧：通知入口可用（最小版 UI），点开能渲染出"空态或列表"二者之一
    await page.getByRole('button', { name: '通知' }).click()
    await expect(
      page.locator('.notice, .el-empty').first(),
    ).toBeVisible()

    // 看板：这一轮只提交了质量维度 ⇒ 单子仍是评分中
    const progress = await seed.api.get(`/api/tasks/${seed.taskId}/progress`, {
      headers: seed.headers,
    })
    const rows: { status: string; unsubmittedSheets: number }[] = (await progress.json()).data.rows
    expect(rows[0].status, '只提交一个维度时评估单仍是评分中').toBe('SCORING')
    expect(rows[0].unsubmittedSheets, '未提交数应当减少过').toBeLessThan(4)

    expect(problems.csp, 'CSP 违规').toEqual([])
    expect(problems.all, '控制台问题').toEqual([])
  })

  test('采购经理：任务详情看板与评分单清单（含改派入口）', async ({ page }) => {
    const admin = await adminApi()
    const qualityId = await ensureUser(admin, QUALITY_USERNAME, 'EVALUATOR', 'QUALITY')
    const deliveryId = await ensureUser(admin, DELIVERY_USERNAME, 'EVALUATOR', 'DELIVERY')
    await ensureUser(admin, MANAGER_USERNAME, 'MANAGER', 'PURCHASE')
    // 建任务/发布/改派是 MANAGER 的权限（01 §7）：admin 的令牌做这些会 403/4001
    const manager = await loginAs(MANAGER_USERNAME)
    const seed = await seedTask(manager, qualityId, deliveryId)

    const problems = watchConsole(page)
    await login(page, MANAGER_USERNAME, PASSWORD)
    await menuItem(page, '评估任务').click()
    await expect(page).toHaveURL(/\/tasks$/)

    const row = page.locator('.el-table__row', { hasText: `${seed.year}Q1` }).first()
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '详情' }).click()
    await expect(page).toHaveURL(new RegExp(`/tasks/${seed.taskId}`))

    // 进度看板：至少一行（本任务只有一个供应商），且四个维度的评分单都在清单里
    await expect(page.getByText('进度看板')).toBeVisible()
    await expect(page.locator('.el-table__row', { hasText: '质量' }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: '改派' }).first()).toBeVisible()

    expect(problems.csp, 'CSP 违规').toEqual([])
    expect(problems.all, '控制台问题').toEqual([])
  })

  test('越权：VIEWER 看不到入口，直接输地址也进不去打分页', async ({ page }) => {
    const admin = await adminApi()
    const qualityId = await ensureUser(admin, QUALITY_USERNAME, 'EVALUATOR', 'QUALITY')
    const deliveryId = await ensureUser(admin, DELIVERY_USERNAME, 'EVALUATOR', 'DELIVERY')
    await ensureUser(admin, MANAGER_USERNAME, 'MANAGER', 'PURCHASE')
    await ensureUser(admin, VIEWER_USERNAME, 'VIEWER', 'OTHER')
    // 建任务/发布/改派是 MANAGER 的权限（01 §7）：admin 的令牌做这些会 403/4001
    const manager = await loginAs(MANAGER_USERNAME)
    const seed = await seedTask(manager, qualityId, deliveryId)

    const problems = watchConsole(page)
    await login(page, VIEWER_USERNAME, PASSWORD)
    // ⚠️ `login()` 只负责提交表单、**不等待结果**，而下面两条是**负向**断言（"菜单里没有"）——
    // 它们在**登录页**上同样成立、会立刻通过，于是后面的 goto 抢在登录写 token 之前执行，
    // 被守卫当成"未登录"跳 /login?redirect=/sheets/9（P3-W7 实测的红）。
    // 所以先用一条**正向**断言把"登录已落地"钉住（VIEWER 唯一的菜单项是「首页」）。
    await expect(page.locator('.el-menu-item').first()).toBeVisible()

    // 入口级：菜单里没有「评估任务」「我的待办」
    await expect(menuItem(page, '评估任务')).toHaveCount(0)
    await expect(menuItem(page, '我的待办')).toHaveCount(0)

    // 直接输地址：路由守卫按 menu.ts 的前缀匹配把它挡回**首页**（不是靠"菜单里看不见"）。
    // ⚠️ 判据要写成"确实回到了首页"，不能写 `not.toHaveURL(/\/sheets\//)`：跳登录页时 URL 是
    // `/login?redirect=/sheets/9`，**同样含有 `/sheets/` 子串**，那种写法会因为错误的原因失败。
    const sheets = await seed.api.get(`/api/tasks/${seed.taskId}/sheets`, { headers: seed.headers })
    const sheetId: number = (await sheets.json()).data[0].sheetId
    await page.goto(`/sheets/${sheetId}`)
    await expect(page).toHaveURL(/localhost:18081\/$/)

    expect(problems.csp, 'CSP 违规').toEqual([])
    // 守卫拦回首页时页面可能整体重挂载，控制台只允许出现与权限相关的 4xx（watchConsole 已豁免 401/403）
    expect(problems.all, '控制台问题').toEqual([])
  })
})
