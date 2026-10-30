import type { EChartsCoreOption } from 'echarts/core'

import { GRADE_ORDER } from '@/shared/labels'
import type { AnalyticsCompare, AnalyticsGradeCount, AnalyticsTrend } from '@/shared/types/dto'

/**
 * 分析看板的图表 option 纯函数（M5 W6）：数据形状 → ECharts option，组件只负责生命周期。
 *
 * <p>口径：趋势按**维度名并集**、缺口为 `null`（`connectNulls: false`，不把缺口连成假线段）；
 * 等级分布固定 A/B/C/D（0 也画）；对比按维度名并集、缺维度为 `null`（柱状不画 0）。
 */

export function periodLabel(year: number, quarter: number): string {
  return `${year} Q${quarter}`
}

export function trendOption(trend: AnalyticsTrend): EChartsCoreOption {
  const labels = trend.periods.map((point) => periodLabel(point.year, point.quarter))
  const names = new Set<string>(['总分'])
  for (const point of trend.periods) {
    for (const dimension of point.dimensions) {
      names.add(dimension.dimensionName)
    }
  }
  const series = [...names].map((name) => ({
    name,
    type: 'line' as const,
    connectNulls: false,
    data:
      name === '总分'
        ? trend.periods.map((point) => point.totalScore)
        : trend.periods.map((point) => {
            const found = point.dimensions.find((item) => item.dimensionName === name)
            return found?.score ?? null
          }),
  }))
  return {
    tooltip: { trigger: 'axis' },
    legend: { type: 'scroll', top: 0 },
    grid: { left: 8, right: 8, top: 40, bottom: 8, containLabel: true },
    xAxis: { type: 'category', data: labels },
    yAxis: { type: 'value', min: 0, max: 100 },
    series,
  }
}

export function gradeOption(counts: AnalyticsGradeCount[]): EChartsCoreOption {
  const byGrade = new Map(counts.map((item) => [item.grade, item.count]))
  return {
    tooltip: { trigger: 'item' },
    grid: { left: 8, right: 8, top: 24, bottom: 8, containLabel: true },
    xAxis: { type: 'category', data: [...GRADE_ORDER] },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '供应商数',
        type: 'bar',
        barMaxWidth: 48,
        data: GRADE_ORDER.map((grade) => byGrade.get(grade) ?? 0),
      },
    ],
  }
}

export function compareOption(compare: AnalyticsCompare): EChartsCoreOption {
  const names = new Set<string>()
  for (const row of compare.rows) {
    for (const dimension of row.dimensions) {
      names.add(dimension.dimensionName)
    }
  }
  const dimensions = [...names]
  return {
    tooltip: { trigger: 'axis' },
    legend: { type: 'scroll', top: 0 },
    grid: { left: 8, right: 8, top: 40, bottom: 8, containLabel: true },
    xAxis: { type: 'category', data: dimensions },
    yAxis: { type: 'value', min: 0, max: 100 },
    series: compare.rows.map((row) => ({
      name: `${row.supplierCode} ${row.supplierName}`,
      type: 'bar' as const,
      barMaxWidth: 32,
      data: dimensions.map((name) => {
        const found = row.dimensions.find((item) => item.dimensionName === name)
        return found?.score ?? null
      }),
    })),
  }
}
