import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { clearToken, setToken, useToken } from '@/app/http/token'
import type { Department, Role } from '@/shared/types/enums'

export interface AuthSession {
  token: string
  name: string
  roles: Role[]
  /** 部门 code；null 表示尚未加载（P1 由登录响应填充） */
  department: Department | null
}

export const useAuthStore = defineStore('auth', () => {
  // 与 http 层共用同一响应式 token：拦截器在 401 清 token 后，这里立即变为未登录
  const token = useToken()
  const name = ref('')
  const roles = ref<Role[]>([])
  const department = ref<Department | null>(null)

  const isAuthenticated = computed(() => token.value !== '')

  function setSession(session: AuthSession): void {
    setToken(session.token)
    name.value = session.name
    roles.value = session.roles
    department.value = session.department
  }

  function clearSession(): void {
    clearToken()
    name.value = ''
    roles.value = []
    department.value = null
  }

  return { token, name, roles, department, isAuthenticated, setSession, clearSession }
})
