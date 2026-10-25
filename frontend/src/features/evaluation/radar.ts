/**
 * 雷达图的纯数据/配置构造（D4 裁决：ECharts 按需引入，只画维度雷达）。
 *
 * <p>⚠️ `tooltip.renderMode` **必须是 `richText`**：ECharts 默认把 tooltip 渲染成 HTML，
 * 维度名来自模板（可被编辑），会进 `innerHTML`——CSP 的 `script-src 'self'` 挡不住 DOM 注入。
 * `richText` 走 canvas 文本，没有这条注入面（2026-10-04 评审发现，15 §6）。
 *
 * <p>只画**完整**的维度：未完成的维度分是 null（13 §E6 的不完整口径），
 * 把它画成 0 会让"还没填"看起来像"考得很差"。
 */
export interface RadarDimension {
  dimensionName: string
  score: number | null
  complete: boolean
}
export interface RadarPoint {
  name: string
  score: number
}

export interface RadarOption {
  tooltip: {
    trigger: 'item'
    renderMode: 'richText'
    formatter: (params: { name?: string; value?: number[] }) => string
  }
  radar: {
    indicator: { name: string; max: number }[]
    radius: string
    splitNumber: number
  }
  series: {
    type: 'radar'
    data: { value: number[]; name: string }[]
    areaStyle: { opacity: number }
  }[]
}

export function radarPoints(dimensions: RadarDimension[]): RadarPoint[] {
  return dimensions
    .filter((dimension) => dimension.complete && dimension.score != null)
    .map((dimension) => ({ name: dimension.dimensionName, score: dimension.score as number }))
}

export function buildRadarOption(dimensions: RadarDimension[]): RadarOption {
  const points = radarPoints(dimensions)
  return {
    tooltip: {
      trigger: 'item',
      // 见文件头的注入面说明：不能改成 'html'
      renderMode: 'richText',
      formatter: (params) => {
        const values = params.value ?? []
        return points
          .map((point, index) => `${point.name}：${values[index]?.toFixed(2) ?? '—'}`)
          .join('\n')
      },
    },
    radar: {
      indicator: points.map((point) => ({ name: point.name, max: 100 })),
      radius: '65%',
      splitNumber: 4,
    },
    series: [
      {
        type: 'radar',
        data: [{ value: points.map((point) => point.score), name: '维度得分' }],
        areaStyle: { opacity: 0.2 },
      },
    ],
  }
}
