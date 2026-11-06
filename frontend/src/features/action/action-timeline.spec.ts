import { describe, expect, it } from 'vitest'

import type { CorrectiveActionDetail } from '@/shared/types/dto'

import { buildActionTimeline } from './action-timeline'

function detail(overrides: Partial<CorrectiveActionDetail>): CorrectiveActionDetail {
  return {
    id: 1,
    alertId: 11,
    alertType: 'TOTAL_LOW',
    alertTitle: '总分低于阈值',
    taskSupplierId: 21,
    supplierId: 31,
    supplierCode: 'SUP-001',
    supplierName: '供应商一',
    dimensionId: null,
    dimensionName: null,
    baselineYear: 2026,
    baselineQuarter: 1,
    baselineScore: 60,
    targetScore: 70,
    ownerId: 41,
    ownerName: '李衡',
    dueDate: '2026-03-31',
    measure: '改进来料检验',
    expectedResult: null,
    status: 'OPEN',
    overdue: false,
    reverifyPending: false,
    submitComment: null,
    submittedAt: null,
    verifyYear: null,
    verifyQuarter: null,
    verifyTaskSupplierId: null,
    verifyScore: null,
    verifyOutcome: null,
    verifyComment: null,
    verifiedBy: null,
    verifiedByName: null,
    verifiedAt: null,
    canceledReason: null,
    createdBy: 51,
    createdByName: '张岚',
    version: 0,
    createdAt: '2026-01-10T09:00:00',
    updatedAt: '2026-01-10T09:00:00',
    auditTrail: [],
    ...overrides,
  }
}

describe('整改状态记录', () => {
  it('只创建时只有一条记录，不伪造开始时间', () => {
    expect(buildActionTimeline(detail({}))).toEqual([
      {
        key: 'created',
        label: '创建整改',
        time: '2026-01-10T09:00:00',
        detail: '创建人：张岚',
      },
    ])
  })

  it('提交后追加提交记录', () => {
    const entries = buildActionTimeline(
      detail({
        status: 'WAITING_VERIFICATION',
        submittedAt: '2026-02-01T10:00:00',
        submitComment: '已完成检验流程调整',
      }),
    )
    expect(entries.map((entry) => entry.key)).toEqual(['created', 'submitted'])
    expect(entries[1]).toEqual({
      key: 'submitted',
      label: '提交完成',
      time: '2026-02-01T10:00:00',
      detail: '已完成检验流程调整',
    })
  })

  it('验证后追加验证结论、验证人与意见', () => {
    const entries = buildActionTimeline(
      detail({
        status: 'VERIFIED',
        submittedAt: '2026-02-01T10:00:00',
        verifiedAt: '2026-04-01T11:00:00',
        verifyOutcome: 'IMPROVED',
        verifyComment: '下期已改善',
        verifiedByName: '张岚',
      }),
    )
    expect(entries.map((entry) => entry.label)).toEqual([
      '创建整改',
      '提交完成',
      '验证：已改善',
    ])
    expect(entries[2].detail).toBe('验证人：张岚 · 下期已改善')
  })

  it('继续整改与待人工判断使用对应标签', () => {
    expect(
      buildActionTimeline(
        detail({
          status: 'REOPENED',
          verifiedAt: '2026-04-01T11:00:00',
          verifyOutcome: 'NOT_IMPROVED',
        }),
      )[1].label,
    ).toBe('验证：未改善')
    expect(
      buildActionTimeline(
        detail({
          status: 'WAITING_VERIFICATION',
          verifiedAt: '2026-04-01T11:00:00',
          verifyOutcome: 'PENDING',
        }),
      )[1].label,
    ).toBe('验证：待人工判断')
  })

  it('取消原因不进入时间线（没有真实的取消时间字段）', () => {
    const entries = buildActionTimeline(
      detail({
        status: 'CANCELED',
        canceledReason: '供应商已退出',
      }),
    )
    expect(entries.map((entry) => entry.key)).toEqual(['created'])
    expect(JSON.stringify(entries)).not.toContain('供应商已退出')
  })

  it('重开重新提交后按实际时间排序，旧验证标为上一轮', () => {
    const entries = buildActionTimeline(
      detail({
        status: 'WAITING_VERIFICATION',
        reverifyPending: true,
        submittedAt: '2026-05-01T10:00:00',
        verifiedAt: '2026-04-01T11:00:00',
        verifyOutcome: 'NOT_IMPROVED',
        verifyComment: '继续整改',
        verifiedByName: '张岚',
      }),
    )

    expect(entries.map((entry) => entry.key)).toEqual(['created', 'verified', 'submitted'])
    expect(entries[1].label).toBe('上一轮验证：未改善')
    expect(entries[1].detail).toBe('验证人：张岚 · 继续整改')
    expect(entries[2].time).toBe('2026-05-01T10:00:00')
  })
})
