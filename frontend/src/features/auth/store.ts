import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'

import { clearToken, setToken, useToken } from '@/app/http/token'
import { ROLE_LABEL } from '@/shared/labels'
import { clearProfile, readProfile, saveProfile } from './session'
import type { Department, Role } from '@/shared/types/enums'

export interface AuthSession {
  token: string
  name: string
  roles: Role[]
  department: Department | null
}

export const useAuthStore = defineStore('auth', () => {
  // 与 http 层共用同一响应式 token：拦截器在 401 清 token 后，这里立即变为未登录
  const token = useToken()
  // 刷新后从本地恢复身份信息；没有它，页面会「已登录却显示未登录」，角色菜单也渲染不出来
  const profile = readProfile()
  const name = ref(profile?.name ?? '')
  const roles = ref<Role[]>(profile?.roles ?? [])
  const department = ref<Department | null>(profile?.department ?? null)

  // 401 等路径由 http 层直接清 token，这里同步收敛身份信息，避免清完 token 还留着上一个人的角色
  watch(
    token,
    (value) => {
      if (!value) {
        name.value = ''
        roles.value = []
        department.value = null
        clearProfile()
      }
    },
    { flush: 'sync' },
  )

  const isAuthenticated = computed(() => token.value !== '')
  // 权限/展示的单一来源：视图不要再各自 `roles.includes('ADMIN')` 或拼角色文案
  const isAdmin = computed(() => roles.value.includes('ADMIN'))
  const roleText = computed(() => roles.value.map((role) => ROLE_LABEL[role]).join(' / ') || '—')

  function setSession(session: AuthSession): void {
    setToken(session.token)
    name.value = session.name
    roles.value = session.roles
    department.value = session.department
    saveProfile({ name: session.name, roles: session.roles, department: session.department })
  }

  function clearSession(): void {
    clearToken()
  }

  return {
    token,
    name,
    roles,
    department,
    isAuthenticated,
    isAdmin,
    roleText,
    setSession,
    clearSession,
  }
})
