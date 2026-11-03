import type { CorrectiveActionSummary } from '@/shared/types/dto'
import type { CorrectiveActionStatus, Role, VerifyOutcome } from '@/shared/types/enums'

/**
 * 整改任务的状态/角色判定（纯函数，无 Vue/HTTP 依赖）。
 *
 * <p>所有按钮显隐集中在这里，页面只消费结果；这样「什么状态能做什么」可以用穷举单测钉住，
 * 不会散在模板的 v-if 里各写一遍。
 */

export type AlertActionState = 'action' | 'create' | 'handle' | 'handled' | 'legacy' | 'none'

/** 预警行「处理/整改」单元格要展示的入口 */
export interface AlertActionEntry {
  state: AlertActionState
  actionId: number | null
  actionStatus: CorrectiveActionStatus | null
  canCreate: boolean
  canHandle: boolean
  /** 已有整改时是否给可点击的详情链接；VIEWER 可以看状态但不能进入 /actions */
  showActionLink: boolean
  legacyHint: boolean
}

interface AlertEntryInput {
  status: string
  actionable: boolean
  correctiveActionId: number | null
  correctiveActionStatus: CorrectiveActionStatus | null
}

function isManager(roles: readonly Role[]): boolean {
  return roles.includes('ADMIN') || roles.includes('MANAGER')
}

/**
 * 预警行的整改入口（优先级：已有整改 → 可创建 → 已处理 → 旧预警 → 无动作）。
 *
 * <p>旧预警（V1004 前生成，无基线/目标）只允许原「处理」流程；VIEWER 不出现任何写按钮。
 */
export function alertActionEntry(
  alert: AlertEntryInput,
  roles: readonly Role[],
): AlertActionEntry {
  const manager = isManager(roles)
  if (alert.correctiveActionId != null) {
    return {
      state: 'action',
      actionId: alert.correctiveActionId,
      actionStatus: alert.correctiveActionStatus,
      canCreate: false,
      canHandle: false,
      showActionLink: manager,
      legacyHint: false,
    }
  }
  if (alert.actionable) {
    return {
      state: manager ? 'create' : 'none',
      actionId: null,
      actionStatus: null,
      canCreate: manager,
      canHandle: manager,
      showActionLink: false,
      legacyHint: false,
    }
  }
  if (alert.status === 'HANDLED') {
    return {
      state: 'handled',
      actionId: null,
      actionStatus: null,
      canCreate: false,
      canHandle: false,
      showActionLink: false,
      legacyHint: false,
    }
  }
  if (alert.status === 'UNREAD') {
    return {
      state: 'legacy',
      actionId: null,
      actionStatus: null,
      canCreate: false,
      canHandle: manager,
      showActionLink: false,
      legacyHint: true,
    }
  }
  return {
    state: 'none',
    actionId: null,
    actionStatus: null,
    canCreate: false,
    canHandle: false,
    showActionLink: false,
    legacyHint: false,
  }
}

/** 列表/详情状态标记的 CSS 类；OPEN 不套额外颜色 */
export function actionStatusClass(status: CorrectiveActionStatus | null): string {
  switch (status) {
    case 'VERIFIED':
      return 'is-success'
    case 'IN_PROGRESS':
    case 'WAITING_VERIFICATION':
    case 'REOPENED':
      return 'is-warning'
    case 'CANCELED':
      return 'is-off'
    default:
      return ''
  }
}

export interface ActionPermissions {
  canEdit: boolean
  canStart: boolean
  canSubmit: boolean
  canVerify: boolean
  canCancel: boolean
  readOnlyHint: string
}

const EMPTY_PERMISSIONS: ActionPermissions = {
  canEdit: false,
  canStart: false,
  canSubmit: false,
  canVerify: false,
  canCancel: false,
  readOnlyHint: '',
}

/**
 * 详情页的动作按钮矩阵。
 *
 * <p>`loaded=false` 时全部禁用：详情路由参数切换时旧数据还在屏幕上，不能让它继续可写
 * （与评分单页 `sheetPermissions` 的 loaded 闸门同一教训）。
 */
export function actionPermissions(
  status: CorrectiveActionStatus | null,
  loaded: boolean,
  roles: readonly Role[],
  reverifyPending = false,
): ActionPermissions {
  if (!loaded || !status) {
    return EMPTY_PERMISSIONS
  }
  const manager = isManager(roles)
  const member =
    manager || roles.includes('EVALUATOR')
  if (!member) {
    return { ...EMPTY_PERMISSIONS, readOnlyHint: '没有可执行的整改操作' }
  }

  const editable = status === 'OPEN' || status === 'IN_PROGRESS' || status === 'REOPENED'
  const terminal = status === 'VERIFIED' || status === 'CANCELED'
  const hint =
    reverifyPending
      ? '已重新提交，等待下期定稿后再次验证'
      : status === 'VERIFIED'
        ? '整改已通过，记录仅供查看'
        : status === 'CANCELED'
          ? '整改已取消，记录仅供查看'
          : status === 'WAITING_VERIFICATION' && !manager
            ? '已提交，等待采购经理验证'
            : ''

  return {
    canEdit: editable,
    canStart: status === 'OPEN' || status === 'REOPENED',
    canSubmit: status === 'IN_PROGRESS',
    canVerify: manager && status === 'WAITING_VERIFICATION' && !reverifyPending,
    canCancel: manager && !terminal,
    readOnlyHint: hint,
  }
}

/** 下期结果缺失或待人工判断时，验证意见是必填的（后端同样以 2001 兜底） */
export function verifyCommentRequired(outcome: VerifyOutcome | null): boolean {
  return outcome == null || outcome === 'PENDING'
}

/** 下期对比列/卡片的短文案 */
export function verifySummary(action: CorrectiveActionSummary): string {
  if (action.verifyYear == null) {
    return '待下期定稿'
  }
  const period = `${action.verifyYear} Q${action.verifyQuarter}`
  if (action.verifyOutcome === 'PENDING') {
    return `${period} · 待人工判断`
  }
  if (action.verifyScore == null) {
    return period
  }
  return `${period} · ${action.verifyScore.toFixed(2)}`
}
