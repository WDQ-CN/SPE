import type { Router } from 'vue-router'

import { canAccess } from '@/app/menu'
import { useAuthStore } from '@/features/auth/store'
import { portalAuthenticated } from '@/features/portal/api'

/**
 * 鉴权守卫（09 WP2 / 06 §3.3）：无 token 访问受保护路由跳登录页；已登录访问登录页回首页。
 *
 * <p>另外做一层**入口级**的角色拦截（`app/menu.ts` 的 `canAccess`）：无权限的路径直接回首页，
 * 而不是渲染出一个只能报错的页面。这只是体验——菜单不渲染、路由挡回，都改不了后端的判定权：
 * 越权请求仍然会拿到 4xxx 并留 `ACCESS_DENIED` 审计（01 §7、10 §E15）。
 */
export function registerGuard(router: Router): void {
  router.beforeEach((to) => {
    const auth = useAuthStore()

    // 供应商门户是独立身份：不看内部 token，也不允许内部登录态把它重定向回首页
    if (to.meta.portal) {
      if (to.name === 'portal-login' || to.name === 'portal-invite') {
        return true
      }
      return portalAuthenticated() ? true : { name: 'portal-login', query: { redirect: to.fullPath } }
    }

    if (!to.meta.public && !auth.isAuthenticated) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    if (to.meta.public && auth.isAuthenticated) {
      return { name: 'home' }
    }
    if (!to.meta.public && !canAccess(to.path, auth.roles)) {
      return { name: 'home' }
    }
    return true
  })
}
