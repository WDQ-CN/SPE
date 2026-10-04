import type {
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
