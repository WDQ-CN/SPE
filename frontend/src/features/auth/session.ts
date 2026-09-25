import type { Department, Role } from '@/shared/types/enums'

/**
 * 刷新后需要恢复的登录态。token 不在这里——它由 `app/http/token.ts` 单独持久化，
 * 两处都存会出现「谁是真身」的问题（见《04-开发小记》007）。
 */
export interface AuthProfile {
  name: string
  roles: Role[]
  department: Department | null
}

const PROFILE_KEY = 'spe.auth.profile'

export function readProfile(): AuthProfile | null {
  const raw = localStorage.getItem(PROFILE_KEY)
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw) as AuthProfile
  } catch {
    // 手工改坏或被旧版本写脏：清掉而不是让应用起不来
    localStorage.removeItem(PROFILE_KEY)
    return null
  }
}

export function saveProfile(profile: AuthProfile): void {
  localStorage.setItem(PROFILE_KEY, JSON.stringify(profile))
}

export function clearProfile(): void {
  localStorage.removeItem(PROFILE_KEY)
}
