import { describe, expect, it } from 'vitest'

import { filterAndSortResults, type ResultFilter } from './result-sort'
import type { TaskResultRow } from '@/shared/types/dto'

const row = (
  taskSupplierId: number,
  supplierCode: string,
  supplierName: string,
  totalScore: number | null,
  totalGrade: string | null,
): TaskResultRow => ({
  taskSupplierId,
  supplierId: taskSupplierId,
  supplierCode,
  supplierName,
  totalScore,
  totalGrade,
  dimensions: [],
})

const rows = [
  row(1, 'B-01', 'Beta', 80, 'B'),
  row(2, 'A-02', 'Alpha', 92, 'A'),
  row(3, 'C-03', 'Gamma', null, null),
  row(4, 'A-01', 'Alpha', 80, 'B'),
  row(5, 'D-04', 'Delta', 70, 'D'),
]

const filter = (overrides: Partial<ResultFilter> = {}): ResultFilter => ({
  keyword: '',
  sortBy: 'totalScore',
  order: 'desc',
  ...overrides,
})

describe('filterAndSortResults（结果列表筛选/排序）', () => {
  it('按供应商名称/编码包含匹配（忽略大小写与首尾空白）', () => {
    expect(
      filterAndSortResults(rows, filter({ keyword: '  alpha ' })).map((item) => item.taskSupplierId),
    ).toEqual([2, 4])
    expect(
      filterAndSortResults(rows, filter({ keyword: 'c-03' })).map((item) => item.taskSupplierId),
    ).toEqual([3])
  })

  it('总分降序：null 永远在最后，同分按供应商编码升序', () => {
    expect(filterAndSortResults(rows, filter()).map((item) => item.taskSupplierId)).toEqual([
      2, 4, 1, 5, 3,
    ])
  })

  it('总分升序：null 仍在最后（不随方向翻转）', () => {
    expect(
      filterAndSortResults(rows, filter({ order: 'asc' })).map((item) => item.taskSupplierId),
    ).toEqual([5, 4, 1, 2, 3])
  })

  it('按总等级排序：A→D 降序，null 最后', () => {
    expect(
      filterAndSortResults(rows, filter({ sortBy: 'totalGrade', order: 'desc' })).map(
        (item) => item.taskSupplierId,
      ),
    ).toEqual([2, 4, 1, 5, 3])
  })

  it('按供应商名称排序：同名的两行按编码/id 稳定兜底', () => {
    expect(
      filterAndSortResults(rows, filter({ sortBy: 'supplier', order: 'asc' })).map(
        (item) => item.taskSupplierId,
      ),
    ).toEqual([4, 2, 1, 5, 3])
  })

  it('空列表与无匹配都返回空数组', () => {
    expect(filterAndSortResults([], filter())).toEqual([])
    expect(filterAndSortResults(rows, filter({ keyword: '不存在' }))).toEqual([])
  })

  it('排序键同为 null 时仍按供应商编码兜底（P2-32）', () => {
    const bothNull = [
      row(6, 'Z-01', 'Zeta', null, null),
      row(7, 'A-09', 'Eta', null, null),
    ]
    expect(filterAndSortResults(bothNull, filter()).map((item) => item.taskSupplierId)).toEqual([
      7, 6,
    ])
    expect(
      filterAndSortResults(bothNull, filter({ sortBy: 'totalGrade' })).map(
        (item) => item.taskSupplierId,
      ),
    ).toEqual([7, 6])
  })
})
