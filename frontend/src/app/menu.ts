import type { Role } from '@/shared/types/enums'

/**
 * 侧边菜单与「入口级」权限的**唯一来源**（依据 01 §7 权限矩阵）。
 *
 * <p>为什么集中在数据里而不是写在布局组件里：菜单可见性是「谁能看到哪个入口」的一条规则，
 * 散着写必然出现两种分叉——「菜单里隐藏了，但地址栏直接输入还能进」和「新页面忘了加角色判断」。
 * 现在布局消费 {@link visibleMenus}、路由守卫消费 {@link canAccess}，同一份数据两处使用。
 *
 * <p>**不含未实现的模块**：P2~P4 的模板、任务、评分、审批、报表等入口等页面真的做出来再登记。
 * P0 那版占位菜单（灰掉的「评估模板 · P2」）是「看起来有、点进去空白」的状态，不如不显示。
 */
export interface MenuItem {
  /** 与路由完整路径一致（如 `/suppliers`），也是 `el-menu` 的 index */
  path: string
  title: string
  /** 可见角色；**空数组表示任意已登录角色都可见**（首页） */
  roles: Role[]
  /**
   * 只做**入口级权限**、不进侧边菜单（详情页这类"从列表点进去"的路径）。
   *
   * <p>为什么也要登记：`canAccess` 按最长前缀归属入口，未登记的路径**一律放行**——
   * 不登记就等于"直接输地址能进"。评分单页 `s/sheets/:id` 正是这种路径（P3 新增）。
   */
  hidden?: boolean
}

const MENUS: MenuItem[] = [
  { path: '/', title: '首页', roles: [] },
  // 01 §7：供应商维护 = ADMIN / MANAGER
  { path: '/suppliers', title: '供应商', roles: ['ADMIN', 'MANAGER'] },
  // 01 §7：评估模板管理 = ADMIN（写）/ MANAGER（查看），即两者都可见这个入口
  { path: '/templates', title: '评估模板', roles: ['ADMIN', 'MANAGER'] },
  // 01 §7：评估任务 = ADMIN（只读）/ MANAGER（建、改、发布）
  { path: '/tasks', title: '评估任务', roles: ['ADMIN', 'MANAGER'] },
  // 01 §7：我的待办 = 评估人与采购经理（管理员与只读用户不参与打分）
  { path: '/my', title: '我的待办', roles: ['MANAGER', 'EVALUATOR'] },
  // 打分页：从待办/详情点进去，不进菜单，但必须登记才能让守卫按前缀拦住越权角色
  { path: '/sheets', title: '评分单', roles: ['MANAGER', 'EVALUATOR'], hidden: true },
  // 01 §7：用户、系统设置 = 仅 ADMIN
  { path: '/users', title: '用户管理', roles: ['ADMIN'] },
  { path: '/settings', title: '系统设置', roles: ['ADMIN'] },
]

function allows(item: MenuItem, roles: Role[]): boolean {
  return item.roles.length === 0 || item.roles.some((role) => roles.includes(role))
}

/** 当前角色可见的菜单（保持登记顺序，首页永远在最前） */
export function visibleMenus(roles: Role[]): MenuItem[] {
  return MENUS.filter((item) => allows(item, roles) && !item.hidden)
}

/**
 * 该路径对当前角色是否可见。
 *
 * <p>匹配规则：先精确匹配；没有精确项时按**最长前缀**归属到某个入口（`/suppliers/123` → `/suppliers`，
 * 将来加详情页不必再登记一次）。表里查不到的路径一律放行——本表只管「入口级」可见性，
 * 登录页、将来的公开页不在其中；真正的越权判定始终在后端（4xxx + `ACCESS_DENIED` 审计）。
 */
export function canAccess(path: string, roles: Role[]): boolean {
  const exact = MENUS.find((item) => item.path === path)
  if (exact) {
    return allows(exact, roles)
  }
  // 前缀匹配有两条相互独立的保护，缺一条都可能让首页（对所有角色开放）吃掉别人的路径：
  // ① 模板带斜杠：`${'/'}/` = `//`，而 /suppliers 不以 `//` 开头，首页自然不参与；
  // ② 取最长前缀：即使 ① 被改动，/suppliers/123 也只会命中 /suppliers 而不是 /。
  // 这里显式排除首页，是把「首页不做前缀匹配」这个意图写出来，而不是依赖上面两条的巧合
  // （变异验证确认过：单独删掉这一行不会改变行为，所以它是意图声明而非守卫）。
  const byPrefix = MENUS.filter(
    (item) => item.path !== '/' && path.startsWith(`${item.path}/`),
  ).sort((left, right) => right.path.length - left.path.length)[0]
  return byPrefix ? allows(byPrefix, roles) : true
}