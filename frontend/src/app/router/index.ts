import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { setNavigate } from '@/app/http/navigation'
import { APP_NAME } from '@/shared/constants'
import { registerGuard } from './guard'

/**
 * 路由表。入口级权限不写在这里——`meta` 只放标题，可见角色统一由 `app/menu.ts` 判定，
 * 免得同一份权限规则在路由与菜单里各写一份（那也是「菜单隐藏了但还能进」的常见成因）。
 */
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
      {
        path: 'suppliers',
        name: 'suppliers',
        component: () => import('@/features/supplier/views/SupplierListView.vue'),
        meta: { title: '供应商' },
      },
      // 编辑器的路径挂在 /templates 下，靠 menu.ts 的**前缀匹配**继承入口级权限
      // （`/templates/12` → `/templates` → ADMIN/MANAGER）；直接输 URL 也拦得住。
      // 路径里的参数是**版本** id 而不是模板 id —— 编辑器编辑的是某个版本，
      // 模板名从版本树里取（后端 TemplateVersionTree 带 templateName）。
      {
        path: 'templates',
        name: 'templates',
        component: () => import('@/features/template/views/TemplateListView.vue'),
        meta: { title: '评估模板' },
      },
      {
        path: 'templates/:versionId',
        name: 'template-editor',
        component: () => import('@/features/template/views/TemplateEditorView.vue'),
        meta: { title: '模板编辑器' },
      },
      // 详情页与打分页都挂在各自的**列表入口**之下（/tasks、/my），靠 menu.ts 的前缀匹配继承权限：
      // /tasks/12 → /tasks、/sheets/9 → /sheets（评分单入口登记为 hidden，不进菜单但受管辖）。
      {
        path: 'tasks',
        name: 'tasks',
        component: () => import('@/features/evaluation/views/TaskListView.vue'),
        meta: { title: '评估任务' },
      },
      {
        path: 'tasks/:id',
        name: 'task-detail',
        component: () => import('@/features/evaluation/views/TaskDetailView.vue'),
        meta: { title: '任务详情' },
      },
      {
        path: 'my',
        name: 'my-sheets',
        component: () => import('@/features/evaluation/views/MySheetsView.vue'),
        meta: { title: '我的待办' },
      },
      {
        path: 'sheets/:id',
        name: 'sheet-scoring',
        component: () => import('@/features/evaluation/views/SheetScoringView.vue'),
        meta: { title: '评分' },
      },
      {
        path: 'approvals',
        name: 'approvals',
        component: () => import('@/features/evaluation/views/MyApprovalsView.vue'),
        meta: { title: '我的审批' },
      },
      {
        path: 'approvals/:id',
        name: 'approval-detail',
        component: () => import('@/features/evaluation/views/ApprovalDetailView.vue'),
        meta: { title: '审批' },
      },
      {
        path: 'results',
        name: 'results',
        component: () => import('@/features/evaluation/views/ResultTasksView.vue'),
        meta: { title: '结果报表' },
      },
      {
        path: 'results/:id',
        name: 'task-results',
        component: () => import('@/features/evaluation/views/TaskResultsView.vue'),
        meta: { title: '任务结果' },
      },
      {
        path: 'reports/:id',
        name: 'report',
        component: () => import('@/features/evaluation/views/TaskSupplierReportView.vue'),
        meta: { title: '评估报告' },
      },
      {
        path: 'analytics',
        name: 'analytics',
        component: () => import('@/features/analytics/views/AnalyticsView.vue'),
        meta: { title: '分析看板' },
      },
      {
        path: 'alerts',
        name: 'alerts',
        component: () => import('@/features/alert/views/AlertListView.vue'),
        meta: { title: '预警' },
      },
      {
        path: 'audit',
        name: 'audit',
        component: () => import('@/features/audit/views/AuditLogView.vue'),
        meta: { title: '操作日志' },
      },
      {
        path: 'api-tokens',
        name: 'api-tokens',
        component: () => import('@/features/api-token/views/ApiTokenView.vue'),
        meta: { title: '开放 API' },
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/features/user/views/UserListView.vue'),
        meta: { title: '用户管理' },
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/features/setting/views/SettingView.vue'),
        meta: { title: '系统设置' },
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
// 跳登录页时带上来源路径，与守卫的 redirect 约定保持一致。
// 一个页面并发多个请求会有多个 401，只让第一次带上 redirect：后续几次再 replace 会把来源路径覆盖成空。
let loginNavigationPending = false

setNavigate((path) => {
  if (path !== '/login') {
    void router.replace({ path })
    return
  }
  const current = router.currentRoute.value
  if (loginNavigationPending || current.path === '/login') {
    return
  }
  loginNavigationPending = true
  void router.replace({ path, query: { redirect: current.fullPath } })
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · ${APP_NAME}` : APP_NAME
  loginNavigationPending = false
})

registerGuard(router)
