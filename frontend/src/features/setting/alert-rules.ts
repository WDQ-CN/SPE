/** 预警规则的前端即时校验（M5 SM3；判定权仍在后端，越界返回 2034，这里只是体验）。 */

export interface AlertRulesForm {
  enabled: boolean
  totalThreshold: number
  keyDimensionThreshold: number
  trendPeriods: number
  trendDrop: number
}

export function alertRulesError(rules: AlertRulesForm): string | null {
  if (rules.totalThreshold < 0 || rules.totalThreshold > 100) {
    return '总分阈值必须在 0~100 之间'
  }
  if (rules.keyDimensionThreshold < 0 || rules.keyDimensionThreshold > 100) {
    return '关键维度阈值必须在 0~100 之间'
  }
  if (rules.trendPeriods < 1 || rules.trendPeriods > 8) {
    return '连续期数必须在 1~8 之间'
  }
  if (rules.trendDrop < 0 || rules.trendDrop > 100) {
    return '累计降幅必须在 0~100 之间'
  }
  return null
}
