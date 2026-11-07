import { VERIFY_OUTCOME_LABEL } from '@/shared/labels'
import type { CorrectiveActionDetail } from '@/shared/types/dto'

import { hasPreviousVerification } from './action-status'

/**
 * 整改详情的「状态记录」。
 *
 * <p>只使用详情里**真实存在**的时间：createdAt / submittedAt / verifiedAt。
 * V1004 没有 started_at，也没有取消时间，因此不伪造开始记录；取消原因由页面单独提示。
 */
export type ActionTimelineKey = 'created' | 'submitted' | 'verified'

export interface ActionTimelineEntry {
  key: ActionTimelineKey
  label: string
  /** ISO 本地时间字符串；展示层用 formatUtcLocal，不在纯函数里做时区转换 */
  time: string
  detail: string | null
}

const KEY_RANK: Record<ActionTimelineKey, number> = {
  created: 0,
  submitted: 1,
  verified: 2,
}

function joinDetail(parts: Array<string | null | undefined>): string | null {
  const text = parts.filter((part): part is string => Boolean(part)).join(' · ')
  return text || null
}

export function buildActionTimeline(action: CorrectiveActionDetail): ActionTimelineEntry[] {
  const entries: ActionTimelineEntry[] = [
    {
      key: 'created',
      label: '创建整改',
      time: action.createdAt,
      detail: action.createdByName ? `创建人：${action.createdByName}` : null,
    },
  ]

  if (action.submittedAt) {
    entries.push({
      key: 'submitted',
      label: '提交完成',
      time: action.submittedAt,
      detail: action.submitComment,
    })
  }

  if (action.verifiedAt) {
    const outcomeLabel = action.verifyOutcome
      ? VERIFY_OUTCOME_LABEL[action.verifyOutcome]
      : null
    const prefix = hasPreviousVerification(action) ? '上一轮验证' : '验证'
    entries.push({
      key: 'verified',
      label: outcomeLabel ? `${prefix}：${outcomeLabel}` : prefix,
      time: action.verifiedAt,
      detail: joinDetail([
        action.verifiedByName ? `验证人：${action.verifiedByName}` : null,
        action.verifyComment,
      ]),
    })
  }

  // 重开重新提交后新的 submittedAt 会晚于旧 verifiedAt；按实际时间排序，避免倒挂。
  return entries.sort(
    (left, right) =>
      left.time.localeCompare(right.time) || KEY_RANK[left.key] - KEY_RANK[right.key],
  )
}
