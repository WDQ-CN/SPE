import { expect, test, type Page } from '@playwright/test'

/**
 * P1-W4 的端到端验收：在真实 Chrome 里走一遍登录、菜单、三个页面，并盯住控制台。
 *
 * <p>为什么要有这一层：单测能证明「规则对」，接口测试能证明「数据对」，但**渲染、跳转、
 * 对话框交互、CSP 是否拦到自家资源**只能在这里看。W4 收口时本机没有浏览器工具，这几条
 * 一直空着（11 §9 的待人工核对项）——本文件把它们补成可重复执行的检查。
 *
 * <p>三条约定：
 * 1. **用专用探针账号制造失败登录**：成功登录会清零限流计数，而失败会累加；拿种子账号
 *    `admin` 反复失败会把它锁死（后端集成测试踩过同一个坑，见 03 §17 第 3 项）。
 * 2. **只读写必要的数据**：唯一会写库的用例是「新增用户」，它会留下一个**已停用**的
 *    `e2e-w4-*` 账号（无删除接口，与既有探针账号同类，已登记）。
 * 3. **控制台错误按失败处理**：页面能跑但控制台在报错，通常意味着资源被拦或组件异常。
 */

const ADMIN = { username: 'admin', password: 'Admin@12345' }
const FAILURE_PROBE = 'e2e-w4-unknown-account'

/** 收集控制台错误；CSP 违规单独挑出来，因为它是「页面看起来正常但资源被拦」的典型。 */
function watchConsole(page: Page): { all: string[]; csp: string[] } {
  const all: string[] = []
  const csp: string[] = []
  page.on('console', (message) => {
    if (message.type() !== 'error') {
      return
    }
    const text = message.text()
    // 浏览器会为任何非 2xx 响应自动打一条 "Failed to load resource"：401/403 是本项目的**预期**结果
    // （登录失败返回 401、越权返回 403），把它当缺陷会让用例在正确行为下变红。
    // 真正要盯的是 **JS 异常**（pageerror）与 **CSP 违规**——这两类没有豁免。
    if (/Failed to load resource/i.test(text)) {
      return
    }
    all.push(text)
    if (/Content Security Policy|Refused to (load|execute|apply)/i.test(text)) {
      csp.push(text)
    }
  })
  page.on('pageerror', (error) => all.push(`pageerror: ${error.message}`))
  return { all, csp }
}

async function login(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login')
  await page.getByPlaceholder('请输入账号').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
}

function menuItem(page: Page, name: string) {
  return page.locator('.el-menu-item', { hasText: name })
}

async function loginAsAdmin(page: Page): Promise<void> {
  await login(page, ADMIN.username, ADMIN.password)
  await expect(page.locator('.el-menu-item').first()).toBeVisible()
}

test('admin 登录后落首页：菜单四项、身份正确、控制台无错误', async ({ page }) => {
  const console_ = watchConsole(page)

  await loginAsAdmin(page)

  await expect(page).toHaveURL(/localhost:18081\/$/)
  const items = page.locator('.el-menu-item')
  await expect(items).toHaveCount(4)
  await expect(items.nth(0)).toContainText('首页')
  await expect(items.nth(1)).toContainText('供应商')
  await expect(items.nth(2)).toContainText('用户管理')
  await expect(items.nth(3)).toContainText('系统设置')

  // 「系统管理员」在页头标签和首页信息卡里都会出现，getByText 会因命中多个而报 strict mode violation
  await expect(page.locator('.layout__user .el-tag')).toHaveText('系统管理员')

  expect(console_.all, `控制台报错：\n${console_.all.join('\n')}`).toEqual([])
})

test('未登录直接访问受保护路径 → 跳登录页并带上回跳目标', async ({ page }) => {
  await page.goto('/users')

  await expect(page.getByPlaceholder('请输入账号')).toBeVisible()
  expect(page.url()).toContain('/login')
  expect(page.url()).toContain('redirect')
})

test('口令错误的探针账号：留在登录页、在表单内提示、控制台无错误', async ({ page }) => {
  const console_ = watchConsole(page)

  await login(page, FAILURE_PROBE, 'whatever-wrong')

  await expect(page).toHaveURL(/\/login/)
  await expect(page.locator('.el-alert__title')).toContainText('账号或密码错误')
  // 表单级错误不应走全局提示，更不该清 token 跳转
  expect(console_.all).toEqual([])
})

