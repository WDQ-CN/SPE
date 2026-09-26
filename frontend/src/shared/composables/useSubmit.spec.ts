import { describe, expect, it, vi } from 'vitest'

import { BizError } from '@/shared/error-code'
import { useSubmit } from './useSubmit'

/**
 * 提交外壳的两条规则：防重复、只回显「需要页面显示」的错误。
 * 后者容易写错——把 4xxx/5xxx 也塞进对话框，用户会先看到全局提示、再看到同样的红字。
 */
describe('useSubmit', () => {
  it('执行中再次调用被忽略（手快连点两次会造出两条数据）', async () => {
    let release: () => void = () => {}
    const action = vi.fn().mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          release = resolve
        }),
    )
    const submit = useSubmit()

    const first = submit.run(action)
    const second = await submit.run(action)

    expect(second).toBe(false)
    expect(action).toHaveBeenCalledTimes(1)

    release()
    await expect(first).resolves.toBe(true)
  })

  it('2xxx（参数/校验类）回显到表单：拦截器刻意不弹提示', async () => {
    const submit = useSubmit()

    const ok = await submit.run(() => Promise.reject(new BizError(2002, '账号已存在')))

    expect(ok).toBe(false)
    expect(submit.errorMessage.value).toBe('账号已存在')
  })

  it('登录页的 1002/1003 同样回显（它们只在表单里有意义）', async () => {
    const submit = useSubmit()

    await submit.run(() => Promise.reject(new BizError(1003, '尝试过于频繁，请稍后再试')))

    expect(submit.errorMessage.value).toBe('尝试过于频繁，请稍后再试')
  })

  it('3xxx/4xxx/5xxx 不回显：拦截器已经提示过，再显示一遍是重复打扰', async () => {
    for (const code of [3001, 4001, 5000]) {
      const submit = useSubmit()
      await submit.run(() => Promise.reject(new BizError(code, '后端说明')))
      expect(submit.errorMessage.value, `码 ${code}`).toBe('')
    }
  })

  it('传输层错误不回显（同样已由拦截器提示）', async () => {
    const submit = useSubmit()

    await submit.run(() => Promise.reject(new Error('无法连接后端服务')))

    expect(submit.errorMessage.value).toBe('')
  })

  it('下一次提交会清掉上一次的回显', async () => {
    const submit = useSubmit()

    await submit.run(() => Promise.reject(new BizError(2002, '第一次的错误')))
    expect(submit.errorMessage.value).toBe('第一次的错误')

    await submit.run(() => Promise.resolve())
    expect(submit.errorMessage.value).toBe('')
  })
})
