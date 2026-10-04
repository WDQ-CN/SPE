import { describe, expect, it } from 'vitest'

import { checkRawValue, decimalPlaces, inputHint } from './raw-value'

/**
 * 录入值边界的逐条钉死（01 §6.6；13 §E19）。
 *
 * <p>每条都按"刚好在界上 / 差一点越界"两两成对写——只写"100 合法、120 非法"这种远距离取值，
 * 判定写成 `>= 100` 或 `> 101` 都能蒙过去。
 */
describe('checkRawValue', () => {
  it('DIRECT：0 与 100 合法，-0.01 与 100.01 越界', () => {
    expect(checkRawValue('QUANT', 'DIRECT', 0)).toEqual({ ok: true })
    expect(checkRawValue('QUANT', 'DIRECT', 100)).toEqual({ ok: true })
    expect(checkRawValue('QUANT', 'DIRECT', -0.01).ok).toBe(false)
    expect(checkRawValue('QUANT', 'DIRECT', 100.01).ok).toBe(false)
  })

  it('DIRECT：33.34 合法（两位小数），33.335 因三位小数被拒', () => {
    expect(checkRawValue('QUANT', 'DIRECT', 33.34)).toEqual({ ok: true })
    const threeDecimals = checkRawValue('QUANT', 'DIRECT', 33.335)
    expect(threeDecimals.ok).toBe(false)
    expect(threeDecimals.ok === false && threeDecimals.message).toContain('两位小数')
  })

  it('RATIO：录入 120 被拒（超出 0~100 的录入边界；>100 的封顶只留给历史/API 数据）', () => {
    expect(checkRawValue('QUANT', 'RATIO', 98.5)).toEqual({ ok: true })
    expect(checkRawValue('QUANT', 'RATIO', 100)).toEqual({ ok: true })
    expect(checkRawValue('QUANT', 'RATIO', 100.5).ok).toBe(false)
    expect(checkRawValue('QUANT', 'RATIO', 120).ok).toBe(false)
  })

  it('TARGET：0 合法（01 §6.1a：达成率 0），负数被拒', () => {
    expect(checkRawValue('QUANT', 'TARGET', 0)).toEqual({ ok: true })
    expect(checkRawValue('QUANT', 'TARGET', 150).ok).toBe(true)
    expect(checkRawValue('QUANT', 'TARGET', -0.01).ok).toBe(false)
  })

  it('定性：1 与 5 合法，0/6 与非整数被拒，且不设中间值', () => {
    expect(checkRawValue('QUAL', null, 1)).toEqual({ ok: true })
    expect(checkRawValue('QUAL', null, 5)).toEqual({ ok: true })
    expect(checkRawValue('QUAL', null, 0).ok).toBe(false)
    expect(checkRawValue('QUAL', null, 6).ok).toBe(false)
    expect(checkRawValue('QUAL', null, 3.5).ok).toBe(false)
  })

  it('未填（null）不算错误：完整性由提交时的后端校验负责（E6 的不完整口径）', () => {
    expect(checkRawValue('QUANT', 'DIRECT', null)).toEqual({ ok: true })
    expect(checkRawValue('QUAL', null, null)).toEqual({ ok: true })
  })

  it('decimalPlaces 用字符串判断，不受浮点噪声影响', () => {
    expect(decimalPlaces(33.34)).toBe(2)
    expect(decimalPlaces(100)).toBe(0)
    expect(decimalPlaces(0.1)).toBe(1)
  })

  it('inputHint 对每种计分方式都给得出提示（页面不能出现空白提示）', () => {
    expect(inputHint('QUAL', null)).not.toBe('')
    expect(inputHint('QUANT', 'DIRECT')).not.toBe('')
    expect(inputHint('QUANT', 'RATIO')).not.toBe('')
    expect(inputHint('QUANT', 'TARGET')).not.toBe('')
  })
})
