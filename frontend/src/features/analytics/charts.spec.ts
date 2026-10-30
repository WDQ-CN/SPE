import { describe, expect, it } from 'vitest'

import { compareOption, gradeOption, periodLabel, trendOption } from './charts'
import type { AnalyticsCompare, AnalyticsGradeCount, AnalyticsTrend } from '@/shared/types/dto'

const trend: AnalyticsTrend = {
  supplierId: 1,
  supplierCode: 'S-1',
  supplierName: '供应商甲',
  periods: [
    {
      year: 2026,
      quarter: 1,
      totalScore: 80,
      totalGrade: 'B',
      dimensions: [
        { dimensionName: '质量', score: 80, grade: 'B' },
        { dimensionName: '交付', score: 70, grade: 'C' },
      ],
    },
    {
      year: 2026,
      quarter: 2,
      totalScore: 75,
      totalGrade: 'C',
      dimensions: [
        { dimensionName: '质量', score: 75, grade: 'C' },
        { dimensionName: '成本', score: 60, grade: 'D' },
      ],
    },
  ],
}

describe('分析看板 option 纯函数（M5 W6）', () => {
  it('趋势：维度名并集、缺口 null（不把缺口连成线）', () => {
    const option = trendOption(trend) as {
      xAxis: { data: string[] }
      series: { name: string; data: (number | null)[]; connectNulls: boolean }[]
    }
    expect(option.xAxis.data).toEqual([periodLabel(2026, 1), periodLabel(2026, 2)])
    expect(option.series.map((series) => series.name)).toEqual(['总分', '质量', '交付', '成本'])
    expect(option.series[0].data).toEqual([80, 75])
    expect(option.series[2].data).toEqual([70, null])
    expect(option.series[3].data).toEqual([null, 60])
    expect(option.series.every((series) => series.connectNulls === false)).toBe(true)
  })

  it('等级分布：固定 A/B/C/D，缺档补 0', () => {
    const counts: AnalyticsGradeCount[] = [
      { grade: 'B', count: 2 },
      { grade: 'D', count: 1 },
    ]
    const option = gradeOption(counts) as {
      xAxis: { data: string[] }
      series: { data: number[] }[]
    }
    expect(option.xAxis.data).toEqual(['A', 'B', 'C', 'D'])
    expect(option.series[0].data).toEqual([0, 2, 0, 1])
  })

  it('对比：按维度名并集、缺维度 null（柱状不画 0）', () => {
    const compare: AnalyticsCompare = {
      year: 2026,
      quarter: 2,
      rows: [
        {
          supplierId: 1,
          supplierCode: 'S-1',
          supplierName: '甲',
          totalScore: 80,
          totalGrade: 'B',
          dimensions: [
            { dimensionName: '质量', score: 80, grade: 'B' },
            { dimensionName: '交付', score: 70, grade: 'C' },
          ],
        },
        {
          supplierId: 2,
          supplierCode: 'S-2',
          supplierName: '乙',
          totalScore: 75,
          totalGrade: 'C',
          dimensions: [{ dimensionName: '质量', score: 75, grade: 'C' }],
        },
      ],
    }
    const option = compareOption(compare) as {
      xAxis: { data: string[] }
      series: { name: string; data: (number | null)[] }[]
    }
    expect(option.xAxis.data).toEqual(['质量', '交付'])
    expect(option.series[0].name).toBe('S-1 甲')
    expect(option.series[0].data).toEqual([80, 70])
    expect(option.series[1].data).toEqual([75, null])
  })
})
