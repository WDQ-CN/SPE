/**
 * 权重校验（12 §E3、§3-W4）：编辑器的**实时提示**，与后端的发布校验同口径。
 *
 * <p>⚠️ 必须按「分」比较，**不能直接加浮点数**：`0.1 + 0.2` 在 IEEE 754 下不等于 `0.3`，
 * 合计时就会出现"前端说不能发布、点下去却成功"（码 2011）这类最难排查的前后端不一致——后端用的是
 * `BigDecimal`（精确等于）。12 §E3 的原文就是「合计判定全程不经浮点」，这里是它的前端对应物。
 *
 * <p>⚠️ **别拿 `33.33 + 33.33 + 33.34` 当反例**（我原先在注释、12 §E3 与 `V7` 的列注释里都这么写过）：
 * 它在 JS 里**恰好等于 100**，不是 `100.00000000000001`。这种"听起来合理"的假例子比没有例子更糟——
 * 有人照着它去推断"哪类组合会出错"就会得出错误结论。真正的证据是 `0.1 + 0.2 !== 0.3`
 * （`weight.spec.ts` 里用它，实测成立）。
 *
 * <p>纯函数、不依赖 Vue：这样边界值（99.99 / 100.00 / 100.01、空维度）可以直接单测，不必挂载组件。
 */

import { formatWeight } from '@/shared/format'
import type { ScoreMode } from '@/shared/types/enums'

/** 权重合计的目标值（与后端 `TemplateService.FULL_WEIGHT` 一致） */
export const FULL_WEIGHT = 100

/** 权重换算成「分」（整数），规避浮点误差 */
export function toCents(weight: number): number {
  return Math.round(weight * 100)
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
   * `TARGET` 指标缺目标值或 ≤ 0 的（`维度名/编码`，对应后端 `TEMPLATE_TARGET_INVALID(2013)`）。
   *
   * <p>⚠️ 这条**必须在前端拦**：表单允许目标值留空是对的（12 §E4「保存不校验、发布才校验」），
   * 但发布闸门若不跟着拦，就会出现最坏的一种不一致——**面板说"可以发布"、按钮可点、点下去必被后端 2013 拒**。
   * 而新增指标时 `targetValue` 默认就是 `null`，用户选"目标达成率"后不动它就是最常见路径。
   */
  invalidTargets: string[]
  /**
   * 版本内重复的指标编码（对应后端 `TEMPLATE_INDICATOR_CODE_DUPLICATE(2014)`）。
   *
   * <p>补上这一类才真的"与后端发布校验五类逐条对应"（03 §38 的 P2-18：原先声明五类、实现只有四类）。
   * ⚠️ 判定的**作用域是版本、不是维度**——后端唯一键是 `uk_indicator_version_code(template_version_id, code)`，
   * 跨维度重复同样会被拒；只按维度内查重会漏。
   */
  duplicateCodes: string[]
  /**
   * 能否发布：有维度、维度合计合规、每个维度内合计合规、没有空维度、没有非法目标值、编码不重复。
   *
   * <p>与后端发布校验的五类逐条对应。**不合规只是禁用「发布」，不影响保存**——这正是 12 §E4 的取向：
   * 草稿允许合计 ≠ 100 的中间态，否则用户想把 40 改成 30 时会先被拦住、根本改不动。
   */
  publishable: boolean
}

export function checkTemplateWeights(
  dimensions: {
    name: string
    weight: number
    indicators: {
      weight: number
      /** 可选：不给就不判目标值（边界单测里的权重样本不必都带上这些字段） */
      code?: string
      scoreMode?: ScoreMode | null
      targetValue?: number | null
    }[]
  }[],
): TemplateWeightReport {
  const dimensionCheck = checkWeightSum(dimensions, '维度')
  const emptyDimensions: string[] = []
  const indicatorIssues: { dimensionName: string; check: WeightCheck }[] = []
  const invalidTargets: string[] = []
  const seenCodes = new Set<string>()
  const duplicateCodes: string[] = []

  for (const dimension of dimensions) {
    if (dimension.indicators.length === 0) {
      emptyDimensions.push(dimension.name)
      continue
    }
    const check = checkWeightSum(dimension.indicators, `维度「${dimension.name}」内指标`)
    if (!check.valid) {
      indicatorIssues.push({ dimensionName: dimension.name, check })
    }
    for (const indicator of dimension.indicators) {
      if (indicator.scoreMode === 'TARGET' && (indicator.targetValue ?? 0) <= 0) {
        invalidTargets.push(`${dimension.name}/${indicator.code ?? '未命名指标'}`)
      }
      // 编码查重跨维度进行（后端唯一键的作用域是版本，不是维度）
      if (indicator.code) {
        const code = indicator.code.trim()
        if (code && seenCodes.has(code)) {
          duplicateCodes.push(code)
        } else if (code) {
          seenCodes.add(code)
        }
      }
    }
  }

  return {
    dimensions: dimensionCheck,
    indicatorIssues,
    emptyDimensions,
    invalidTargets,
    duplicateCodes: [...new Set(duplicateCodes)],
    publishable:
      dimensions.length > 0 &&
      dimensionCheck.valid &&
      indicatorIssues.length === 0 &&
      emptyDimensions.length === 0 &&
      invalidTargets.length === 0 &&
      duplicateCodes.length === 0,
  }
}
