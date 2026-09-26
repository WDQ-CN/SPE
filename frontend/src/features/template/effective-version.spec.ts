import { describe, expect, it } from 'vitest'

import { interpretWriteResult } from './effective-version'

/**
 * 写响应怎么用（12 §R-5）。
 *
 * <p>为什么这值得单独测：分支判断错了的后果**不是报错**，而是"保存之后随便改点什么都提示已过期"
 * ——用户与开发者都很难把它联想到"三个分支里的一个写错了"。而它又完全是个纯函数，没有理由不测。
 */
describe('写响应的解释', () => {
  it('生效版本与请求版本相同时：原地更新，不需要重新拉树、不打扰用户', () => {
    const outcome = interpretWriteResult(
      { effectiveVersionId: 7, effectiveVersion: 3 },
      7,
      2,
    )

    expect(outcome.copied).toBe(false)
    expect(outcome.effectiveVersionId).toBe(7)
    expect(outcome.message).toBe('')
  })

  it('生效版本不同时：判定为自动复制，提示里带上原版本号与新草稿的说法', () => {
    const outcome = interpretWriteResult(
      { effectiveVersionId: 9, effectiveVersion: 0 },
      7,
      2,
    )

    expect(outcome.copied).toBe(true)
    expect(outcome.effectiveVersionId).toBe(9)
    expect(outcome.message).toContain('v2')
    expect(outcome.message).toContain('草稿')
  })

  it('只看 effectiveVersionId，不看 effectiveVersion —— 复制出来的行 version 是 0，拿它判断会全判成"变了"', () => {
    // 两个用例的唯一区别是 effectiveVersion，结论必须完全一样
    const same = interpretWriteResult({ effectiveVersionId: 7, effectiveVersion: 0 }, 7, 1)
    const other = interpretWriteResult({ effectiveVersionId: 7, effectiveVersion: 99 }, 7, 1)

    expect(same.copied).toBe(false)
    expect(other.copied).toBe(false)
    expect(same).toEqual(other)
  })

  it('判定依据是版本 id 而不是版本号：同一个版本号在不同草稿里是两份数据', () => {
    // v2 的草稿（id=9）与 v2 的已发布版本（id=7）版本号相同但内容无关；
    // 请求 id 是 7、生效 id 是 9，必须判为复制，不能因为 versionNo 都是 2 就当成原地更新
    const outcome = interpretWriteResult({ effectiveVersionId: 9, effectiveVersion: 0 }, 7, 2)

    expect(outcome.copied).toBe(true)
  })
})
