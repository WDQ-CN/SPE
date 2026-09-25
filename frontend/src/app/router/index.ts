import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { setNavigate } from '@/app/http/navigation'
import { APP_NAME } from '@/shared/constants'
import { registerGuard } from './guard'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/features/auth/views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: () => import('@/app/layouts/DefaultLayout.vue'),
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/app/views/HomeView.vue'),
        meta: { title: '首页' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

// http 层（401 等）通过该端口跳转，避免 axios 模块反向依赖路由模块形成环；
// 跳登录页时带上来源路径，与守卫的 redirect 约定保持一致
setNavigate((path) => {
  const current = router.currentRoute.value
  const query =
    path === '/login' && current.path !== '/login' ? { redirect: current.fullPath } : undefined
  void router.replace({ path, query })
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · ${APP_NAME}` : APP_NAME
})

registerGuard(router)
