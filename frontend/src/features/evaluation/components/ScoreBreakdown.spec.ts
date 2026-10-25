import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'

import ScoreBreakdown from './ScoreBreakdown.vue'
import type { ReportDimension } from '@/shared/types/dto'

const dimension = (overrides: Partial<ReportDimension> = {}): ReportDimension => ({
  dimensionId: 1,
  dimensionName: '质量',
  weight: 40,
  score: 80,
  grade: 'B',
  complete: true,
  indicators: [
    {
      indicatorId: 11,
      code: 'IQC_PASS_RATE',
      name: '来料合格率',
      weight: 50,
      type: 'QUANT',
      scoreMode: 'RATIO',
      rawValue: 80,
      scoreValue: 80,
      unit: '%',
      source: 'MANUAL',
    },
  ],
  ...overrides,
})

const mountBreakdown = (dimensions: ReportDimension[]) =>
  mount(ScoreBreakdown, {
    props: { dimensions },
    global: { plugins: [ElementPlus] },
  })

describe('ScoreBreakdown（维度与指标明细）', () => {
  it('维度标题带权重、得分与等级', () => {
    const wrapper = mountBreakdown([dimension()])
    const title = wrapper.get('.breakdown__title').text()
    expect(title).toContain('质量')
    expect(title).toContain('40.00')
    expect(title).toContain('80.00')
    expect(title).toContain('B')
  })

  it('未完成维度打标记；null 分数显示破折号而不是 0', () => {
    const wrapper = mountBreakdown([
      dimension({
        score: null,
        grade: null,
        complete: false,
        indicators: [
          {
            indicatorId: 12,
            code: 'X',
            name: '未填指标',
            weight: 100,
            type: 'QUANT',
            scoreMode: 'DIRECT',
            rawValue: null,
            scoreValue: null,
            unit: null,
            source: null,
          },
        ],
      }),
    ])
    expect(wrapper.text()).toContain('未完成')
    const title = wrapper.get('.breakdown__title').text()
    expect(title).toContain('得分 — · —')
    expect(title).not.toContain('得分 0.00')
  })

  it('没有维度时显示空态', () => {
    const wrapper = mountBreakdown([])
    expect(wrapper.text()).toContain('暂无维度数据')
  })
})
