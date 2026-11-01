/**
 * ECharts 的 Editorial Ledger 外观（唯一配色来源）：token 与 `app/styles/base.css` 的 CSS 变量同值。
 *
 * <p>图表用 canvas 绘制，拿不到 CSS 变量，所以在这里镜像一份；改主题时两处一起改。
 * 只描述外观，不参与任何数据口径。
 */
export const CHART_COLORS = [
  '#1C1C1C',
  '#816D70',
  '#3F6148',
  '#8A6A2F',
  '#4B5563',
  '#A33A32',
  '#8A857C',
]

export const GRADE_COLORS: Record<string, string> = {
  A: '#3F6148',
  B: '#1C1C1C',
  C: '#8A6A2F',
  D: '#A33A32',
}

export const CHART_TEXT_STYLE = {
  fontFamily: "'Albert Sans', 'Noto Sans SC', 'Microsoft YaHei', sans-serif",
  color: '#6B6A65',
  fontSize: 10,
}

export const CHART_TOOLTIP = {
  backgroundColor: '#FFFDF9',
  borderColor: '#DED8CC',
  borderWidth: 1,
  padding: [8, 10] as [number, number],
  textStyle: {
    fontFamily: CHART_TEXT_STYLE.fontFamily,
    color: '#1C1C1C',
    fontSize: 11,
  },
  extraCssText: 'box-shadow: 0 12px 32px rgb(28 28 28 / 10%); border-radius: 0;',
}

export const CHART_LEGEND = {
  icon: 'rect',
  itemWidth: 8,
  itemHeight: 8,
  itemGap: 16,
  textStyle: CHART_TEXT_STYLE,
}

export const CATEGORY_AXIS = {
  axisLine: { lineStyle: { color: '#DED8CC' } },
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
