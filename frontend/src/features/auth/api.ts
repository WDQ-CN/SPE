import { http } from '@/app/http'
import type { Department, Role } from '@/shared/types/enums'

/** 登录入参（后端 `LoginRequest`）：账号 ≤50，口令只做非空——长度规则由后端 `PasswordPolicy` 判定 */
export interface LoginPayload {
  username: string
  password: string
}

/**
 * 登录响应（后端 `AuthController.LoginResponse`）。
 *
 * <p>`department` 可能为 null（`sys_user.department` 允许为空），前端据此按「未知部门」展示。
 */
export interface LoginResult {
  token: string
  name: string
  roles: Role[]
  department: Department | null
}

/**
 * 登录（01 A1 / 02 §4）。
 *
 * <p>失败时**不在这里做任何兜底**：1002/1003 与 2xxx 由 `app/http` 拦截器按「表单级」抛出
 * `BizError`，调用方只负责把 message 显示在表单上（10 §E2、03 §16 的 SEC-5）。
 */
export function login(payload: LoginPayload): Promise<LoginResult> {
  return http.post<LoginResult>('/auth/login', payload)
}
