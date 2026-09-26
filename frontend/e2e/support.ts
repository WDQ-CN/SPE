import { expect, type Page } from '@playwright/test'

/**
 * 端到端用例的公共工具（03 §19/§21）。
 *
 * 抽出来的理由很实际：控制台监视、登录、菜单定位这几件事在 w4/w5/w6 三个文件里都要用，
 * 各写一份的话「怎样才算控制台出错」这个判断会散在多处——而那条判断恰恰是最容易写错、
 * 也最容易在错误的地方放宽的（见 `watchConsole` 里关于 401 的注释）。
 */

/** 种子管理员（`V5__seed_admin.sql`，仅演示环境）。 */
export const ADMIN = { username: 'admin', password: 'Admin@12345' } as const

/**
 * 失败登录专用探针账号。
 *
 * 不要用 `admin` 制造失败登录：登录限流器按账号与 IP 两个维度独立计数，且是**进程内单例**、
 * 不随测试事务回滚；拿种子账号反复失败会把它锁死，让后续用例的前置登录莫名抛异常
 * （后端集成测试踩过，见 03 §17 第 3 项）。
 */
export const FAILURE_PROBE = 'e2e-w4-unknown-account'

/**
 * 收集控制台问题。两类要盯，一类要豁免：
 *
 * - **JS 异常**（pageerror）与 **CSP 违规**：没有豁免，出现即失败；
 * - 浏览器为任何非 2xx 响应自动打的 `Failed to load resource`：**必须豁免**。401/403 在本项目是
 *   预期结果（登录失败 401、越权 403），把它当缺陷会让用例在**正确行为**下变红。
 */
export function watchConsole(page: Page): { all: string[]; csp: string[] } {
  const all: string[] = []
  const csp: string[] = []
  page.on('console', (message) => {
    if (message.type() !== 'error') {
      return
    }
    const text = message.text()
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

export async function login(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login')
  await page.getByPlaceholder('请输入账号').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
}

export function menuItem(page: Page, name: string) {
  return page.locator('.el-menu-item', { hasText: name })
}

export async function loginAsAdmin(page: Page): Promise<void> {
  await login(page, ADMIN.username, ADMIN.password)
  await expect(page.locator('.el-menu-item').first()).toBeVisible()
}
