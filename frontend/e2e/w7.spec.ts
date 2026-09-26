import { expect, request, test, type Page } from '@playwright/test'
import { ADMIN, menuItem, watchConsole } from './support'

/**
 * P2-W4 的端到端验收（12 §3-W4）：**ADMIN 走通「看默认模板 → 改权重 → 保存 → 发布新版本」，
 * MANAGER 只读**。
 *
 * <p>为什么必须真实跑一遍：这条流程里有三件事**只有真实部署链才验得到**——
 * 1. 默认模板是 V8 种入的 **PUBLISHED v1**，所以"改它的维度"必然触发**自动复制**；前端要据此
 *    `replace` 到新草稿并重新拉整棵树（E5/R-5）。MockMvc 里这条是通过断言 `WriteResult` 覆盖的，
 *    这里验的是**界面真的换了版本**；
 * 2. 权重闸门要允许**保存**、只拦**发布**（E4）；
 * 3. MANAGER 的只读既要在界面上没有入口，也要被后端 4xxx 挡住（01 §7）。
 *
 * <p>⚠️ 用例会**真的写 dev 库**：每跑一次就给默认模板添一个已发布版本。所以断言刻意**不假设版本号**
 * （只断言"多了一个已发布版本"与"URL 换到了新版本"），重复执行也不会失败。
 */

const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:18081'
const DEFAULT_TEMPLATE = '季度绩效默认模板'
const PROBE_PASSWORD = 'E2eTest@12345'
const MANAGER_USERNAME = 'e2e-w7-manager'

async function adminApi() {
  const api = await request.newContext({ baseURL: BASE_URL })
  const response = await api.post('/api/auth/login', {
    data: { username: ADMIN.username, password: ADMIN.password },
  })
  const body = await response.json()
  expect(body.code, 'admin 登录').toBe(0)
  return { api, headers: { Authorization: `Bearer ${body.data.token}` } }
}

/** 幂等地准备一个可登录的 MANAGER：不存在就建，存在就启用 + 重置口令。 */
async function ensureManager(
  context: Awaited<ReturnType<typeof adminApi>>,
): Promise<void> {
  const listed = await context.api.get('/api/users?page=1&size=100', { headers: context.headers })
  const rows: { id: number; username: string; status: string; version: number }[] = (
    await listed.json()
  ).data.list
  const existing = rows.find((row) => row.username === MANAGER_USERNAME)

  if (!existing) {
    const created = await context.api.post('/api/users', {
      headers: context.headers,
      data: {
        username: MANAGER_USERNAME,
        password: PROBE_PASSWORD,
        name: 'W4 模板只读验收账号',
        role: 'MANAGER',
        department: 'PURCHASE',
      },
    })
    expect((await created.json()).code, '创建 MANAGER 账号').toBe(0)
    return
  }

  if (existing.status === 'DISABLED') {
    await context.api.put(`/api/users/${existing.id}/status`, {
      headers: context.headers,
      data: { status: 'ENABLED', version: existing.version },
    })
  }
  await context.api.put(`/api/users/${existing.id}/password`, {
    headers: context.headers,
    data: { newPassword: PROBE_PASSWORD },
  })
}

async function disableManager(context: Awaited<ReturnType<typeof adminApi>>): Promise<void> {
  const listed = await context.api.get('/api/users?page=1&size=100', { headers: context.headers })
  const rows: { id: number; username: string; status: string; version: number }[] = (
    await listed.json()
  ).data.list
  const existing = rows.find((row) => row.username === MANAGER_USERNAME)
  if (!existing || existing.status === 'DISABLED') {
    return
  }
  await context.api.put(`/api/users/${existing.id}/status`, {
    headers: context.headers,
    data: { status: 'DISABLED', version: existing.version },
  })
}

async function login(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login')
  await page.getByPlaceholder('请输入账号').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.locator('.el-menu-item').first()).toBeVisible()
}

/** 当前 URL 里的版本 id —— 自动复制前后就靠它判断"真的换了版本" */
function versionIdOf(page: Page): number {
  const matched = /\/templates\/(\d+)/.exec(page.url())
  expect(matched, `URL 里应当有版本 id：${page.url()}`).not.toBeNull()
  return Number(matched![1])
}

