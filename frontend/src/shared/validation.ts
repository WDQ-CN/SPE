import type { GradeThresholds } from './types/dto'

/**
 * 等级阈值的顺序检查（01 §6.3）：`0 ≤ D ≤ C ≤ B ≤ A ≤ 100`，**允许相等**（相等表示该档停用）。
 *
 * <p>返回中文提示，`null` 表示合法。**判定权在后端**（不满足返回 `THRESHOLD_INVALID(2005)`）——
 * 这里只为即时反馈，让用户不必等一次往返才知道填反了；两边口径必须一致，所以规则写成一处纯函数，
 * 由 `validation.spec.ts` 钉住边界（含"允许相等"这条容易写反的规则）。
 */
export function thresholdOrderingError(thresholds: GradeThresholds): string | null {
  const values = [thresholds.a, thresholds.b, thresholds.c, thresholds.d]
  if (values.some((value) => typeof value !== 'number' || Number.isNaN(value))) {
    return '请填写完整的四个阈值'
  }
  if (values.some((value) => value < 0 || value > 100)) {
    return '阈值必须在 0 ~ 100 之间'
  }
  // 逐级比较而不是排序后对比：这样能指出具体是哪一对反了
  if (thresholds.d > thresholds.c) {
    return 'D 档不能高于 C 档'
  }
  if (thresholds.c > thresholds.b) {
    return 'C 档不能高于 B 档'
  }
  if (thresholds.b > thresholds.a) {
    return 'B 档不能高于 A 档'
  }
  return null
}