test('登录成功回跳 redirect 指定页面', async ({ page }) => {
  await page.goto('/login?redirect=/suppliers')
  await page.getByPlaceholder('请输入账号').fill(ADMIN.username)
  await page.getByPlaceholder('请输入密码').fill(ADMIN.password)
  await page.getByRole('button', { name: '登录' }).click()

  await expect(page).toHaveURL(/\/suppliers$/)
  await expect(page.locator('.el-table')).toBeVisible()
})

test('供应商页：关键字搜索命中既有数据，表格与分页渲染正常', async ({ page }) => {
  const console_ = watchConsole(page)

  await loginAsAdmin(page)
  await menuItem(page, '供应商').click()
  await expect(page).toHaveURL(/\/suppliers$/)

  await page.getByPlaceholder('编码或名称包含').fill('E2E-W3-001')
  await page.getByRole('button', { name: '查询' }).click()

  const body = page.locator('.el-table__body')
  await expect(body).toContainText('E2E-W3-001')
  await expect(page.locator('.el-table__body tr')).toHaveCount(1)

  // 重置后应回到全量列表（数量大于搜索结果）
  await page.getByRole('button', { name: '重置' }).click()
  await expect(page.locator('.el-table__body tr').first()).toBeVisible()

  expect(console_.all, `控制台报错：\n${console_.all.join('\n')}`).toEqual([])
})

test('设置页：显示当前阈值；把 A 档填得比 B 档低时前端拦下并禁用保存', async ({ page }) => {
  await loginAsAdmin(page)
  await menuItem(page, '系统设置').click()
  await expect(page).toHaveURL(/\/settings$/)

  const aThreshold = page.locator('.el-form-item', { hasText: 'A 档阈值' }).locator('input')
  await expect(aThreshold).toHaveValue('90')

  await aThreshold.fill('50')
  await aThreshold.blur()

  await expect(page.locator('.el-alert--warning')).toContainText('B 档不能高于 A 档')
  await expect(page.getByRole('button', { name: '保存' })).toBeDisabled()

  // 复原输入但不点保存：本用例只验前端联动，不写库
  await aThreshold.fill('90')
  await aThreshold.blur()
  await expect(page.getByRole('button', { name: '保存' })).toBeEnabled()
})

test('用户页：新增账号 → 列表可见 → 停用（覆盖对话框、校验与守卫 UPDATE）', async ({ page }) => {
  const console_ = watchConsole(page)
  // 用时间戳保证唯一：重复跑不会撞「账号已存在」
  const username = `e2e-w4-${Date.now().toString().slice(-9)}`

  await loginAsAdmin(page)
  await menuItem(page, '用户管理').click()
  await expect(page).toHaveURL(/\/users$/)

  // 列表按 id 升序，新账号排在末尾；dev 库里累积的账号早已不止一页（每跑一次 e2e 留一个停用账号），
  // 所以先把每页设到后端上限 100，让新账号与它所在的行落在同一页——否则这条用例会随数据增长而变红。
  await page.locator('.el-pagination .el-select').click()
  await page.locator('.el-select-dropdown__item', { hasText: '100' }).first().click()

  await page.getByRole('button', { name: '新增用户' }).click()
  await page.getByPlaceholder('登录用的账号').fill(username)
  await page.getByPlaceholder('至少 8 位').fill('E2eTest@12345')
  await page.getByLabel('姓名').fill('E2E 用例账号')
  await page.getByRole('button', { name: '保存' }).click()

  // 新增成功后对话框关闭，新账号出现在列表（上面已把页大小设到 100，新账号虽在末尾但仍在同一页）
  await expect(page.locator('.el-dialog')).toBeHidden()
  const row = page.locator('.el-table__row', { hasText: username })
  await expect(row).toContainText('启用')

  // 停用：走 ElMessageBox 确认（顺带验证它的样式被正确引入）
  await row.getByRole('button', { name: '停用' }).click()
  await expect(page.locator('.el-message-box')).toBeVisible()
  await page.getByRole('button', { name: '确定' }).click()

  await expect(page.locator('.el-table__row', { hasText: username })).toContainText('停用')
  expect(console_.all, `控制台报错：\n${console_.all.join('\n')}`).toEqual([])
})

test('CSP 不拦自家资源：四个页面加载后无 CSP 违规（SEC-4 的运行时证据）', async ({ page }) => {
  const console_ = watchConsole(page)

  await loginAsAdmin(page)
  for (const name of ['供应商', '用户管理', '系统设置']) {
    await menuItem(page, name).click()
    await page.waitForLoadState('networkidle')
  }

  expect(console_.csp, `CSP 违规：\n${console_.csp.join('\n')}`).toEqual([])
})
