import { describe, expect, it } from 'vitest'

import { thresholdOrderingError } from './validation'

/**
 * 等级阈值的顺序规则（01 §6.3）：`0 ≤ D ≤ C ≤ B ≤ A ≤ 100`，**允许相等**（相等表示该档停用）。
 *
 * <p>「允许相等」这条最容易写反——写成严格递增会让用户无法停用某一档，
 * 而它正是 E16 里被明确记下的一个易错点。用例把边界钉住。
 */
describe('thresholdOrderingError', () => {
  it('默认阈值（90/80/70/0）合法', () => {
    expect(thresholdOrderingError({ a: 90, b: 80, c: 70, d: 0 })).toBeNull()
  })

  it('允许相邻两档相等（相等 = 停用该档）', () => {
    expect(thresholdOrderingError({ a: 90, b: 90, c: 90, d: 90 })).toBeNull()
    expect(thresholdOrderingError({ a: 90, b: 80, c: 70, d: 70 })).toBeNull()
  })

  it('四档全为 0 或全为 100 都合法（边界值）', () => {
    expect(thresholdOrderingError({ a: 0, b: 0, c: 0, d: 0 })).toBeNull()
    expect(thresholdOrderingError({ a: 100, b: 100, c: 100, d: 100 })).toBeNull()
  })

  it('逆序会指出具体是哪一对反了', () => {
    expect(thresholdOrderingError({ a: 80, b: 90, c: 70, d: 0 })).toBe('B 档不能高于 A 档')
    expect(thresholdOrderingError({ a: 90, b: 70, c: 80, d: 0 })).toBe('C 档不能高于 B 档')
    expect(thresholdOrderingError({ a: 90, b: 80, c: 60, d: 70 })).toBe('D 档不能高于 C 档')
  })

  it('越界（<0 或 >100）被拒', () => {
    expect(thresholdOrderingError({ a: 101, b: 80, c: 70, d: 0 })).toBe('阈值必须在 0 ~ 100 之间')
    expect(thresholdOrderingError({ a: 90, b: 80, c: 70, d: -1 })).toBe('阈值必须在 0 ~ 100 之间')
  })

  it('缺失或非数字被拒（输入框清空时会得到 NaN）', () => {
    expect(thresholdOrderingError({ a: Number.NaN, b: 80, c: 70, d: 0 })).toBe(
      '请填写完整的四个阈值',
    )
    expect(thresholdOrderingError({ a: 90, b: 80, c: 70, d: undefined as unknown as number })).toBe(
      '请填写完整的四个阈值',
    )
  })
})
