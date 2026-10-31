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
 * 收集控制台问题。三类要盯，一类要豁免：
 *
 * - **JS 异常**（pageerror）与 **CSP 违规**：没有豁免，出现即失败；
 * - **非 2xx 的资源响应**：只豁免 **401/403**（本项目里它们是预期结果——登录失败 401、越权 403），
 *   其余（404/500…）一律记为问题。调用方可以按 `(status, url)` 精确豁免**本用例主动制造**的
 *   预期结果（如 P2-8 自停用的 409），豁免必须落在具体 URL 上、并在用例里写清原因。
 *
 * ⚠️ 这里**不再**靠 console 里那句 `Failed to load resource` 做判断，而是直接听 `response` 事件按
 * **状态码**判定。原因：console 与 response 两个事件的到达顺序没有保证，用 console 文本去查
 * response 记录会**时序性漏判**；而那句话本身又分不出 401 与 404——无差别豁免等于把"漏了一个
 * chunk、静态资源路径写错"这类真问题**永久静音**（03 §26.5）。
 */
export function watchConsole(
  page: Page,
  options: { ignoreResponse?: (status: number, url: string) => boolean } = {},
): { all: string[]; csp: string[] } {
  const all: string[] = []
  const csp: string[] = []

  // 401/403 是业务上的预期结果；favicon 允许缺失（未提供图标不算缺陷）
  const EXPECTED_STATUS = new Set([401, 403])
  page.on('response', (response) => {
    const status = response.status()
    if (status < 400 || EXPECTED_STATUS.has(status)) {
      return
    }
    const url = response.url()
    if (/\/(favicon\.ico|favicon\.svg)$/.test(url)) {
      return
    }
    if (options.ignoreResponse?.(status, url)) {
      return
    }
    all.push(`资源加载失败：HTTP ${status} ${url}`)
  })

  page.on('console', (message) => {
    if (message.type() !== 'error') {
      return
    }
    const text = message.text()
    // 浏览器为任何非 2xx 自动打的那句由上面的 response 监听负责，这里跳过以免重复计数
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

/**
 * 逐路径断言响应**确实带着 CSP 头**（03 §38 的 P1-11）。
 *
 * <p>为什么必须有正例：SEC-4 原先唯一的运行时证据是"console 里没有 CSP 违规"——那是一条**恒真负例**。
 * 一旦 nginx 的 `add_header` 继承坑（04-032）回归或某个 location 漏 include，浏览器不再报违规，
 * 数组照样空、用例照样绿，而保护其实已经没了。断言"头在"才守得住那件事本身。
 *
 * <p>逐路径的理由：`add_header` 是**按 location 继承**的，一个路径带 CSP 不代表另一个也带。
 */
export async function expectCspHeaderOn(page: Page, urls: string[]): Promise<void> {
  expect(urls.length, '至少要有一个待验路径').toBeGreaterThan(0)
  for (const url of urls) {
    const response = await page.request.get(url)
    const header = response.headers()['content-security-policy'] ?? ''
    expect(header, `${url} 必须带 CSP 头（实际="${header}"）`).toContain("script-src 'self'")
  }
}

/**
 * 从首页真实加载的资源里挑一个 `/assets/*.js`（03 §38 的 P1-11）。
 *
 * <p>不能写死文件名：产物名带内容哈希，每次构建都变。`/` 经 `try_files` 内部重定向落到
 * `location = /index.html`，所以 `/` 与 `/index.html` 是同一份响应头——**必须真挑一个产物文件**，
 * 分层断言才有意义。
 */
export async function firstAssetUrl(page: Page): Promise<string> {
  const urls: string[] = await page.evaluate(() =>
    performance.getEntriesByType('resource').map((entry) => entry.name),
  )
  const asset = urls.find((url) => /\/assets\/[^/]+\.js$/.test(url))
  expect(asset, '首页必须加载至少一个 /assets/*.js 产物，否则这条分层断言测不到东西').toBeTruthy()
  return asset as string
}