/** 在维度对话框里把权重改成新值（el-input-number 要先清空，直接 fill 会追加到原值后面） */
async function setWeight(page: Page, value: string): Promise<void> {
  const input = page.locator('.el-dialog:visible .el-input-number input').first()
  await input.fill('')
  await input.fill(value)
  await input.blur()
}

test('W4 判据：ADMIN 改已发布版本 → 自动复制出新草稿 → 再发布新版本', async ({ page }) => {
  const console_ = watchConsole(page)
  const context = await adminApi()

  // ⚠️ **本用例自己造数据，不碰默认模板**。第一版我拿默认模板（V8 的已发布 v1）当输入，
  // 而那次失败的运行已经把它改了（自动复制出一个草稿），于是第二次跑进来看到的是草稿而不是
  // 已发布版本，期望全部落空——正是「不要假设全表状态、用本用例自己建立的数据」那条（04-064）。
  // 现在维度/指标/发布都通过 API 现建现发，UI 只负责验**它该验的交互**（与 w5 的"API 建号、UI 验"同一模式）。
  const name = `W4 E2E 模板 ${Date.now()}`
  const createdBody = await (
    await context.api.post('/api/templates', { headers: context.headers, data: { name } })
  ).json()
  expect(createdBody.code, '建模板').toBe(0)
  const templateId: number = createdBody.data.id
  const versionId: number = createdBody.data.latestVersionId

  const addDimension = await context.api.post(`/api/template-versions/${versionId}/dimensions`, {
    headers: context.headers,
    data: { name: '质量', weight: 100, sortNo: 1 },
  })
  expect((await addDimension.json()).code, '加维度').toBe(0)
  const treeBody = await (
    await context.api.get(`/api/template-versions/${versionId}`, { headers: context.headers })
  ).json()
  const dimensionId: number = treeBody.data.dimensions[0].id

  const addIndicator = await context.api.post(`/api/dimensions/${dimensionId}/indicators`, {
    headers: context.headers,
    data: {
      name: '合格率',
      code: 'W4_E2E_A',
      weight: 100,
      type: 'QUANT',
      scoreMode: 'RATIO',
      unit: '%',
      sortNo: 1,
    },
  })
  expect((await addIndicator.json()).code, '加指标').toBe(0)

  const publish = await context.api.post(`/api/template-versions/${versionId}/publish`, {
    headers: context.headers,
    data: { version: 0 },
  })
  expect((await publish.json()).code, '发布 v1').toBe(0)
  const publishedBefore = await (
    await context.api.get(`/api/templates/${templateId}/published-versions`, {
      headers: context.headers,
    })
  ).json()
  expect(publishedBefore.data.length, '刚发布完应当有 1 个已发布版本').toBe(1)

  try {
    // 1) 进编辑器：v1 已发布 → 提示不可修改 + 改动会自动复制，发布按钮禁用
    await login(page, ADMIN.username, ADMIN.password)
    await page.goto(`/templates/${versionId}`)
    await expect(page).toHaveURL(new RegExp(`/templates/${versionId}$`))
    await expect(page.getByText('已发布、不可修改')).toBeVisible()
    await expect(page.getByRole('button', { name: /^发布 v/ })).toBeDisabled()

    // 2) 改已发布版本的维度 → 必须触发自动复制：URL 换到新草稿
    const quality = page.locator('.editor__dimension', { hasText: '质量' })
    await quality.getByRole('button', { name: '改维度' }).click()
    await setWeight(page, '80')
    await page.locator('.el-dialog:visible').getByRole('button', { name: '保存' }).click()

    await expect
      .poll(() => versionIdOf(page), { message: '改已发布版本后应换到新草稿' })
      .not.toBe(versionId)
    await expect(page.locator('.editor__status')).toContainText('草稿')
    // 新草稿里质量是 80 → 维度合计 80，提示"还差 20"，且**保存成功**（E4：只拦发布不拦保存）
    await expect(page.getByText('还差 20')).toBeVisible()
    await expect(page.getByRole('button', { name: /^发布 v/ })).toBeDisabled()

    // 3) 发布即冻结：原 v1 的内容一点没变（这是"复制新草稿"而不是"就地改"的直接证据）
    const v1Body = await (
      await context.api.get(`/api/template-versions/${versionId}`, { headers: context.headers })
    ).json()
    expect(v1Body.data.status, 'v1 仍应是已发布').toBe('PUBLISHED')
    expect(v1Body.data.dimensions[0].weight, 'v1 的权重不该被改动').toBe(100)

    // 4) 把权重改回 100 → 发布闸门解锁 → 发布，于是又多一个已发布版本
    await quality.getByRole('button', { name: '改维度' }).click()
    await setWeight(page, '100')
    await page.locator('.el-dialog:visible').getByRole('button', { name: '保存' }).click()
    await expect(page.getByText('权重合计正确，可以发布')).toBeVisible()
    await page.getByRole('button', { name: /^发布 v/ }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '确定' }).click()
    await expect(page.locator('.editor__status')).toContainText('已发布')

    // 5) 用接口核对：确实多了一个已发布版本（不假设版本号，重复执行也不会失败）
    await expect
      .poll(async () => {
        const listed = await context.api.get(`/api/templates/${templateId}/published-versions`, {
          headers: context.headers,
        })
        return (await listed.json()).data.length
      })
      .toBe(2)

    // 6) 列表页仍可用（不按排位找新模板：列表默认每页 10 条，重复执行后总有被挤到第二页的一天，
    // 那是"假设全表状态"的另一种写法；该模板存在与否已由上面按 id 的接口断言覆盖）
    await page.goto('/templates')
    await expect(page.locator('.el-table__row').first()).toBeVisible()
    await expect(page.getByRole('button', { name: '新建模板' })).toBeVisible()
  } finally {
    await context.api.dispose()
  }

  expect(console_.all, `控制台报错：\n${console_.all.join('\n')}`).toEqual([])
})

