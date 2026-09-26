/** 用户角色（01 §2 / 09 D5） */
export type Role = 'ADMIN' | 'MANAGER' | 'EVALUATOR' | 'VIEWER'

/** 用户所属部门 code，大写常量（01 §2 部门口径 / 09 D5） */
export type Department =
  | 'QUALITY'
  | 'DELIVERY'
  | 'PURCHASE'
  | 'PRODUCTION'
  | 'FINANCE'
  | 'SERVICE'
  | 'OTHER'

/**
 * 用户账号状态（`V1__sys_user.sql` 的 status 列，两态机）。
 *
 * 与 `SupplierStatus` 值域相同但**语义不同**（账号 ↔ 档案），故各留一份——
 * 后端也是这么分的（10 §E16）；前端若复用同一个类型，将来供应商加档位就会污染账号状态。
 */
export type UserStatus = 'ENABLED' | 'DISABLED'

/** 供应商档案状态（`V3__supplier.sql` 的 status 列，两态机） */
export type SupplierStatus = 'ENABLED' | 'DISABLED'
