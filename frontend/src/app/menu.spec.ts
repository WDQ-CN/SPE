import { describe, expect, it } from 'vitest'

import { canAccess, visibleMenus } from './menu'

/**
 * 菜单可见性的规则测试（01 §7 权限矩阵）。
 *
 * <p>为什么值得测：这条规则有两个消费者（布局渲染与路由守卫），错一处的表现是
 * 「菜单里看不到、但直接输地址就进去了」——手工点界面试不出来，只有把矩阵写成用例才守得住。
 */
describe('侧边菜单可见性（01 §7）', () => {
  it('ADMIN 能看到全部四个入口', () => {
    expect(visibleMenus(['ADMIN']).map((menu) => menu.path)).toEqual([
      '/',
      '/suppliers',
      '/users',
      '/settings',
    ])
  })

  it('MANAGER 只能看到首页与供应商', () => {
    expect(visibleMenus(['MANAGER']).map((menu) => menu.path)).toEqual(['/', '/suppliers'])
  })

  it('EVALUATOR 与 VIEWER 在 P1 只有首页（P1 的接口只有供应商与用户管理）', () => {
    expect(visibleMenus(['EVALUATOR']).map((menu) => menu.path)).toEqual(['/'])
    expect(visibleMenus(['VIEWER']).map((menu) => menu.path)).toEqual(['/'])
  })

  it('没有任何角色时只有首页（首页对所有已登录用户开放）', () => {
    expect(visibleMenus([]).map((menu) => menu.path)).toEqual(['/'])
  })
})

describe('入口级访问判定', () => {
  it('无权限的角色访问受限路径返回 false', () => {
    expect(canAccess('/users', ['MANAGER'])).toBe(false)
    expect(canAccess('/settings', ['EVALUATOR'])).toBe(false)
    expect(canAccess('/suppliers', ['VIEWER'])).toBe(false)
  })

  it('有权限的角色返回 true', () => {
    expect(canAccess('/users', ['ADMIN'])).toBe(true)
    expect(canAccess('/suppliers', ['MANAGER'])).toBe(true)
    expect(canAccess('/', ['VIEWER'])).toBe(true)
  })

  it('子路径按最长前缀归属到入口（将来加详情页不必再登记）', () => {
    expect(canAccess('/suppliers/123', ['MANAGER'])).toBe(true)
    expect(canAccess('/suppliers/123/edit', ['VIEWER'])).toBe(false)
  })

  it('未登记的路径一律放行：本表只管入口级可见性', () => {
    // 登录页、将来的公开页不在菜单表里，越权判定始终由后端负责（4xxx + ACCESS_DENIED 审计）
    expect(canAccess('/login', ['VIEWER'])).toBe(true)
    expect(canAccess('/some/future/page', [])).toBe(true)
  })

  it('首页不会被前缀匹配吃掉：它只做精确匹配', () => {
    // 若把 '/' 也当前缀，'/suppliers' 会被错误地归属到首页（首页对所有人开放），越权判定就失效了
    expect(canAccess('/suppliers', ['VIEWER'])).toBe(false)
  })
})
