import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import { clearToken, getToken } from '@/app/http/token'
import { useAuthStore } from './store'

/**
 * 登录态的生命周期。
 *
 * <p>这套逻辑有一处历史坑（04-007）：token 与「姓名/角色/部门」如果各存一份，
 * 401 清 token 后守卫读到的仍是旧的登录态，表现为「已登出却还能进、菜单还在」。
 * 用例专门盯这条：清 token 必须同步收敛身份信息。
 */
describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    clearToken()
  })

  it('setSession 写入 token 与身份信息', () => {
    const auth = useAuthStore()
    auth.setSession({
      token: 'token-1',
      name: '系统管理员',
      roles: ['ADMIN'],
      department: 'QUALITY',
    })

    expect(getToken()).toBe('token-1')
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.name).toBe('系统管理员')
    expect(auth.roles).toEqual(['ADMIN'])
    expect(auth.department).toBe('QUALITY')
  })

  it('刷新页面后从本地恢复身份信息（否则会「已登录却显示未登录」）', () => {
    useAuthStore().setSession({
      token: 'token-2',
      name: '采购经理',
      roles: ['MANAGER'],
      department: 'PURCHASE',
    })

    // 模拟刷新：新建 pinia 实例，store 重新初始化
    setActivePinia(createPinia())
    const restored = useAuthStore()

    expect(restored.name).toBe('采购经理')
    expect(restored.roles).toEqual(['MANAGER'])
    expect(restored.department).toBe('PURCHASE')
  })

  it('clearSession 清 token 时同步清掉身份信息（不留上一个人的角色）', () => {
    const auth = useAuthStore()
    auth.setSession({
      token: 'token-3',
      name: '系统管理员',
      roles: ['ADMIN'],
      department: 'QUALITY',
    })

    auth.clearSession()

    expect(auth.isAuthenticated).toBe(false)
    expect(auth.name).toBe('')
    expect(auth.roles).toEqual([])
    expect(auth.department).toBeNull()
  })

  it('http 层直接清 token（401 路径）时，身份信息也被同步收敛', () => {
    const auth = useAuthStore()
    auth.setSession({
      token: 'token-4',
      name: '评估人',
      roles: ['EVALUATOR'],
      department: 'DELIVERY',
    })

    // 拦截器在 401 时直接调 clearToken，不经过 store
    clearToken()

    expect(auth.isAuthenticated).toBe(false)
    expect(auth.roles).toEqual([])
  })

  it('本地身份信息被写脏时忽略它而不是让应用起不来', () => {
    localStorage.setItem('spe.auth.profile', '{ 这不是 JSON')

    const auth = useAuthStore()

    expect(auth.name).toBe('')
    expect(auth.roles).toEqual([])
  })
})
