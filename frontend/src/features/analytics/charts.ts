import type { EChartsCoreOption } from 'echarts/core'

import {
  CATEGORY_AXIS,
  CHART_ARIA,
  CHART_COLORS,
  CHART_LEGEND,
  CHART_TEXT_STYLE,
  CHART_TOOLTIP,
  GRADE_COLORS,
  VALUE_AXIS,
} from '@/shared/chart-theme'
import { GRADE_ORDER } from '@/shared/labels'
import type { AnalyticsCompare, AnalyticsGradeCount, AnalyticsTrend } from '@/shared/types/dto'

/**
 * 分析看板的图表 option 纯函数（M5 W6）：数据形状 → ECharts option，组件只负责生命周期。
 *
 * <p>口径：趋势按**维度名并集**、缺口为 `null`（`connectNulls: false`，不把缺口连成假线段）；
 * 等级分布固定 A/B/C/D（0 也画）；对比按维度名并集、缺维度为 `null`（柱状不画 0）。
 * 外观（配色/字体/轴）统一来自 `shared/chart-theme.ts`。
 */

export function periodLabel(year: number, quarter: number): string {
  return `${year} Q${quarter}`
}

function axisTooltip(): Record<string, unknown> {
  return {
    ...CHART_TOOLTIP,
    trigger: 'axis',
    axisPointer: { type: 'line', lineStyle: { color: '#6E3B3A', width: 1 } },
  }
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
    symbol: 'circle',
    symbolSize: 4,
    lineStyle: { width: name === '总分' ? 2 : 1.5 },
    data:
      name === '总分'
        ? trend.periods.map((point) => point.totalScore)
        : trend.periods.map((point) => {
            const found = point.dimensions.find((item) => item.dimensionName === name)
            return found?.score ?? null
          }),
  }))
  return {
    aria: CHART_ARIA,
    color: CHART_COLORS,
    textStyle: CHART_TEXT_STYLE,
    tooltip: axisTooltip(),
    legend: { ...CHART_LEGEND, type: 'scroll', top: 0 },
    grid: { left: 8, right: 8, top: 42, bottom: 6, containLabel: true },
    xAxis: { type: 'category', data: labels, ...CATEGORY_AXIS },
    yAxis: { type: 'value', min: 0, max: 100, ...VALUE_AXIS },
    series,
  }
}

export function gradeOption(counts: AnalyticsGradeCount[]): EChartsCoreOption {
  const byGrade = new Map(counts.map((item) => [item.grade, item.count]))
  return {
    aria: CHART_ARIA,
    color: CHART_COLORS,
    textStyle: CHART_TEXT_STYLE,
    tooltip: { ...axisTooltip(), axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 8, top: 20, bottom: 6, containLabel: true },
    xAxis: { type: 'category', data: [...GRADE_ORDER], ...CATEGORY_AXIS },
    yAxis: { type: 'value', minInterval: 1, ...VALUE_AXIS },
    series: [
      {
        name: '供应商数',
        type: 'bar',
        barMaxWidth: 36,
        data: GRADE_ORDER.map((grade) => byGrade.get(grade) ?? 0),
        itemStyle: {
          color: (params: { dataIndex: number }) => GRADE_COLORS[GRADE_ORDER[params.dataIndex]],
        },
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
    aria: CHART_ARIA,
    color: CHART_COLORS,
    textStyle: CHART_TEXT_STYLE,
    tooltip: axisTooltip(),
    legend: { ...CHART_LEGEND, type: 'scroll', top: 0 },
    grid: { left: 8, right: 8, top: 42, bottom: 6, containLabel: true },
    xAxis: { type: 'category', data: dimensions, ...CATEGORY_AXIS },
    yAxis: { type: 'value', min: 0, max: 100, ...VALUE_AXIS },
    series: compare.rows.map((row) => ({
      name: `${row.supplierCode} ${row.supplierName}`,
      type: 'bar' as const,
      barMaxWidth: 24,
      data: dimensions.map((name) => {
        const found = row.dimensions.find((item) => item.dimensionName === name)
        return found?.score ?? null
      }),
    })),
  }
}
