import type { Department, Role, SupplierStatus, UserStatus } from './types/enums'

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
