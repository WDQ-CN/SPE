/**
 * 登录回跳目标的解析（`?redirect=` 来自地址栏，**不能直接拿去跳转**）。
 *
 * <p>为什么需要它：`redirect` 是外部可控输入。若原样交给路由，`?redirect=//evil.com` 这类值
 * 可能被当成外部地址——这正是开放重定向（钓鱼链接常拿它把「可信域名的登录页」当跳板）。
 * 规则很窄：只接受站内绝对路径，其余一律回首页。
 *
 * <p>纯函数，便于单测（`redirect.spec.ts`）。
 */
export function safeRedirect(raw: unknown, fallback = '/'): string {
  if (typeof raw !== 'string' || raw === '') {
    return fallback
  }
  // 必须以单个 `/` 开头：`//host` 是协议相对地址，`/\host` 在部分浏览器里等价于 `//host`
  if (!raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) {
    return fallback
  }
  // 反斜杠在 URL 解析里可能被规整成 `/`，同样能绕过上面的判断
  if (raw.includes('\\')) {
    return fallback
  }
  return raw
}
