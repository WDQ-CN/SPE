import type { IndicatorType, ScoreMode } from '@/shared/types/enums'

/**
 * 录入值的**前端预校验**（01 §6.6 的边界表；13 §E19 点名要抽出来的两个纯函数之一）。
 *
 * <p>为什么必须抽出来单测：四种计分方式各一套边界，而这四种判定**错了不会报错**——要么把合法的输入
 * 拦下来（用户以为系统坏了），要么放过越界值让后端用 2025 拒（错误码提示在表单外，用户一脸茫然）。
 * 抽成纯函数就能逐条钉住边界（含"刚好在界上"与"差一点越界"两类）。
 *
 * <p>⚠️ **这里不含换算**：换算（比率/目标达成率/定性 ×20）只存在于后端 ` ScoreCalculator`
 * （01 E2 / ADR-4）——前端只判断"这个数能不能提交"，算分一律以后端试算接口为准。
 */

/** 校验结果：合法，或带上可直接显示的中文原因。 */
export type RawValueCheck = { ok: true } | { ok: false; message: string }

const MAX_DECIMALS = 2

/** 小数位数（用字符串判断，避免 `0.1+0.2` 那类浮点噪声影响判定）。 */
export function decimalPlaces(value: number): number {
  const text = String(value)
  const dot = text.indexOf('.')
  return dot < 0 ? 0 : text.length - dot - 1
}

/**
 * 校验一个录入值。
 *
 * @param type 指标类型（定性/定量）
 * @param scoreMode 计分方式；定性指标为 null（12 §E2）
 * @param value 用户输入；`null`/空串由调用方先转成 null（表示"未填"，不是错误）
 */
export function checkRawValue(
  type: IndicatorType,
  scoreMode: ScoreMode | null,
  value: number | null,
): RawValueCheck {
  if (value === null || Number.isNaN(value)) {
    return { ok: true } // 未填不是"非法"，完整性由提交时的后端校验与页面提示负责（E6）
  }
  if (type === 'QUAL') {
    if (!Number.isInteger(value)) {
      return { ok: false, message: '定性指标只能填 1~5 的整数' }
    }
    if (value < 1 || value > 5) {
      return { ok: false, message: '定性指标只能填 1~5 的整数' }
    }
    return { ok: true }
  }
  if (decimalPlaces(value) > MAX_DECIMALS) {
    return { ok: false, message: '最多两位小数' }
  }
  switch (scoreMode) {
    case 'DIRECT':
    case 'RATIO':
      if (value < 0 || value > 100) {
        return { ok: false, message: '取值必须在 0~100 之间' }
      }
      return { ok: true }
    case 'TARGET':
      if (value < 0) {
        return { ok: false, message: '实际值不能为负数' }
      }
      return { ok: true }
    default:
      // 定量指标缺 scoreMode 属模板数据问题（发布校验会拦）；这里不拦，交给后端 2025
      return { ok: true }
  }
}

/** 输入框旁的提示文案（01 §6.6 的"录入"列）。 */
export function inputHint(type: IndicatorType, scoreMode: ScoreMode | null): string {
  if (type === 'QUAL') {
    return '1~5 的整数（5 为优）'
  }
  switch (scoreMode) {
    case 'DIRECT':
      return '0~100，最多两位小数'
    case 'RATIO':
      return '百分比数值 0~100，最多两位小数'
    case 'TARGET':
      return '实际值（≥0，最多两位小数），系统按目标值换算'
    default:
      return ''
  }
}
