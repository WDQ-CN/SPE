import { expect, request, test, type Page } from '@playwright/test'
import { ADMIN } from './support'

/**
 * P1-W5 的 M1 验收用例：**四角色真实登录 + 菜单按 01 §7 渲染**。
 *
 * <p>为什么单独一个文件：`w4.spec.ts` 验的是「页面能用」，这里验的是**验收判据本身**
 * （10 §1 的判据 1、08 的 P1 验收「四角色可登录」）。此前只有 ADMIN 真实登录过，
 * 其余角色的菜单矩阵只在 `menu.spec.ts` 里以单测形式覆盖——单测证明「规则对」，
 * 但证明不了「真实登录后界面确实按角色渲染」。
 *
 * <p>账号用 **API 建、UI 验**：建号走接口（快、且不受 UI 变更影响），验的是登录后的菜单。
 * 账号名固定 + 幂等处理（已存在就启用并重置口令），因此可以重复执行且不会累积垃圾账号；
 * 用例结束把它们停用，保持 dev 库整洁。
 */

const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:18081'
const PROBE_PASSWORD = 'E2eTest@12345'

/** 01 §7 权限矩阵在 P1~P3 范围内的映射（顺序与 `app/menu.ts` 的登记顺序一致） */
const ROLE_CASES = [
  {
    username: 'e2e-w5-manager',
    role: 'MANAGER',
    // P2-W4 起「评估模板」；P3-W6 起「评估任务」与「我的待办」；P4-W6 起「我的审批」「结果报表」；
    // M5 起「分析看板」「预警」；W4 起「整改任务」（01 §7：MANAGER 可管理全部整改）
    menus: [
      '首页',
      '供应商',
      '评估模板',
      '评估任务',
      '我的待办',
      '我的审批',
      '结果报表',
      '分析看板',
      '预警',
      '整改任务',
    ],
  },
  {
    username: 'e2e-w5-evaluator',
    role: 'EVALUATOR',
    // P3-W6 起评估人多一个「我的待办」；W4 起「整改任务」只显示本人负责的整改
    menus: ['首页', '我的待办', '整改任务'],
  },
  {
    username: 'e2e-w5-viewer',
    role: 'VIEWER',
    // P4-W6 起 VIEWER 多了「结果报表」（01 §7：结果列表/评估报告只读）；M5 再加法分析看板/预警
    menus: ['首页', '结果报表', '分析看板', '预警'],
  },
  /**
   * ADMIN 是第四项（03 §38 的 P1-12：标题一直写"四角色"，但 `ROLE_CASES` 只有三项，
   * 于是下面那句 `if (spec.role === 'ADMIN')` 从写下到现在**一次没跑过**）。
   * 用独立的探针账号而不是种子的 `admin`——用例结尾会把它停用，停掉 `admin` 会把整个库锁死。
   */
  {
    username: 'e2e-w5-admin',
    role: 'ADMIN',
    // 01 §7：用户与系统设置仅 ADMIN；供应商/模板/任务是 ADMIN 可见（模板与任务是"只读"那一列）；
    // P4-W6 起再加「我的审批」「结果报表」；M5 加「分析看板」「预警」「操作日志」；M6 加「开放 API」；
    // W4 加「整改任务」
    menus: [
      '首页',
      '供应商',
      '评估模板',
      '评估任务',
      '我的审批',
      '结果报表',
      '分析看板',
      '预警',
      '整改任务',
      '用户管理',
      '系统设置',
      '操作日志',
      '开放 API',
    ],
  },
] as const

interface ApiContext {
  api: Awaited<ReturnType<typeof request.newContext>>
  token: string
  headers: Record<string, string>
}

async function adminApi(): Promise<ApiContext> {
  const api = await request.newContext({ baseURL: BASE_URL })
  const response = await api.post('/api/auth/login', {
    data: { username: ADMIN.username, password: ADMIN.password },
  })
  const body = await response.json()
  expect(body.code, 'admin 登录').toBe(0)
  const token: string = body.data.token
  return { api, token, headers: { Authorization: `Bearer ${token}` } }
}

interface UserRow {
  id: number
  username: string
  status: 'ENABLED' | 'DISABLED'
  version: number
}

