import { describe, expect, it } from 'vitest'

import { safeRedirect } from './redirect'

/**
 * 回跳目标的解析（安全相关）：`?redirect=` 来自地址栏，是外部可控输入。
 * 若原样拿去跳转，`?redirect=//evil.com` 这类值会把「可信域名的登录页」变成钓鱼跳板。
 */
describe('safeRedirect', () => {
  it('站内绝对路径原样返回', () => {
    expect(safeRedirect('/suppliers')).toBe('/suppliers')
    expect(safeRedirect('/suppliers?page=2&size=20')).toBe('/suppliers?page=2&size=20')
  })

  it('协议相对地址被拒（//evil.com 会被浏览器当成外部主机）', () => {
    expect(safeRedirect('//evil.com')).toBe('/')
    expect(safeRedirect('//evil.com/login')).toBe('/')
  })

  it('绝对 URL 与相对路径被拒', () => {
    expect(safeRedirect('https://evil.com')).toBe('/')
    expect(safeRedirect('http://evil.com/x')).toBe('/')
    expect(safeRedirect('suppliers')).toBe('/')
  })

  it('含反斜杠的值被拒（部分浏览器把 \\ 规整成 /，可绕过前缀判断）', () => {
    expect(safeRedirect('/\\evil.com')).toBe('/')
    expect(safeRedirect('/a\\b')).toBe('/')
  })

  it('非字符串与空值回落到首页', () => {
    expect(safeRedirect(undefined)).toBe('/')
    expect(safeRedirect(null)).toBe('/')
    expect(safeRedirect('')).toBe('/')
    // vue-router 的 query 值可能是数组（?redirect=a&redirect=b）
    expect(safeRedirect(['/suppliers'])).toBe('/')
  })

  it('可指定回落目标', () => {
    expect(safeRedirect('//evil.com', '/suppliers')).toBe('/suppliers')
  })
})
