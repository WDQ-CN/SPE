import { expect, request, test, type Page } from '@playwright/test'

import { ADMIN, login, menuItem } from './support'

/**
 * P0-1 的回归用例（`03 §38`）：**以 MANAGER 身份走 UI 建任务并发布**。
 *
 * <p>为什么必须有这条走界面的用例——`p3.spec.ts` 建任务与发布全部走 API 直连（`seedTask`），
 * 改派那一行只断言按钮 `toBeVisible()` 从未点击，于是"MANAGER 一点『新建任务』就被 403 打断、
 * 三个下拉全空"这个 P0 在 **16 条 e2e 全绿**的情况下藏了 6 个提交。按本轮提升进 `06 §8` 的自查项：
 * **角色可见的入口必须有走 UI 的点击用例，不接受"API 直连 + 按钮可见"**。
 *
 * <p>修复前这条必须是红的（选择器调 `/api/users`，那是 ADMIN-only）；断言写成**正例**
 * （下拉里必须真有选项、能选中、能发布成功），不是"没报错"（04-070 的判据形状）。
 *
 * <p>⚠️ 用例会真的写 dev 库：每次跑新建一个**唯一编码的供应商**，所以 J4 的 `(供应商, 周期)`
 * 唯一性不会撞（走 UI 建任务没法像 `seedTask` 那样自己挑空周期，换个新供应商是更稳的做法）。
 */

const PASSWORD = 'E2eUi@12345'
const MANAGER_USERNAME = 'e2e-ui-manager'
const APPROVER_USERNAME = 'e2e-ui-approver'

type Api = Awaited<ReturnType<typeof request.newContext>>

interface Account {
  api: Api
  headers: Record<string, string>
}

async function adminApi(): Promise<Account> {
  const api = await request.newContext()
  const body = await (
    await api.post('/api/auth/login', { data: { username: ADMIN.username, password: ADMIN.password } })
  ).json()
  expect(body.code, 'admin 登录').toBe(0)
  return { api, headers: { Authorization: `Bearer ${body.data.token}` } }
}

/** 幂等准备账号：不存在就建，存在就启用 + 重置口令（**断言每次调用真的成功**，不吞响应）。 */
async function ensureUser(admin: Account, username: string, role: string): Promise<void> {
  const listed = await admin.api.get('/api/users?page=1&size=100', { headers: admin.headers })
  const rows: { id: number; username: string; status: string; version: number }[] = (
    await listed.json()
  ).data.list
  const existing = rows.find((row) => row.username === username)
  if (!existing) {
    const created = await admin.api.post('/api/users', {
      headers: admin.headers,
      data: { username, password: PASSWORD, name: `E2E ${username}`, role, department: 'PURCHASE' },
    })
    expect((await created.json()).code, `创建用户 ${username}`).toBe(0)
    return
  }
  if (existing.status !== 'ENABLED') {
    const enabled = await admin.api.put(`/api/users/${existing.id}/status`, {
      headers: admin.headers,
      data: { status: 'ENABLED', version: existing.version },
    })
    expect((await enabled.json()).code, `启用用户 ${username}`).toBe(0)
  }
  // 注意字段名是 `newPassword`（后端 ResetPasswordRequest）——p3.spec.ts 里发的是 `password`，
  // 那一支从未生效过（03 §38 的 P2，见 recheck 期间的 13 条 Unrecognized field）
  const reset = await admin.api.put(`/api/users/${existing.id}/password`, {
    headers: admin.headers,
    data: { newPassword: PASSWORD },
  })
  expect((await reset.json()).code, `重置口令 ${username}`).toBe(0)
}

async function ensureSupplier(admin: Account): Promise<string> {
  const code = `E2E-UI-${Date.now().toString(36).toUpperCase()}`
  const created = await admin.api.post('/api/suppliers', {
    headers: admin.headers,
    data: { code, name: `E2E-UI 供应商 ${code}`, category: 'E2E' },
  })
  const body = await created.json()
  expect(body.code, '创建供应商').toBe(0)
  expect(body.data.id, '供应商 id').toBeTruthy()
  return code
}

/** 该表单项里的 el-select（wrapper 才是可点的面，input 是只读 combobox） */
function selectItem(page: Page, formLabel: string) {
  return page.locator(`.el-form-item:has-text("${formLabel}")`).first()
}

/**
 * 打开指定表单项的 el-select，返回**属于它自己**的选项定位器。
 *
 * <p>⚠️ 不能用全局的 `.el-select-dropdown:visible .el-select-dropdown__item`：Element Plus 把下拉
 * 渲染在 body 上，前一个 select 的面板在动画期间仍然 visible，于是"审批链里到底有没有选项"
 * 会被**上一个下拉的残留选项**冒充（本轮实测：MANAGER 拿不到人员，断言却照样通过）。
 * 走 `aria-controls` 精确锁定该 select 的 listbox；点 wrapper 而不是点只读 input；
 * 选择用键盘（ArrowDown + Enter）而不是 click —— 下拉带滚动/高亮动画，click 的稳定判定会重试到超时。
 */