test('W4 判据：MANAGER 只读——模板页无写入口，且直连写接口被后端拒绝', async ({ page }) => {
  const context = await adminApi()
  try {
    await ensureManager(context)
  } finally {
    // 账号已就绪即可，清理放到用例末尾
  }

  try {
    const console_ = watchConsole(page)
    await login(page, MANAGER_USERNAME, PROBE_PASSWORD)
    await menuItem(page, '评估模板').click()

    const row = page.locator('.el-table__row', { hasText: DEFAULT_TEMPLATE })
    await expect(row).toBeVisible()
    // 菜单可见、列表可看 → 但写入口不存在
    await expect(page.getByRole('button', { name: '新建模板' })).toHaveCount(0)
    await expect(row.getByRole('button', { name: '重命名' })).toHaveCount(0)
    await expect(page.getByText('当前角色为只读')).toBeVisible()

    await row.getByRole('button', { name: '编辑版本' }).click()
    await expect(page).toHaveURL(/\/templates\/\d+/)
    for (const name of ['加指标', '改维度', '删维度', '添加维度']) {
      await expect(page.getByRole('button', { name }), `MANAGER 不应看到「${name}」`).toHaveCount(0)
    }
    await expect(page.getByRole('button', { name: /^发布 v/ })).toHaveCount(0)

    // 真正的边界在后端：MANAGER 直连写接口必须 403 + 4001（不只是界面隐藏）
    const managerLogin = await context.api.post('/api/auth/login', {
      data: { username: MANAGER_USERNAME, password: PROBE_PASSWORD },
    })
    const managerToken: string = (await managerLogin.json()).data.token
    const forbidden = await context.api.post('/api/templates', {
      headers: { Authorization: `Bearer ${managerToken}` },
      data: { name: 'MANAGER 不该建出来的模板' },
    })
    expect(forbidden.status(), 'MANAGER 建模板').toBe(403)
    expect((await forbidden.json()).code).toBe(4001)

    // 但读接口是允许的（01 §7 给 MANAGER「查看」）
    const readable = await context.api.get('/api/templates?page=1&size=10', {
      headers: { Authorization: `Bearer ${managerToken}` },
    })
    expect(readable.status(), 'MANAGER 读模板列表').toBe(200)

    expect(console_.all, `控制台报错：\n${console_.all.join('\n')}`).toEqual([])
  } finally {
    await disableManager(context)
    await context.api.dispose()
  }
})
