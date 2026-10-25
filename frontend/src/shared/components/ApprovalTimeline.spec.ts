import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'

import ApprovalTimeline from './ApprovalTimeline.vue'
import type { ApprovalTimelineEntry } from '@/shared/types/dto'

const entry = (round: number, seq: number, action = 'APPROVE'): ApprovalTimelineEntry => ({
  round,
  seq,
  approverId: seq,
  approverName: `审批人${seq}`,
  action,
  comment: action === 'REJECT' ? '请重填' : null,
  createdAt: '2026-10-24T10:00:00',
})

const mountTimeline = (
  entries: ApprovalTimelineEntry[],
  currentRound: number,
  currentNode = 0,
) =>
  mount(ApprovalTimeline, {
    props: { entries, currentRound, currentNode },
    global: { plugins: [ElementPlus] },
  })

describe('ApprovalTimeline（审批时间线）', () => {
  it('按轮分块，当前轮打「当前轮」标记', () => {
    const wrapper = mountTimeline([entry(1, 1), entry(2, 1)], 2)
    expect(wrapper.findAll('.approval-timeline__round')).toHaveLength(2)
    expect(wrapper.get('.approval-timeline__round--current').text()).toContain('第 2 轮')
    expect(wrapper.get('.approval-timeline__round--current').text()).toContain('当前轮')
  })

  it('通过/驳回动作显示中文，驳回意见可见', () => {
    const wrapper = mountTimeline([entry(1, 1), entry(1, 2, 'REJECT')], 1)
    expect(wrapper.text()).toContain('通过')
    expect(wrapper.text()).toContain('驳回')
    expect(wrapper.text()).toContain('请重填')
  })

  it('没有记录时显示空态（不伪造轮次）', () => {
    const wrapper = mountTimeline([], 1)
    expect(wrapper.text()).toContain('暂无审批记录')
    expect(wrapper.findAll('.approval-timeline__round')).toHaveLength(0)
  })

  it('当前轮尚无记录时显示占位块（P2-29）', () => {
    const wrapper = mountTimeline([entry(1, 1)], 2, 1)
    expect(wrapper.findAll('.approval-timeline__round')).toHaveLength(2)
    expect(wrapper.get('.approval-timeline__round--current').text()).toContain('第 2 轮')
    expect(wrapper.get('.approval-timeline__round--current').text()).toContain('本轮尚无审批记录')
  })
})
