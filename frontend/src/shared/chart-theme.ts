/**
 * ECharts 的 Editorial Ledger 外观（唯一配色来源）：token 与 `app/styles/base.css` 的 CSS 变量同值。
 *
 * <p>图表用 canvas 绘制，拿不到 CSS 变量，所以在这里镜像一份；改主题时两处一起改。
 * 只描述外观，不参与任何数据口径。
 */
export const CHART_COLORS = [
  '#171717',
  '#6E3B3A',
  '#2F5D3A',
  '#7A531C',
  '#3F4A56',
  '#9E2F28',
  '#6B665C',
]

export const GRADE_COLORS: Record<string, string> = {
  A: '#2F5D3A',
  B: '#171717',
  C: '#7A531C',
  D: '#9E2F28',
}

export const CHART_TEXT_STYLE = {
  fontFamily: "'Albert Sans', 'Noto Sans SC', 'Microsoft YaHei', sans-serif",
  color: '#5F5B54',
  fontSize: 11,
}

export const CHART_TOOLTIP = {
  backgroundColor: '#FFFCF6',
  borderColor: '#D8D1C3',
  borderWidth: 1,
  padding: [8, 10] as [number, number],
  textStyle: {
    fontFamily: CHART_TEXT_STYLE.fontFamily,
    color: '#171717',
    fontSize: 12,
  },
  extraCssText: 'box-shadow: 0 12px 32px rgb(23 23 23 / 10%); border-radius: 0;',
}

export const CHART_LEGEND = {
  icon: 'rect',
  itemWidth: 9,
  itemHeight: 9,
  itemGap: 16,
  textStyle: CHART_TEXT_STYLE,
}

export const CHART_ARIA = { enabled: true, decal: { show: false } }

export const CATEGORY_AXIS = {
  axisLine: { lineStyle: { color: '#D8D1C3' } },
  axisTick: { show: false },
  axisLabel: CHART_TEXT_STYLE,
  splitLine: { show: false },
}

export const VALUE_AXIS = {
  axisLine: { show: false },
  axisTick: { show: false },
  axisLabel: CHART_TEXT_STYLE,
  splitLine: { lineStyle: { color: '#EDE8DE' } },
}

export function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true
  )
}
