import { describe, expect, it } from 'vitest'

import { buildRadarOption, radarPoints, type RadarDimension } from './radar'

const dimension = (
  name: string,
  score: number | null,
  complete = true,
): RadarDimension => ({
  dimensionName: name,
  score,
  complete,
})

describe('buildRadarOption（报告页雷达图）', () => {
  it('只取完整维度的分数，未完成不画成 0', () => {
    const points = radarPoints([
      dimension('质量', 80),
      dimension('交付', null, false),
      dimension('成本', 60),
    ])
    expect(points.map((point) => point.name)).toEqual(['质量', '成本'])
  })

  it('tooltip 必须是 richText（维度名不进 HTML，CSP 挡不住 DOM 注入）', () => {
    const option = buildRadarOption([dimension('质量', 80)])
    expect(option.tooltip.renderMode).toBe('richText')
  })

  it('雷达轴与数据同序，formatter 输出维度名与两位小数', () => {
    const option = buildRadarOption([dimension('质量', 80), dimension('成本', 60)])
    expect(option.radar.indicator.map((item) => item.name)).toEqual(['质量', '成本'])
    expect(option.series[0].data[0].value).toEqual([80, 60])
    expect(option.tooltip.formatter({ value: [80, 60] })).toContain('质量：80.00')
    expect(option.tooltip.formatter({ value: [80, 60] })).toContain('成本：60.00')
  })
})
