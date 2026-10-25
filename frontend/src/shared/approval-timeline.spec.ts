import { describe, expect, it } from 'vitest'

import { formatUtcLocal, groupTimelineByRound } from './approval-timeline'
import type { ApprovalTimelineEntry } from './types/dto'

const entry = (round: number, seq: number, action = 'APPROVE'): ApprovalTimelineEntry => ({
  round,
  seq,
  approverId: seq,
  approverName: `审批人${seq}`,
  action,
  comment: null,
  createdAt: `2026-10-0${round}T0${seq}:00:00`,
})

describe('groupTimelineByRound（审批时间线分组）', () => {
  it('按轮分组，组内按 seq 排序', () => {
    const groups = groupTimelineByRound([entry(2, 2), entry(1, 2), entry(2, 1), entry(1, 1)], 2)
    expect(groups.map((group) => group.round)).toEqual([1, 2])
    expect(groups[0].entries.map((item) => item.seq)).toEqual([1, 2])
    expect(groups[1].entries.map((item) => item.seq)).toEqual([1, 2])
  })

  it('当前轮只标记 task_supplier.round 那一轮（不推 MAX(round)）', () => {
    const groups = groupTimelineByRound([entry(1, 1), entry(2, 1), entry(3, 1)], 2)
    expect(groups.map((group) => group.current)).toEqual([false, true, false])
  })

  it('空时间线返回空数组（页面显示空态，不伪造"第 1 轮"）', () => {
    expect(groupTimelineByRound([], 1)).toEqual([])
  })

  it('当前轮尚无记录且停在审批节点时，补一个当前轮占位（P2-29）', () => {
    const groups = groupTimelineByRound([entry(1, 1)], 2, 1)
    expect(groups.map((group) => group.round)).toEqual([1, 2])
    expect(groups[1]).toEqual({ round: 2, current: true, entries: [] })
  })

  it('评分中（node=0）不伪造当前轮占位', () => {
    expect(groupTimelineByRound([], 1, 0)).toEqual([])
  })
})

describe('formatUtcLocal（UTC 串→本地时间）', () => {
  const pad = (value: number): string => String(value).padStart(2, '0')
  const localOf = (utc: string): string => {
    const parsed = new Date(utc)
    return (
      `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ` +
      `${pad(parsed.getHours())}:${pad(parsed.getMinutes())}:${pad(parsed.getSeconds())}`
    )
  }

  it('无时区后缀的值按 UTC 解析（直接 slice 会早 8 小时）', () => {
    expect(formatUtcLocal('2026-10-24T10:00:00')).toBe(localOf('2026-10-24T10:00:00Z'))
  })

  it('已带时区后缀的值按原样解析', () => {
    expect(formatUtcLocal('2026-10-24T10:00:00Z')).toBe(localOf('2026-10-24T10:00:00Z'))
  })

  it('空值与不可解析值不崩：空串返回空串、坏值原样返回', () => {
    expect(formatUtcLocal('')).toBe('')
    expect(formatUtcLocal('not-a-time')).toBe('not-a-time')
  })
})
