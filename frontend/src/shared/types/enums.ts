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

/**
 * 模板版本状态（`V7__template.sql` 的 status 列）。
 *
 * P2 只实现 **DRAFT → PUBLISHED** 一条迁移；`ARCHIVED` 是**枚举值预留、没有入口能进入**该状态
 * （02 §3 未规定"废弃"的进入条件，12 §E10）——与 07 §4 对任务/评分单「枚举值预留、入口后置」
 * 的既有做法一致。前端把它列出来是为了后端将来真用上时不至于显示成空白，**不表示现在可选**。
 */
export type TemplateVersionStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'

/** 指标类型（12 §E2）：`QUAL`（定性）时 `scoreMode` 为 `null`，计分策略由它决定 */
export type IndicatorType = 'QUANT' | 'QUAL'

/** 计分方式（01 §6.1 / 12 §E2）：仅定量指标有值 */
export type ScoreMode = 'DIRECT' | 'RATIO' | 'TARGET'

/**
 * 评估任务状态（02 §3.1 的 `eval_task.status` / 06 §2.1）。
 *
 * P3 只有 **DRAFT →（发布）→ IN_PROGRESS** 一条可达迁移；`COMPLETED`（J3：全部评估单生效）要等 P4 的定稿，
 * `CANCELED`（J6）是机会项——**列出来是为了显示历史数据，不表示现在可选**（07 §4 的口径）。
 */
export type EvalTaskStatus = 'DRAFT' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELED'

/** 评估单状态（06 §2.1）：P3 只产生 SCORING → PENDING_APPROVAL；APPROVING/EFFECTIVE 属 P4。 */
export type TaskSupplierStatus = 'SCORING' | 'PENDING_APPROVAL' | 'APPROVING' | 'EFFECTIVE'

/** 评分单状态（06 §2.1）：P3 三态全部可达。 */
export type SheetStatus = 'UNASSIGNED' | 'SCORING' | 'SUBMITTED'
