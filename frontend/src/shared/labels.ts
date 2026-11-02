import type {
  CorrectiveActionStatus,
  Department,
  EvalTaskStatus,
  IndicatorType,
  Role,
  ScoreMode,
  SheetStatus,
  SupplierStatus,
  TaskSupplierStatus,
  TemplateVersionStatus,
  UserStatus,
  VerifyOutcome,
} from './types/enums'

/**
 * 枚举的界面文案与下拉选项——**中文标签的唯一来源**（01 §2 的角色表与部门口径）。
 *
 * <p>不把这些散在各页面里：同一个角色/部门会在用户页、将来的人员选择器、审批链配置里反复出现，
 * 各写一份的结果必然是某处漏改（同一个约定写两遍就会漂移）。标签措辞直接取自 01 §2，
 * 不要在这里自造别称——术语以规格为准（06 §2.1）。
 */

export const ROLE_LABEL: Record<Role, string> = {
  ADMIN: '系统管理员',
  MANAGER: '采购经理',
  EVALUATOR: '评估人',
  VIEWER: '只读用户',
}

export const DEPARTMENT_LABEL: Record<Department, string> = {
  QUALITY: '质量',
  DELIVERY: '交付物流',
  PURCHASE: '采购',
  PRODUCTION: '生产',
  FINANCE: '财务',
  SERVICE: '服务',
  OTHER: '其他',
}

export const USER_STATUS_LABEL: Record<UserStatus, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
}

export const SUPPLIER_STATUS_LABEL: Record<SupplierStatus, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
}

export const TEMPLATE_VERSION_STATUS_LABEL: Record<TemplateVersionStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  // P2 没有进入该状态的入口（V7 的注释与 12 §E10）；列出来只为将来真用上时不显示空白
  ARCHIVED: '已归档',
}

export const INDICATOR_TYPE_LABEL: Record<IndicatorType, string> = {
  QUANT: '定量',
  QUAL: '定性',
}

/** 三种计分方式（01 §6.1）；仅定量指标有值 */
export const SCORE_MODE_LABEL: Record<ScoreMode, string> = {
  DIRECT: '直接打分',
  RATIO: '比率',
  TARGET: '目标达成率',
}

/** 下拉选项：`value` 用 code（与后端枚举取值一致），`label` 取自上面的文案表 */
export interface SelectOption<T extends string> {
  value: T
  label: string
}

/** 由文案表生成选项，避免每个枚举各写一遍同样的 map（可选值集合与文案永远同源） */
function toOptions<T extends string>(labelOf: Record<T, string>): SelectOption<T>[] {
  return (Object.keys(labelOf) as T[]).map((value) => ({ value, label: labelOf[value] }))
}

export const ROLE_OPTIONS: SelectOption<Role>[] = toOptions(ROLE_LABEL)
export const DEPARTMENT_OPTIONS: SelectOption<Department>[] = toOptions(DEPARTMENT_LABEL)
export const INDICATOR_TYPE_OPTIONS: SelectOption<IndicatorType>[] = toOptions(INDICATOR_TYPE_LABEL)
export const SCORE_MODE_OPTIONS: SelectOption<ScoreMode>[] = toOptions(SCORE_MODE_LABEL)

export const EVAL_TASK_STATUS_LABEL: Record<EvalTaskStatus, string> = {
  DRAFT: '草稿',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  CANCELED: '已取消',
}

export const TASK_SUPPLIER_STATUS_LABEL: Record<TaskSupplierStatus, string> = {
  SCORING: '评分中',
  PENDING_APPROVAL: '待审批',
  APPROVING: '审批中',
  EFFECTIVE: '已生效',
}

export const SHEET_STATUS_LABEL: Record<SheetStatus, string> = {
  UNASSIGNED: '待指派',
  SCORING: '评分中',
  SUBMITTED: '已提交',
}

/** 审批动作（`approval_record.action`）：后端只写这两个值（15 §E2） */
export const APPROVAL_ACTION_LABEL: Record<string, string> = {
  APPROVE: '通过',
  REJECT: '驳回',
}

/** 评分记录来源（`score.source`）：报告页的「来源」列（01 R3） */
export const SCORE_SOURCE_LABEL: Record<string, string> = {
  MANUAL: '手工录入',
  IMPORT: 'Excel 导入',
  API: '开放接口',
}

/** M5 预警（01 W3）：类型与状态 */
export const ALERT_TYPE_LABEL: Record<string, string> = {
  TOTAL_LOW: '总分低于阈值',
  KEY_DIMENSION_LOW: '关键维度低于阈值',
  TREND_DECLINE: '总分连续下降',
}

export const ALERT_STATUS_LABEL: Record<string, string> = {
  UNREAD: '未读',
  HANDLED: '已处理',
  SUPERSEDED: '已取代',
}

/** 整改任务状态（V1004 / 29 §3.2）：状态机键与后端逐字一致 */
export const CORRECTIVE_ACTION_STATUS_LABEL: Record<CorrectiveActionStatus, string> = {
  OPEN: '待开始',
  IN_PROGRESS: '整改中',
  WAITING_VERIFICATION: '待验证',
  VERIFIED: '已通过',
  REOPENED: '继续整改',
  CANCELED: '已取消',
}

export const CORRECTIVE_ACTION_STATUS_OPTIONS: SelectOption<CorrectiveActionStatus>[] =
  toOptions(CORRECTIVE_ACTION_STATUS_LABEL)

/** 下期验证结论（V1004 `verify_outcome`） */
export const VERIFY_OUTCOME_LABEL: Record<VerifyOutcome, string> = {
  IMPROVED: '已改善',
  NOT_IMPROVED: '未改善',
  PENDING: '待人工判断',
}

/** M5 操作日志（SM5）：目标类型（动作码保持后端原值，不在这里造中文别名） */
export const AUDIT_TARGET_TYPE_LABEL: Record<string, string> = {
  USER: '用户',
  SUPPLIER: '供应商',
  SETTING: '系统设置',
  TEMPLATE: '模板',
  TASK: '任务',
  TASK_SUPPLIER: '评估单',
  TOKEN: 'API Token',
  CORRECTIVE_ACTION: '整改任务',
}

export const API_TOKEN_STATUS_LABEL: Record<string, string> = {
  ENABLED: '启用',
  DISABLED: '已停用',
}

export const GRADE_ORDER = ['A', 'B', 'C', 'D'] as const
