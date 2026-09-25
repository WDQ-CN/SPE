import { ref, type Ref } from 'vue'

const TOKEN_KEY = 'spe.token'

function readStored(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

/**
 * 单一响应式来源：store 与 http 拦截器共用同一个 ref。
 * 两边各持一份时，拦截器在 401 只清 localStorage 而 store 的 ref 不变，
 * 守卫会认为仍处于登录态，把刚送去登录页的用户弹回首页。
 */
const token = ref(readStored())

export function getToken(): string {
  return token.value
}

export function setToken(value: string): void {
  token.value = value
  if (value) {
    localStorage.setItem(TOKEN_KEY, value)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

export function clearToken(): void {
  setToken('')
}

/** 供 auth store 复用，使 isAuthenticated 随清 token 立即变化 */
export function useToken(): Ref<string> {
  return token
}
