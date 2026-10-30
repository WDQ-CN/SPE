import { describe, expect, it } from 'vitest'

import { alertRulesError, type AlertRulesForm } from './alert-rules'

const base: AlertRulesForm = {
  enabled: true,
  totalThreshold: 70,
  keyDimensionThreshold: 70,
  trendPeriods: 2,
  trendDrop: 5,
}

describe('预警规则前端校验（M5 SM3）', () => {
  it('默认值合法', () => {
    expect(alertRulesError(base)).toBeNull()
  })

  it('每个字段越界都点名', () => {
    expect(alertRulesError({ ...base, totalThreshold: 101 })).toBe('总分阈值必须在 0~100 之间')
    expect(alertRulesError({ ...base, keyDimensionThreshold: -1 })).toBe(
      '关键维度阈值必须在 0~100 之间',
    )
    expect(alertRulesError({ ...base, trendPeriods: 9 })).toBe('连续期数必须在 1~8 之间')
    expect(alertRulesError({ ...base, trendDrop: 101 })).toBe('累计降幅必须在 0~100 之间')
  })

  it('边界值合法（0 与上限）', () => {
    expect(alertRulesError({ ...base, totalThreshold: 0, trendPeriods: 1, trendDrop: 0 })).toBeNull()
    expect(
      alertRulesError({ ...base, totalThreshold: 100, trendPeriods: 8, trendDrop: 100 }),
    ).toBeNull()
  })
})
