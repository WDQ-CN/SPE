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
