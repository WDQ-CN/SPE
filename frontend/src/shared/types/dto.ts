import type { Department, Role, SupplierStatus, UserStatus } from './enums'

/**
 * 后端响应投影的前端镜像：**字段与后端 record 一一对应**（camelCase）。
 *
 * <p>集中放在 `shared/types` 而不是各 feature 里，是因为它们跨越 feature 边界：
 * 用户页与将来的审批页都要看 `UserSummary`，供应商页与将来的任务页都要看 `SupplierSummary`。
 * 字段契约由 `dto.spec.ts` 用固定样例断言（后端加字段而前端不知道，是这类项目最常见的漂移）。
 */

/** 分页请求参数（`page` 从 1 开始，与后端 `PageQuery` 一致；收敛规则在后端，前端不重复实现） */
export interface PageParams {
  page: number
  size: number
}

/** 用户列表行（后端 `UserSummary`）：`version` 是编辑与启停的守卫条件，必须随行携带 */
export interface UserSummary {
  id: number
  username: string
  name: string
  role: Role
  department: Department
  status: UserStatus
  version: number
}

/** 供应商列表行（后端 `SupplierSummary`）：`category` 可空（10 §E10） */
export interface SupplierSummary {
  id: number
  code: string
  name: string
  category: string | null
  status: SupplierStatus
  version: number
}

/** 等级阈值（后端 `GradeThresholds`）：后端是 `BigDecimal`，JSON 里是数字 */
export interface GradeThresholds {
  a: number
  b: number
  c: number
  d: number
}
