import { ref, type Ref } from 'vue'

import type { PageResult } from '@/shared/types/result'

/**
 * 分页列表的加载状态（用户页与供应商页共用）。
 *
 * <p>抽出来的理由不是「代码像」而是「规则必须一致」：页码越界怎么处理、失败后列表怎么办、
 * 快速翻页时旧响应会不会覆盖新响应——这些问题在两个页面里各答一遍，答案迟早不同。
 *
 * <p>设计取舍：
 * 1. **页码收敛以后端返回为准**（后端 `PageQuery` 会把越界页码拉回合法范围），前端不重复实现规则；
 * 2. **只接受最后一次请求的结果**：连点翻页会并发多个请求，网络乱序时旧响应可能后到，
 *    不加这道闸门就会出现「点了第 3 页却显示第 2 页数据」；
 * 3. **失败即清空列表**并保留错误信息供页面显示「重试」——留着上一次的数据配上错误提示，
 *    会让人以为看到的是当前页。
 */
export interface PagedList<T> {
  rows: Ref<T[]>
  total: Ref<number>
  page: Ref<number>
  size: Ref<number>
  loading: Ref<boolean>
  /** 加载失败的原因（中文，可直接展示）；空串表示最近一次加载成功 */
  error: Ref<string>
  /** 加载指定页（默认当前页） */
  load: (targetPage?: number) => Promise<void>
  /** 按当前条件重载（新增/编辑/启停之后调用） */
  reload: () => Promise<void>
}

export function usePagedList<T>(
  fetcher: (page: number, size: number) => Promise<PageResult<T>>,
  initialSize = 10,
): PagedList<T> {
  const rows = ref<T[]>([]) as Ref<T[]>
  const total = ref(0)
  const page = ref(1)
  const size = ref(initialSize)
  const loading = ref(false)
  const error = ref('')

  let requestSeq = 0

  async function load(targetPage = page.value): Promise<void> {
    const seq = ++requestSeq
    loading.value = true
    error.value = ''
    try {
      const result = await fetcher(targetPage, size.value)
      if (seq !== requestSeq) {
        return
      }
      rows.value = result.list
      total.value = result.total
      page.value = result.page
      size.value = result.size
    } catch (caught) {
      if (seq !== requestSeq) {
        return
      }
      rows.value = []
      total.value = 0
      error.value = caught instanceof Error ? caught.message : '加载失败'
    } finally {
      if (seq === requestSeq) {
        loading.value = false
      }
    }
  }

  return {
    rows,
    total,
    page,
    size,
    loading,
    error,
    load,
    reload: () => load(page.value),
  }
}
