import type { Router } from 'vue-router'

import { useAuthStore } from '@/features/auth/store'

/**
 * 鉴权守卫：无 token 访问受保护路由跳登录页（09 WP2 / 06 §3.3）。
 * 角色化菜单与数据可见性在 P1 落地，此处只做登录态判断。
 */
export function registerGuard(router: Router): void {
  router.beforeEach((to) => {
    const auth = useAuthStore()

    if (!to.meta.public && !auth.isAuthenticated) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    if (to.meta.public && auth.isAuthenticated) {
      return { name: 'home' }
    }
    return true
  })
}
