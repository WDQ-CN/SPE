import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { clearToken, getToken } from '@/app/http/token'
import { BizError } from '@/shared/error-code'

// vi.mock 的工厂会被提升到文件顶部，不能直接引用文件级变量——vi.hoisted 把可变状态一起提升
const mocks = vi.hoisted(() => ({
  login: vi.fn(),
  replace: vi.fn(),
  query: {} as Record<string, unknown>,
}))

vi.mock('@/features/auth/api', () => ({ login: mocks.login }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: mocks.query }),
  useRouter: () => ({ replace: mocks.replace }),
}))

import LoginView from './LoginView.vue'

/**
 * 登录页的行为（W4 的核心路径）：成功写会话并回跳、失败只在表单回显、恶意回跳被拦、不重复提交。
 *
 * <p>这几条都在「浏览器里点一遍」的范围内，但自动化后能防住的是**回归**——
 * 例如有人把 `safeRedirect` 去掉、或把失败分支改成 `ElMessage.error` 后仍然跳转。
 */
async function mountAndFill(
  username = 'admin',
  password = 'Admin@12345',
): Promise<VueWrapper> {
  const wrapper = mount(LoginView, { global: { plugins: [ElementPlus, createPinia()] } })
  const inputs = wrapper.findAll('input')
  await inputs[0].setValue(username)
  await inputs[1].setValue(password)
  return wrapper
}

async function clickSubmit(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('button').trigger('click')
  await flushPromises()
}

describe('登录页', () => {
  beforeEach(() => {
    mocks.login.mockReset()
    mocks.replace.mockReset()
    mocks.query = {}
    localStorage.clear()
    // token 是模块级响应式单例，localStorage 清空并不会重置它，必须显式清
    clearToken()
  })

  it('登录成功后写入会话，并跳到 redirect 指定的页面', async () => {
    mocks.query.redirect = '/suppliers'
    mocks.login.mockResolvedValue({
      token: 'token-login-1',
      name: '系统管理员',
      roles: ['ADMIN'],
      department: 'QUALITY',
    })

    await clickSubmit(await mountAndFill())

    expect(mocks.login).toHaveBeenCalledWith({ username: 'admin', password: 'Admin@12345' })
    expect(getToken()).toBe('token-login-1')
    expect(mocks.replace).toHaveBeenCalledWith('/suppliers')
  })

  it('没有 redirect 时回首页', async () => {
    mocks.login.mockResolvedValue({
      token: 'token-login-2',
      name: '系统管理员',
      roles: ['ADMIN'],
      department: null,
    })

    await clickSubmit(await mountAndFill())

    expect(mocks.replace).toHaveBeenCalledWith('/')
  })

  it('登录失败只在表单回显：不跳转、不写 token', async () => {
    mocks.login.mockRejectedValue(new BizError(1002, '账号或密码错误'))

    const wrapper = await mountAndFill('admin', 'wrong-password')
    await clickSubmit(wrapper)

    expect(wrapper.text()).toContain('账号或密码错误')
    expect(mocks.replace).not.toHaveBeenCalled()
    expect(getToken()).toBe('')
  })

  it('被限流（1003）同样只在表单回显，不跳登录页', async () => {
    mocks.login.mockRejectedValue(new BizError(1003, '尝试过于频繁，请稍后再试'))

    const wrapper = await mountAndFill()
    await clickSubmit(wrapper)

    expect(wrapper.text()).toContain('尝试过于频繁')
    expect(mocks.replace).not.toHaveBeenCalled()
  })

  it('恶意的 redirect 被拦下（开放重定向防护）', async () => {
    mocks.query.redirect = '//evil.com'
    mocks.login.mockResolvedValue({
      token: 'token-login-3',
      name: '系统管理员',
      roles: ['ADMIN'],
      department: null,
    })

    await clickSubmit(await mountAndFill())

    expect(mocks.replace).toHaveBeenCalledWith('/')
  })

  it('提交过程中重复点击只发一次请求', async () => {
    let release: (value: unknown) => void = () => {}
    mocks.login.mockImplementation(
      () =>
        new Promise((resolve) => {
          release = resolve
        }),
    )

    const wrapper = await mountAndFill()
    // 两次点击都发生在表单校验完成之前：只有把闸门放在**同步入口**处才挡得住第二次。
    // 这正是本用例存在的价值——最初的实现把 submitting 写在 `await validate()` 之后，
    // 两次点击会同时通过检查并发两次请求（04-037）。
    await wrapper.find('button').trigger('click')
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(mocks.login).toHaveBeenCalledTimes(1)

    release({ token: 'token-login-4', name: 'x', roles: [], department: null })
    await flushPromises()
  })

  it('账号或密码为空时不发请求（前端先挡住明显缺项）', async () => {
    const wrapper = mount(LoginView, { global: { plugins: [ElementPlus, createPinia()] } })

    await clickSubmit(wrapper)

    expect(mocks.login).not.toHaveBeenCalled()
    expect(mocks.replace).not.toHaveBeenCalled()
  })
})