async function findUser(context: ApiContext, username: string): Promise<UserRow | undefined> {
  const response = await context.api.get('/api/users?page=1&size=100', {
    headers: context.headers,
  })
  const rows: UserRow[] = (await response.json()).data.list
  return rows.find((row) => row.username === username)
}

/** 幂等地准备好一个可登录的账号：不存在就建，存在就启用 + 重置口令。 */
async function ensureLoginableUser(
  context: ApiContext,
  spec: { username: string; role: string },
): Promise<void> {
  const existing = await findUser(context, spec.username)

  if (!existing) {
    const created = await context.api.post('/api/users', {
      headers: context.headers,
      data: {
        username: spec.username,
        password: PROBE_PASSWORD,
        name: `${spec.role} 验收账号`,
        role: spec.role,
        department: 'QUALITY',
      },
    })
    expect((await created.json()).code, `创建 ${spec.username}`).toBe(0)
    return
  }

  if (existing.status === 'DISABLED') {
    const enabled = await context.api.put(`/api/users/${existing.id}/status`, {
      headers: context.headers,
      data: { status: 'ENABLED', version: existing.version },
    })
    expect((await enabled.json()).code, `启用 ${spec.username}`).toBe(0)
  }

  // 上一轮跑完会把它停用并保持同一个口令；这里再重置一次，避免「口令是不是我设的那个」这种不确定性
  const reset = await context.api.put(`/api/users/${existing.id}/password`, {
    headers: context.headers,
    data: { newPassword: PROBE_PASSWORD },
  })
  expect((await reset.json()).code, `重置 ${spec.username} 的口令`).toBe(0)
}

async function disableUser(context: ApiContext, username: string): Promise<void> {
  const existing = await findUser(context, username)
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

test('M1 判据 1：四角色可登录，且菜单按 01 §7 渲染', async ({ page }) => {
  // 正例断言：四项齐全才算"四角色"。标题与数组长度差一项的病一直没被抓住，
  // 因为 e2e 目录不在类型检查范围内（03 §38 的 P1-12）
  expect(ROLE_CASES.map((spec) => spec.role)).toEqual(['MANAGER', 'EVALUATOR', 'VIEWER', 'ADMIN'])
  const context = await adminApi()

  try {
    for (const spec of ROLE_CASES) {
      await ensureLoginableUser(context, spec)

      await login(page, spec.username, PROBE_PASSWORD)
      await expect(page.locator('.el-menu-item'), `${spec.role} 的菜单项数`).toHaveText([
        ...spec.menus,
      ])
      // 菜单里没有的入口，用地址栏也进不去（守卫挡回首页）
      await page.goto('/users')
      if (spec.role === 'ADMIN') {
        await expect(page).toHaveURL(/\/users$/)
      } else {
        await expect(page, `${spec.role} 直接访问 /users 应被挡回首页`).toHaveURL(
          /localhost:18081\/$/,
        )
      }

      // 退出，换下一个角色（顺带验证「退出」可用）
      await page.getByRole('button', { name: '退出' }).click()
      await expect(page).toHaveURL(/\/login/)
    }
  } finally {
    for (const spec of ROLE_CASES) {
      await disableUser(context, spec.username)
    }
    await context.api.dispose()
  }
})

test('M1 判据 4：越权角色的写接口被后端拒绝（不只是菜单隐藏）', async () => {
  const context = await adminApi()
  try {
    await ensureLoginableUser(context, ROLE_CASES[2])

    const viewerLogin = await context.api.post('/api/auth/login', {
      data: { username: ROLE_CASES[2].username, password: PROBE_PASSWORD },
    })
    const viewerToken: string = (await viewerLogin.json()).data.token

    // 菜单隐藏只是体验；真正的边界是后端返回 4xxx（01 §7「– 表示无入口」+ 05 §9）
    const forbidden = await context.api.get('/api/users?page=1&size=10', {
      headers: { Authorization: `Bearer ${viewerToken}` },
    })
    expect(forbidden.status(), 'VIEWER 访问用户管理').toBe(403)
    expect((await forbidden.json()).code).toBe(4001)
  } finally {
    await disableUser(context, ROLE_CASES[2].username)
    await context.api.dispose()
  }
})
