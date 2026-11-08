import { describe, expect, it } from 'vitest'

import { canAccess, visibleMenus } from './menu'

/**
 * 菜单可见性的规则测试（01 §7 权限矩阵）。
 *
 * <p>为什么值得测：这条规则有两个消费者（布局渲染与路由守卫），错一处的表现是
 * 「菜单里看不到、但直接输地址就进去了」——手工点界面试不出来，只有把矩阵写成用例才守得住。
 */
describe('侧边菜单可见性（01 §7）', () => {
  it('ADMIN 能看到二十二个入口（原 SPE 十三项 + ERP 九项）', () => {
    expect(visibleMenus(['ADMIN']).map((menu) => menu.path)).toEqual([
      '/',
      '/suppliers',
      '/masterdata',
      '/templates',
      '/tasks',
      '/approvals',
      '/results',
      '/analytics',
      '/alerts',
      '/actions',
      '/sourcing',
      '/inventory',
      '/sales/orders',
      '/procurement/orders',
      '/manufacturing/work-orders',
      '/quality',
      '/assets-projects',
      '/report-center',
      '/users',
      '/settings',
      '/audit',
      '/api-tokens',
    ])
  })

  it('MANAGER 能看到首页、评估入口、整改任务与 ERP 九个入口', () => {
    expect(visibleMenus(['MANAGER']).map((menu) => menu.path)).toEqual([
      '/',
      '/suppliers',
      '/masterdata',
      '/templates',
      '/tasks',
      '/my',
      '/approvals',
      '/results',
      '/analytics',
      '/alerts',
      '/actions',
      '/sourcing',
      '/inventory',
      '/sales/orders',
      '/procurement/orders',
      '/manufacturing/work-orders',
      '/quality',
      '/assets-projects',
      '/report-center',
    ])
  })

  it('EVALUATOR 有首页、我的待办与整改任务；VIEWER 有首页、结果报表、分析看板与预警', () => {
    expect(visibleMenus(['EVALUATOR']).map((menu) => menu.path)).toEqual([
      '/',
      '/my',
      '/actions',
    ])
    expect(visibleMenus(['VIEWER']).map((menu) => menu.path)).toEqual([
      '/',
      '/results',
      '/analytics',
      '/alerts',
    ])
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

  it('采购/销售执行隐藏入口单独登记：VIEWER 直输地址也不放行', () => {
    expect(canAccess('/procurement/operations', ['ADMIN'])).toBe(true)
    expect(canAccess('/procurement/operations', ['MANAGER'])).toBe(true)
    expect(canAccess('/procurement/operations', ['VIEWER'])).toBe(false)
    expect(canAccess('/procurement/operations', ['EVALUATOR'])).toBe(false)
    expect(canAccess('/sales/operations', ['ADMIN'])).toBe(true)
    expect(canAccess('/sales/operations', ['MANAGER'])).toBe(true)
    expect(canAccess('/sales/operations', ['VIEWER'])).toBe(false)
    expect(canAccess('/sales/operations', ['EVALUATOR'])).toBe(false)
    expect(canAccess('/manufacturing/boms', ['MANAGER'])).toBe(true)
    expect(canAccess('/manufacturing/boms', ['VIEWER'])).toBe(false)
  })

  it('M5 新入口：分析看板/预警对 VIEWER 开放，操作日志仅 ADMIN', () => {
    expect(canAccess('/analytics', ['VIEWER'])).toBe(true)
    expect(canAccess('/alerts', ['MANAGER'])).toBe(true)
    expect(canAccess('/analytics', ['EVALUATOR'])).toBe(false)
    expect(canAccess('/alerts', ['EVALUATOR'])).toBe(false)
    expect(canAccess('/audit', ['MANAGER'])).toBe(false)
    expect(canAccess('/audit', ['ADMIN'])).toBe(true)
    expect(canAccess('/api-tokens', ['MANAGER'])).toBe(false)
    expect(canAccess('/api-tokens', ['ADMIN'])).toBe(true)
  })

  it('W4 整改入口：ADMIN/MANAGER/EVALUATOR 可进，VIEWER 不可；详情页按前缀继承', () => {
    expect(canAccess('/actions', ['ADMIN'])).toBe(true)
    expect(canAccess('/actions', ['MANAGER'])).toBe(true)
    expect(canAccess('/actions', ['EVALUATOR'])).toBe(true)
    expect(canAccess('/actions', ['VIEWER'])).toBe(false)
    expect(canAccess('/actions/12', ['MANAGER'])).toBe(true)
    expect(canAccess('/actions/12', ['EVALUATOR'])).toBe(true)
    expect(canAccess('/actions/12', ['VIEWER'])).toBe(false)
  })

  it('子路径按最长前缀归属到入口（将来加详情页不必再登记）', () => {
    expect(canAccess('/suppliers/123', ['MANAGER'])).toBe(true)
    expect(canAccess('/suppliers/123/edit', ['VIEWER'])).toBe(false)
  })

  it('模板编辑器 /templates/{版本id} 按前缀继承入口权限——直接输地址也拦得住', () => {
    // 路由把编辑器挂在 /templates 之下正是为了这一点：入口只登记一次，子路径自动继承
    // （与 /suppliers/123 同一机制）。若哪天改成 /template-versions/:id，这条会红，
    // 因为那个前缀不在菜单表里 → 未登记路径一律放行 → 越权判定只剩后端兜底。
    expect(canAccess('/templates/12', ['ADMIN'])).toBe(true)
    expect(canAccess('/templates/12', ['MANAGER'])).toBe(true)
    expect(canAccess('/templates/12', ['EVALUATOR'])).toBe(false)
    expect(canAccess('/templates/12', ['VIEWER'])).toBe(false)
  })

  it('隐藏入口不进菜单，但仍然受入口级权限管辖（评分单页是 P3 的典型）', () => {
    // 这类页面"从列表点进去"，不该出现在侧边栏；但如果连登记都不登记，
    // canAccess 会因"未登记即放行"把它对所有角色打开（包括 VIEWER）
    expect(visibleMenus(['MANAGER']).map((menu) => menu.path)).not.toContain('/sheets')
    expect(canAccess('/sheets/12', ['MANAGER'])).toBe(true)
    expect(canAccess('/sheets/12', ['EVALUATOR'])).toBe(true)
    expect(canAccess('/sheets/12', ['VIEWER'])).toBe(false)
    expect(canAccess('/sheets/12', ['ADMIN'])).toBe(false)
  })

  it('未登记的路径一律放行：本表只管入口级可见性', () => {
    // 登录页、将来的公开页不在菜单表里，越权判定始终由后端负责（4xxx + ACCESS_DENIED 审计）
    expect(canAccess('/login', ['VIEWER'])).toBe(true)
    expect(canAccess('/some/future/page', [])).toBe(true)
  })

  it('P4 子路径按前缀继承入口权限：审批详情、任务结果、报告详情', () => {
    expect(canAccess('/approvals/12', ['MANAGER'])).toBe(true)
    expect(canAccess('/approvals/12', ['ADMIN'])).toBe(true)
    expect(canAccess('/approvals/12', ['EVALUATOR'])).toBe(false)
    expect(canAccess('/approvals/12', ['VIEWER'])).toBe(false)

    expect(canAccess('/results/12', ['VIEWER'])).toBe(true)
    expect(canAccess('/results/12', ['MANAGER'])).toBe(true)
    expect(canAccess('/results/12', ['EVALUATOR'])).toBe(false)

    // 报告详情对任意已登录角色开放入口；"本人参与过"的判据在后端第二层（01 §7）
    expect(canAccess('/reports/12', ['EVALUATOR'])).toBe(true)
    expect(canAccess('/reports/12', ['VIEWER'])).toBe(true)
    expect(canAccess('/reports/12', ['ADMIN'])).toBe(true)
  })

  it('首页不会被前缀匹配吃掉：它只做精确匹配', () => {
    // 若把 '/' 也当前缀，'/suppliers' 会被错误地归属到首页（首页对所有人开放），越权判定就失效了
    expect(canAccess('/suppliers', ['VIEWER'])).toBe(false)
  })
})