async function openOptions(page: Page, formLabel: string) {
  const item = selectItem(page, formLabel)
  await item.locator('.el-select__wrapper').first().click({ force: true })
  const listboxId = await item.locator('.el-select input').first().getAttribute('aria-controls')
  expect(listboxId, `「${formLabel}」的 select 必须暴露 aria-controls，否则无法区分是哪个下拉`).toBeTruthy()
  return page.locator(`#${listboxId} .el-select-dropdown__item`)
}

async function pickFirstOption(page: Page, formLabel: string): Promise<void> {
  const options = await openOptions(page, formLabel)
  await expect(options, `「${formLabel}」下拉必须有选项`).not.toHaveCount(0)
  const input = selectItem(page, formLabel).locator('.el-select input').first()
  await input.press('ArrowDown')
  await input.press('Enter')
}

test.describe('MANAGER 走 UI 建任务并发布（P0-1 回归）', () => {
  let admin: Account

  test.beforeAll(async () => {
    admin = await adminApi()
    await ensureUser(admin, MANAGER_USERNAME, 'MANAGER')
    await ensureUser(admin, APPROVER_USERNAME, 'MANAGER')
  })

  test.afterAll(async () => {
    await admin?.api.dispose()
  })

  test('新建任务对话框里三个下拉都有真选项，保存草稿后能一路发布成功', async ({ page }) => {
    const supplierCode = await ensureSupplier(admin)
    const taskName = `E2E-UI 建任务 ${supplierCode}`

    await login(page, MANAGER_USERNAME, PASSWORD)
    await expect(page.locator('.el-menu-item').first()).toBeVisible()
    await menuItem(page, '评估任务').click()
    await page.waitForLoadState('networkidle')

    await page.getByRole('button', { name: '新建任务' }).click()
    const dialog = page.locator('.el-dialog:visible')
    await expect(dialog).toBeVisible()

    await dialog.getByPlaceholder('如：2026 年 Q1 供应商绩效评估').fill(taskName)

    // ① 模板与版本：正例断言"选项不是空的"，再取第一个选上
    await pickFirstOption(page, '评估模板')
    await pickFirstOption(page, '模板版本')

    // ② 审批链（01 §3 经 2026-10-25 裁决收窄：审批人只列 MANAGER/ADMIN）：这一路在修复前必空——它调的是 ADMIN-only 的用户列表
    await pickFirstOption(page, '审批链')

    // ③ 参评供应商是远程搜索：点 wrapper、往只读 input 里输编码，等选项回来后按键盘选中
    const supplierItem = selectItem(page, '参评供应商')
    await supplierItem.locator('.el-select__wrapper').first().click({ force: true })
    const supplierInput = supplierItem.locator('.el-select input').first()
    await supplierInput.fill(supplierCode)
    const supplierListboxId = await supplierInput.getAttribute('aria-controls')
    expect(supplierListboxId, '供应商 select 必须暴露 aria-controls').toBeTruthy()
    const supplierOptions = page.locator(`#${supplierListboxId} .el-select-dropdown__item`)
    await expect(
      supplierOptions.filter({ hasText: supplierCode }).first(),
      `远程搜索必须命中新建的供应商 ${supplierCode}`,
    ).toBeVisible({ timeout: 15_000 })
    await supplierInput.press('ArrowDown')
    await supplierInput.press('Enter')
    // 选中与否看**已选标签**：多选 select 会把选中项渲染成 wrapper 里的 .el-tag；
    // 不要用 `#listboxId .is-selected` —— popper 挂在 body 下、不在表单项内，选中后面板还会收起（首轮就栽在这）
    await expect(
      supplierItem.locator('.el-select__wrapper').first(),
      `供应商必须真的被选中（否则后端以「请至少选择一个供应商」拒绝）：${supplierCode}`,
    ).toContainText(supplierCode)

    // ④ 截止日期：直接填（value-format 是 YYYY-MM-DD）
    await dialog.locator('.el-form-item:has-text("截止日期")').locator('input').first().fill('2099-12-31')
    await page.keyboard.press('Enter')

    await dialog.getByRole('button', { name: '保存草稿' }).click()
    await expect(page.getByText('草稿已创建，可在列表里发布')).toBeVisible()

    // ⑤ 列表里找到那一行并发布：确认框 → 成功提示 → 状态转「进行中」
    const row = page.locator('.el-table__row', { hasText: taskName })
    await expect(row, '新建的草稿必须出现在列表里').toHaveCount(1)
    await row.getByRole('button', { name: '发布' }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '确定' }).click()
    await expect(page.getByText(/^已发布：/), '发布成功必须有回执').toBeVisible()
    await expect(row.getByText('进行中'), '任务应进入进行中').toBeVisible()

    // ⑥ 顺手钉住防重复闸门（P1-14）：连点两次"发布"不会发第二遍。
    //    这里能验的是"第二次点击不会再打一次接口"——发布成功后按钮随状态变化消失，
    //    所以用第一行的"详情→返回列表"确认页面没被拦截器的 refreshCurrentView 重挂（那正是原缺陷的表现）
    await row.getByRole('button', { name: '详情' }).click()
    await expect(page.getByText('任务详情')).toBeVisible()
  })
})
