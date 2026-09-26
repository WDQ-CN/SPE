import { describe, expect, it, vi } from 'vitest'

import { usePagedList } from './usePagedList'
import type { PageResult } from '@/shared/types/result'

function pageOf<T>(list: T[], total: number, page: number, size: number): PageResult<T> {
  return { list, total, page, size }
}

/**
 * 分页列表的加载语义。抽这个 composable 的理由不是「代码像」，而是这三条规则必须在
 * 两个列表页里表现一致：页码以后端为准、失败不留旧数据、并发只认最后一次。
 */
describe('usePagedList', () => {
  it('加载成功后写入数据，并以**后端返回的**页码与页大小为准', async () => {
    // 传 99 页，后端把它收敛到第 1 页——前端不该自己算，否则和 PageQuery 的规则会有两套
    const fetcher = vi.fn().mockResolvedValue(pageOf([{ id: 1 }], 1, 1, 10))
    const list = usePagedList(fetcher)

    await list.load(99)

    expect(fetcher).toHaveBeenCalledWith(99, 10)
    expect(list.rows.value).toEqual([{ id: 1 }])
    expect(list.total.value).toBe(1)
    expect(list.page.value).toBe(1)
  })

  it('加载失败时清空列表并留下可展示的错误（留着上一次数据会让人以为那就是当前页）', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(pageOf([{ id: 1 }], 5, 1, 10))
      .mockRejectedValueOnce(new Error('无法连接后端服务'))
    const list = usePagedList(fetcher)

    await list.load()
    expect(list.rows.value).toHaveLength(1)

    await list.load()

    expect(list.rows.value).toEqual([])
    expect(list.total.value).toBe(0)
    expect(list.error.value).toBe('无法连接后端服务')
    expect(list.loading.value).toBe(false)
  })

  it('并发加载只接受最后一次请求的结果（网络乱序时旧响应可能后到）', async () => {
    let releaseSlow: (value: PageResult<{ id: number }>) => void = () => {}
    const slow = new Promise<PageResult<{ id: number }>>((resolve) => {
      releaseSlow = resolve
    })
    const fetcher = vi
      .fn()
      .mockImplementationOnce(() => slow)
      .mockResolvedValueOnce(pageOf([{ id: 2 }], 20, 2, 10))
    const list = usePagedList<{ id: number }>(fetcher)

    const first = list.load(1)
    const second = list.load(2)
    await second
    releaseSlow(pageOf([{ id: 1 }], 20, 1, 10))
    await first

    expect(list.rows.value).toEqual([{ id: 2 }])
    expect(list.page.value).toBe(2)
  })

  it('reload 用当前页码重载（新增/编辑/启停之后调用）', async () => {
    const fetcher = vi.fn().mockResolvedValue(pageOf([], 0, 3, 20))
    const list = usePagedList(fetcher, 20)

    await list.load(3)
    await list.reload()

    expect(fetcher).toHaveBeenLastCalledWith(3, 20)
  })

  it('加载中标志在成功与失败后都会归位', async () => {
    const ok = usePagedList(vi.fn().mockResolvedValue(pageOf([], 0, 1, 10)))
    await ok.load()
    expect(ok.loading.value).toBe(false)

    const failed = usePagedList(vi.fn().mockRejectedValue(new Error('超时')))
    await failed.load()
    expect(failed.loading.value).toBe(false)
  })
})
