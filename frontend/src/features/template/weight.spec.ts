import { describe, expect, it } from 'vitest'

import { checkTemplateWeights, checkWeightSum, formatWeight } from './weight'

/**
 * 权重校验的边界（12 §3-W4 的"单测：权重校验边界（99.99/100.00/100.01、某维度为空）"）。
 *
 * <p>这个文件里**最重要的一条**是 `33.33 + 33.33 + 33.34`：三数相加在 IEEE 754 下是
 * `100.00000000000001`，直接比 `=== 100` 会把一个完全正确的模板判成不合格。后端用 `BigDecimal`
 * 判定「精确等于 100.00」，所以前端若按浮点判，就会出现"前端说不能发布、点下去却成功"这种
 * 最难排查的前后端不一致。它的实现方式是按「分」比较（见 weight.ts 的说明）。
 */
describe('权重合计校验', () => {
  it('合计正好 100.00 时合规，且不产生提示', () => {
    const check = checkWeightSum([{ weight: 40 }, { weight: 30 }, { weight: 20 }, { weight: 10 }], '维度')

    expect(check.valid).toBe(true)
    expect(check.total).toBe(100)
    expect(check.message).toBe('')
    expect(check.diff).toBe(0)
  })

  it('合计 99.99 时不合规，提示"还差 0.01"', () => {
    const check = checkWeightSum([{ weight: 99.99 }], '维度')

    expect(check.valid).toBe(false)
    expect(check.diff).toBeCloseTo(-0.01, 5)
    expect(check.message).toContain('99.99')
    expect(check.message).toContain('还差 0.01')
  })

  it('合计 100.01 时不合规，提示"超出 0.01"（边界两侧都要拦，不能只判小于）', () => {
    const check = checkWeightSum([{ weight: 100.01 }], '维度')

    expect(check.valid).toBe(false)
    expect(check.diff).toBeCloseTo(0.01, 5)
    expect(check.message).toContain('超出 0.01')
  })

  it('33.33 + 33.33 + 33.34 判为合规（等权三分场景）', () => {
    // ⚠️ 这里原本还有一句 `expect(33.33 + 33.33 + 33.34).not.toBe(100)` 作为「前提确认」——
    // 实跑发现**它就是 100**（这三个数的浮点表示恰好相加无误差）。我为了严谨而凭印象编了一个前提，
    // 而凭印象编前提与凭印象写断言是同一个毛病（04-063）。所以那句删掉了，真正能证明
    // 「必须按分比较」的是下面 0.1+0.2 那条 —— 那条的前提是**实测成立**的。
    const check = checkWeightSum([{ weight: 33.33 }, { weight: 33.33 }, { weight: 33.34 }], '维度')

    expect(check.valid).toBe(true)
    expect(check.total).toBe(100)
  })

  it('必须按分比较的真实依据：0.1 + 0.2 在浮点下不等于 0.3，但合计判定仍正确', () => {
    // 这条前提是实测出来的（不是推的）：只有它才说明「直接加浮点数」真的会出错
    expect(0.1 + 0.2).not.toBe(0.3)

    const check = checkWeightSum([{ weight: 0.1 }, { weight: 0.2 }, { weight: 99.7 }], '维度')

    expect(check.valid).toBe(true)
    expect(check.total).toBe(100)
  })

  it('formatWeight 固定两位小数（展示用）', () => {
    expect(formatWeight(100)).toBe('100.00')
    expect(formatWeight(33.335)).toBe('33.34')
  })
})

describe('模板整体权重报告', () => {
  const dimensions = [
    { name: '质量', weight: 40, indicators: [{ weight: 30 }, { weight: 70 }] },
    { name: '交付', weight: 30, indicators: [{ weight: 100 }] },
    { name: '成本', weight: 20, indicators: [{ weight: 100 }] },
    { name: '服务', weight: 10, indicators: [{ weight: 100 }] },
  ]

  it('各层都合规时 publishable 为 true', () => {
    const report = checkTemplateWeights(dimensions)

    expect(report.dimensions.valid).toBe(true)
    expect(report.indicatorIssues).toHaveLength(0)
    expect(report.emptyDimensions).toHaveLength(0)
    expect(report.publishable).toBe(true)
  })

  it('维度合计不为 100 时不可发布，并给出实际值', () => {
    const report = checkTemplateWeights([
      { name: '质量', weight: 40, indicators: [{ weight: 100 }] },
      { name: '交付', weight: 30, indicators: [{ weight: 100 }] },
    ])

    expect(report.dimensions.valid).toBe(false)
    expect(report.dimensions.message).toContain('70.00')
    expect(report.publishable).toBe(false)
  })

  it('某维度内指标合计不为 100 时，单独列出该维度（提示要能定位到哪个维度）', () => {
    const report = checkTemplateWeights([
      { name: '质量', weight: 50, indicators: [{ weight: 30 }, { weight: 30 }] },
      { name: '交付', weight: 50, indicators: [{ weight: 100 }] },
    ])

    expect(report.dimensions.valid).toBe(true)
    expect(report.indicatorIssues).toHaveLength(1)
    expect(report.indicatorIssues[0].dimensionName).toBe('质量')
    expect(report.indicatorIssues[0].check.total).toBe(60)
    expect(report.publishable).toBe(false)
  })

  it('某维度一个指标都没有 → 计入 emptyDimensions（对应后端 2012）', () => {
    const report = checkTemplateWeights([
      { name: '质量', weight: 50, indicators: [] },
      { name: '交付', weight: 50, indicators: [{ weight: 100 }] },
    ])

    expect(report.emptyDimensions).toEqual(['质量'])
    expect(report.publishable).toBe(false)
  })

  it('没有任何维度 → 不可发布（对应后端 2012 的"No dimension"分支）', () => {
    const report = checkTemplateWeights([])

    expect(report.publishable).toBe(false)
    // ⚠️ 空数组的合计是 0，本身就不等于 100，所以 dimensions.valid 也是 false——
    // 但推进到一个维度都没有的场景时，真正的判据是 publishable 而不是那一条
    expect(report.dimensions.valid).toBe(false)
  })

  it('不合规只是禁用发布，不阻止保存——报告里没有任何"禁止保存"的含义', () => {
    const report = checkTemplateWeights([{ name: '质量', weight: 30, indicators: [{ weight: 100 }] }])

    expect(report.publishable).toBe(false)
    // 报告结构里只有 publishable 一个"能不能"开关，页面据此只禁用发布按钮（12 §E4）
    expect(Object.keys(report).sort()).toEqual([
      'dimensions',
      'emptyDimensions',
      'indicatorIssues',
      'publishable',
    ])
  })
})
