import type { Role, SheetStatus } from '@/shared/types/enums'

/**
 * 评分单页面的操作闸门（13 §E19 点名要抽出来的第二个纯函数）。
 *
 * <p>为什么必须抽出来：**判错了不会报错，只会静默显示错东西**——把"已提交"的单子渲染成可编辑，
 * 用户改完提交才发现被拒；或者明明是我的单子却不给编辑入口。
 *
 * <p>⚠️ `loaded` 这个参数是 P2 的教训搬过来的（03 §31.7.16 的 P2-1）：切换评分单时要**先清数据再渲染**，
 * 而"数据还没到"的窗口期里**任何写入口都不能可用**——否则用户可能在旧数据上点保存，写进新的那张单子。
 * 所以把"有没有数据"也做成闸门的一部分，而不是靠模板里的 `v-if` 各写一遍。
 */
export interface SheetPermissions {
  /** 能不能改录入值（含暂存） */
  canEdit: boolean
  /** 能不能提交 */
  canSubmit: boolean
  /** 能不能改派（采购经理） */
  canReassign: boolean
  /** 不能编辑时给用户的一句解释；可编辑时为空串 */
  readOnlyHint: string
}

/**
 * @param status 评分单状态
 * @param loaded 页面是否已经拿到**当前这张**评分单的数据（窗口期为 false）
 * @param isAssignee 当前登录人是不是这张单子的负责人
 * @param roles 当前登录人的角色
 */
export function sheetPermissions(
  status: SheetStatus,
  loaded: boolean,
  isAssignee: boolean,
  roles: Role[],
): SheetPermissions {
  const isManager = roles.includes('MANAGER')
  const canReassign = isManager && (status === 'UNASSIGNED' || status === 'SCORING')
  // 写入口一律要求"已加载 + 是我的单子 + 状态是评分中"三个条件同时成立
  const canEdit = loaded && isAssignee && status === 'SCORING'
  const canSubmit = canEdit

  let readOnlyHint = ''
  if (!loaded) {
    readOnlyHint = '' // 加载中：不显示"只读"，避免闪烁出误导性的说明
  } else if (status === 'UNASSIGNED') {
    readOnlyHint = '这张评分单还没有指派评估人，等采购经理指派后即可录入'
  } else if (status === 'SUBMITTED') {
    readOnlyHint = '已提交的评分单不可修改（如需修改请联系采购经理驳回）'
  } else if (!isAssignee) {
    readOnlyHint = '这不是指派给你的评分单'
  }

  return { canEdit, canSubmit, canReassign, readOnlyHint }
}

/** 待办列表的空态文案（按角色区分：评估人没有待办 vs 采购经理没有待办，下一步动作不同）。 */
export function mySheetsEmptyHint(roles: Role[]): string {
  return roles.includes('MANAGER')
    ? '当前没有指派给你的评分单；可在「评估任务」里发布任务或调整指派'
    : '当前没有待办的评分单，等采购经理指派后会出现在这里'
}
