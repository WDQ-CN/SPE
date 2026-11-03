import { describe, expect, it } from 'vitest'

import type { AlertView, CorrectiveActionSummary } from '@/shared/types/dto'
import type { CorrectiveActionStatus } from '@/shared/types/enums'

import {
  actionPermissions,
  actionStatusClass,
  alertActionEntry,
  verifyCommentRequired,
  verifySummary,
} from './action-status'

function alert(overrides: Partial<AlertView>): AlertView {
  return {
    id: 1,
    taskSupplierId: 11,
    supplierId: 21,
    supplierCode: 'SUP-001',
    supplierName: '供应商一',
    year: 2026,
    quarter: 1,
    type: 'TOTAL_LOW',
    title: '总分低于阈值',
    content: '内容',
    dimensionId: null,
    baselineScore: 60,
    targetScore: 70,
    status: 'UNREAD',
    correctiveActionId: null,
    correctiveActionStatus: null,
    actionable: true,
    handledBy: null,
    handledByName: null,
    handledComment: null,
    handledAt: null,
    createdAt: '2026-01-01T00:00:00',
    ...overrides,
  }
}

describe('预警行的整改入口', () => {
  it('已有整改：管理者可点详情，VIEWER 只看到状态', () => {
    const row = alert({
      status: 'HANDLED',
      actionable: false,
      correctiveActionId: 8,
      correctiveActionStatus: 'WAITING_VERIFICATION',
    })
    expect(alertActionEntry(row, ['MANAGER'])).toMatchObject({
      state: 'action',
      actionId: 8,
      actionStatus: 'WAITING_VERIFICATION',
      showActionLink: true,
    })
    expect(alertActionEntry(row, ['VIEWER'])).toMatchObject({
      state: 'action',
      showActionLink: false,
    })
  })

  it('可创建：ADMIN/MANAGER 同时拿到创建与处理；EVALUATOR/VIEWER 无动作', () => {
    const row = alert({})
    expect(alertActionEntry(row, ['ADMIN'])).toMatchObject({
      state: 'create',
      canCreate: true,
      canHandle: true,
    })
    expect(alertActionEntry(row, ['MANAGER'])).toMatchObject({
      state: 'create',
      canCreate: true,
      canHandle: true,
    })
    expect(alertActionEntry(row, ['EVALUATOR'])).toMatchObject({
      state: 'none',
      canCreate: false,
      canHandle: false,
    })
    expect(alertActionEntry(row, ['VIEWER'])).toMatchObject({
      state: 'none',
      canCreate: false,
      canHandle: false,
    })
  })

  it('旧预警：只保留处理，并提示旧预警；VIEWER 没有处理按钮', () => {
    const row = alert({ baselineScore: null, targetScore: null, actionable: false })
    expect(alertActionEntry(row, ['MANAGER'])).toMatchObject({
      state: 'legacy',
      canHandle: true,
      legacyHint: true,
    })
    expect(alertActionEntry(row, ['VIEWER'])).toMatchObject({
      state: 'legacy',
      canHandle: false,
      legacyHint: true,
    })
  })

  it('已处理且无整改：保留原处理信息，不再出现动作', () => {
    const row = alert({ status: 'HANDLED', actionable: false })
    expect(alertActionEntry(row, ['ADMIN'])).toMatchObject({
      state: 'handled',
      canCreate: false,
      canHandle: false,
    })
  })
})

