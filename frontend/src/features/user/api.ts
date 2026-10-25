import { http } from '@/app/http'
import type { PageResult } from '@/shared/types/result'
import type { Department, Role, UserStatus } from '@/shared/types/enums'
import type { PageParams, UserOption, UserSummary } from '@/shared/types/dto'

/**
 * 用户管理接口（02 §4 / 01 SM1、A4），**仅 ADMIN**。
 *
 * <p>所有写操作都要带 `version`（编辑与启停）——它是后端守卫 UPDATE 的前置条件，缺了就无法区分
 * 「改到的是我看到的那一行」（10 §E13）。前端只负责原样回传列表里拿到的版本号。
 */

/** 新增用户（后端 `CreateUserRequest`）：新建一律 ENABLED，不能指定状态 */
export interface CreateUserPayload {
  username: string
  password: string
  name: string
  role: Role
  department: Department
}

/** 编辑用户（后端 `UpdateUserRequest`）：账号不可改，故入参里没有 username */
export interface UpdateUserPayload {
  name: string
  role: Role
  department: Department
  version: number
}

export function listUsers(params: PageParams): Promise<PageResult<UserSummary>> {
  return http.get<PageResult<UserSummary>>('/users', params)
}

/**
 * 可选人员（02 §4 / 03 §38 的 P0-1）：`roles` 留空 = 不过滤角色（目录面的通用能力）。
 * 审批链选择器固定传 `MANAGER,ADMIN`（01 §3 经 2026-10-25 裁决收窄，`21 §3.W1-1`），
 * 改派则按 `TaskService.requireEligibleAssignee` 的合法集合传 `EVALUATOR,MANAGER`。
 *
 * <p>⚠️ 传**逗号拼接的单个 query 值**而不是数组：axios 默认把数组序列化成 `roles[]=A`，
 * 而后端 `@RequestParam(name="roles") List<String>` 认的是 `roles=A,B`。写错的表现是
 * "过滤没生效/下拉里没人"，不报错——属 04-071 那一族的静默失效。
 */
export function listUserOptions(roles?: readonly Role[]): Promise<UserOption[]> {
  const joined = roles && roles.length > 0 ? roles.join(',') : undefined
  return http.get<UserOption[]>('/user-options', { roles: joined })
}

export function createUser(payload: CreateUserPayload): Promise<UserSummary> {
  return http.post<UserSummary>('/users', payload)
}

export function updateUser(id: number, payload: UpdateUserPayload): Promise<UserSummary> {
  return http.put<UserSummary>(`/users/${id}`, payload)
}

/** 启停：只传**目标状态**，前置状态由后端按两态机推出（10 §E13） */
export function changeUserStatus(
  id: number,
  status: UserStatus,
  version: number,
): Promise<void> {
  return http.put<void>(`/users/${id}/status`, { status, version })
}

/** ADMIN 重置他人口令（01 SM1）：口令规则由后端 `PasswordPolicy` 判定，前端只做长度提示 */
export function resetUserPassword(id: number, newPassword: string): Promise<void> {
  return http.put<void>(`/users/${id}/password`, { newPassword })
}
