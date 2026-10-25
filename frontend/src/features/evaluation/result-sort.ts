import type { TaskResultRow } from '@/shared/types/dto'

/**
 * 结果列表的筛选/排序（01 R2 / 07 §6 场景 9）。
 *
 * <p>在**前端本地**做：`GET /tasks/{id}/results` 的设计就是一次返回任务内全部生效行（02 §4，不分页），
 * 本地处理不增加往返，也不改契约。若将来改分页，筛选/排序参数应下推到同一端点，而不是在这份函数上叠状态。
 *
 * <p>两条确定性约定：
 * 1. **null 永远排在最后**（不随升降序翻转）——总分为 null 的行只可能来自数据异常/未定稿，
 *    把它排在最前会让"看起来分数最高"。
 * 2. 主键相同时按供应商编码（再按 id）升序兜底，保证同分同级的顺序稳定（不依赖 V8 的排序实现）。
 */
export type ResultSortField = 'totalScore' | 'totalGrade' | 'supplier'
export type SortOrder = 'asc' | 'desc'

export interface ResultFilter {
  keyword: string
  sortBy: ResultSortField
  order: SortOrder
}

const GRADE_RANK: Record<string, number> = { A: 4, B: 3, C: 2, D: 1 }

export function filterAndSortResults(
  rows: TaskResultRow[],
  filter: ResultFilter,
): TaskResultRow[] {
  const keyword = filter.keyword.trim().toLowerCase()
  const filtered = keyword
    ? rows.filter(
        (row) =>
          (row.supplierName ?? '').toLowerCase().includes(keyword) ||
          (row.supplierCode ?? '').toLowerCase().includes(keyword),
      )
    : rows

  return [...filtered].sort((left, right) => {
    if (filter.sortBy === 'supplier') {
      const primary = (left.supplierName ?? '').localeCompare(right.supplierName ?? '', 'zh-CN')
      return primary !== 0 ? applyOrder(primary, filter.order) : tieBreak(left, right)
    }
    if (filter.sortBy === 'totalGrade') {
      const leftRank = left.totalGrade ? (GRADE_RANK[left.totalGrade] ?? 0) : null
      const rightRank = right.totalGrade ? (GRADE_RANK[right.totalGrade] ?? 0) : null
      if (leftRank === null || rightRank === null) {
        const compared = nullLast(leftRank, rightRank)
        return compared !== 0 ? compared : tieBreak(left, right)
      }
      const primary = leftRank - rightRank
      return primary !== 0 ? applyOrder(primary, filter.order) : tieBreak(left, right)
    }
    if (left.totalScore === null || right.totalScore === null) {
      const compared = nullLast(left.totalScore, right.totalScore)
      return compared !== 0 ? compared : tieBreak(left, right)
    }
    const primary = left.totalScore - right.totalScore
    return primary !== 0 ? applyOrder(primary, filter.order) : tieBreak(left, right)
  })
}

function applyOrder(comparison: number, order: SortOrder): number {
  return order === 'asc' ? comparison : -comparison
}

/** null 排最后：不随升降序翻转（返回值直接就是"谁在后"）。 */
function nullLast(left: number | string | null, right: number | string | null): number {
  if (left === null && right === null) {
    return 0
  }
  if (left === null) {
    return 1
  }
  if (right === null) {
    return -1
  }
  return 0
}

function tieBreak(left: TaskResultRow, right: TaskResultRow): number {
  const byCode = (left.supplierCode ?? '').localeCompare(right.supplierCode ?? '', 'zh-CN')
  return byCode !== 0 ? byCode : left.taskSupplierId - right.taskSupplierId
}