describe('整改详情按钮矩阵', () => {
  it('ADMIN 在 OPEN：可编辑/开始/取消，不可提交或验证', () => {
    expect(actionPermissions('OPEN', true, ['ADMIN'])).toMatchObject({
      canEdit: true,
      canStart: true,
      canSubmit: false,
      canVerify: false,
      canCancel: true,
    })
  })

  it('MANAGER 在 IN_PROGRESS：可编辑/提交/取消', () => {
    expect(actionPermissions('IN_PROGRESS', true, ['MANAGER'])).toMatchObject({
      canEdit: true,
      canStart: false,
      canSubmit: true,
      canVerify: false,
      canCancel: true,
    })
  })

  it('EVALUATOR 在 WAITING_VERIFICATION：不能验证，提示等待经理', () => {
    expect(actionPermissions('WAITING_VERIFICATION', true, ['EVALUATOR'])).toEqual({
      canEdit: false,
      canStart: false,
      canSubmit: false,
      canVerify: false,
      canCancel: false,
      readOnlyHint: '已提交，等待采购经理验证',
    })
  })

  it('ADMIN 在 WAITING_VERIFICATION：只出现验证/取消', () => {
    expect(actionPermissions('WAITING_VERIFICATION', true, ['ADMIN'])).toMatchObject({
      canEdit: false,
      canStart: false,
      canSubmit: false,
      canVerify: true,
      canCancel: true,
    })
    expect(actionPermissions('WAITING_VERIFICATION', true, ['ADMIN'], true)).toMatchObject({
      canVerify: false,
      readOnlyHint: '已重新提交，等待下期定稿后再次验证',
    })
  })

  it('终态与未加载：全部禁用', () => {
    expect(actionPermissions('VERIFIED', true, ['ADMIN'])).toMatchObject({
      canEdit: false,
      canStart: false,
      canSubmit: false,
      canVerify: false,
      canCancel: false,
      readOnlyHint: '整改已通过，记录仅供查看',
    })
    expect(actionPermissions('CANCELED', true, ['MANAGER'])).toMatchObject({
      canEdit: false,
      canCancel: false,
      readOnlyHint: '整改已取消，记录仅供查看',
    })
    expect(actionPermissions('OPEN', false, ['ADMIN'])).toEqual({
      canEdit: false,
      canStart: false,
      canSubmit: false,
      canVerify: false,
      canCancel: false,
      readOnlyHint: '',
    })
  })

  it('VIEWER 即使拿到状态也没有任何动作', () => {
    expect(actionPermissions('OPEN', true, ['VIEWER'])).toEqual({
      canEdit: false,
      canStart: false,
      canSubmit: false,
      canVerify: false,
      canCancel: false,
      readOnlyHint: '没有可执行的整改操作',
    })
  })
})

describe('状态标记、验证意见与下期摘要', () => {
  it('状态标记按终态/进行中/取消分色', () => {
    const cases: Array<[CorrectiveActionStatus, string]> = [
      ['OPEN', ''],
      ['IN_PROGRESS', 'is-warning'],
      ['WAITING_VERIFICATION', 'is-warning'],
      ['REOPENED', 'is-warning'],
      ['VERIFIED', 'is-success'],
      ['CANCELED', 'is-off'],
    ]
    for (const [status, expected] of cases) {
      expect(actionStatusClass(status)).toBe(expected)
    }
  })

  it('只有缺失或 PENDING 的验证结论才强制意见', () => {
    expect(verifyCommentRequired(null)).toBe(true)
    expect(verifyCommentRequired('PENDING')).toBe(true)
    expect(verifyCommentRequired('IMPROVED')).toBe(false)
    expect(verifyCommentRequired('NOT_IMPROVED')).toBe(false)
  })

  it('下期摘要区分未回填、待人工判断与已回填', () => {
    const base = {
      verifyYear: null,
      verifyQuarter: null,
      verifyScore: null,
      verifyOutcome: null,
    } as CorrectiveActionSummary
    expect(verifySummary(base)).toBe('待下期定稿')
    expect(
      verifySummary({
        ...base,
        verifyYear: 2026,
        verifyQuarter: 2,
        verifyOutcome: 'PENDING',
      }),
    ).toBe('2026 Q2 · 待人工判断')
    expect(
      verifySummary({
        ...base,
        verifyYear: 2026,
        verifyQuarter: 2,
        verifyScore: 75,
        verifyOutcome: 'IMPROVED',
      }),
    ).toBe('2026 Q2 · 75.00')
  })
})
