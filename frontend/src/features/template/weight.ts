/**
 * 权重校验（12 §E3、§3-W4）：编辑器的**实时提示**，与后端的发布校验同口径。
 *
 * <p>⚠️ 必须按「分」比较，**不能直接加浮点数**：`33.33 + 33.33 + 33.34` 在 IEEE 754 下是
 * `100.00000000000001`，`=== 100` 会把一个完全正确的模板判成不合格——而后端用的是 `BigDecimal`
 * （精确等于），两边口径不一致会表现成「前端说不能发布、点了发布却成功」，是最难排查的一类
 * 前后端不一致。12 §E3 的原文就是「合计判定全程不经浮点」，这里是它的前端对应物。
 *
 * <p>纯函数、不依赖 Vue：这样边界值（99.99 / 100.00 / 100.01、33.33×3）可以直接单测，不必挂载组件。
 */

/** 权重合计的目标值（与后端 `TemplateService.FULL_WEIGHT` 一致） */
export const FULL_WEIGHT = 100

/** 权重换算成「分」（整数），规避浮点误差 */
export function toCents(weight: number): number {
  return Math.round(weight * 100)
}

/** 展示用的两位小数（仅用于文案，不参与判定） */
export function formatWeight(value: number): string {
  return value.toFixed(2)
}

export interface WeightCheck {
  /** 合计（两位小数，供展示） */
  total: number
  /** 是否**精确**等于 100.00 */
  valid: boolean
  /** 面向用户的提示；合规时为空串 */
  message: string
  /** 与 100 的差额（正数表示超出） */
  diff: number
}

/**
 * 一组权重的合计校验。
 *
 * @param items 待合计的权重（维度或指标）
 * @param label 文案前缀，如 `维度`、`维度「质量」内指标`
 */
export function checkWeightSum(items: { weight: number }[], label: string): WeightCheck {
  const cents = items.reduce((sum, item) => sum + toCents(item.weight), 0)
  const diffCents = cents - toCents(FULL_WEIGHT)
  const total = cents / 100

  if (diffCents === 0) {
    return { total, valid: true, message: '', diff: 0 }
  }

  const diff = diffCents / 100
  const prefix = `${label}权重合计 ${formatWeight(total)}，`
  return {
    total,
    valid: false,
    diff,
    message: diff > 0 ? `${prefix}超出 ${formatWeight(diff)}` : `${prefix}还差 ${formatWeight(-diff)}`,
  }
}

export interface TemplateWeightReport {
  /** 模板整体的维度合计 */
  dimensions: WeightCheck
  /** 内部合计不合规的维度（只列有问题的，便于页面直接展示） */
  indicatorIssues: { dimensionName: string; check: WeightCheck }[]
  /** 一个指标都没有的维度名（对应后端发布校验的 `TEMPLATE_DIMENSION_EMPTY(2012)`） */
  emptyDimensions: string[]
  /**
   * 能否发布：有维度、维度合计合规、每个维度内合计合规、且没有空维度。
   *
   * <p>与后端发布校验的五类逐条对应（另一类是 TARGET 缺目标值，属指标字段而非权重，由表单校验管）。
   * **不合规只是禁用「发布」，不影响保存**——这正是 12 §E4 的取向：草稿允许合计 ≠ 100 的中间态，
   * 否则用户想把 40 改成 30 时会先被拦住、根本改不动。
   */
  publishable: boolean
}

export function checkTemplateWeights(
  dimensions: { name: string; weight: number; indicators: { weight: number }[] }[],
): TemplateWeightReport {
  const dimensionCheck = checkWeightSum(dimensions, '维度')
  const emptyDimensions: string[] = []
  const indicatorIssues: { dimensionName: string; check: WeightCheck }[] = []

  for (const dimension of dimensions) {
    if (dimension.indicators.length === 0) {
      emptyDimensions.push(dimension.name)
      continue
    }
    const check = checkWeightSum(dimension.indicators, `维度「${dimension.name}」内指标`)
    if (!check.valid) {
      indicatorIssues.push({ dimensionName: dimension.name, check })
    }
  }

  return {
    dimensions: dimensionCheck,
    indicatorIssues,
    emptyDimensions,
    publishable:
      dimensions.length > 0 &&
      dimensionCheck.valid &&
      indicatorIssues.length === 0 &&
      emptyDimensions.length === 0,
  }
}
