import { http } from '@/app/http'
import type { PageResult } from '@/shared/types/result'
import type { SupplierStatus } from '@/shared/types/enums'
import type { PageParams, SupplierSummary } from '@/shared/types/dto'

/**
 * 供应商档案接口（02 §4 / 01 S1、S2），**ADMIN 与 MANAGER**。
 *
 * <p>三个搜索条件都是**包含匹配**且可组合（10 §E18）；后端对空条件是「整段跳过」，
 * 但前端仍然只在**有值**时才发送该参数——URL 里出现 `keyword=` 这种空参数没有意义，
 * 也容易让人误以为「空串是一个合法搜索词」。
 */

export interface SupplierQuery extends PageParams {
  keyword?: string
  category?: string
}

/** 新增供应商（后端 `CreateSupplierRequest`）：`category` 可空（10 §E10） */
export interface CreateSupplierPayload {
  code: string
  name: string
  category: string | null
}

/** 编辑供应商（后端 `UpdateSupplierRequest`）：编码可改（10 §E17） */
export interface UpdateSupplierPayload {
  code: string
  name: string
  category: string | null
  version: number
}

export function listSuppliers(query: SupplierQuery): Promise<PageResult<SupplierSummary>> {
  const params: Record<string, unknown> = { page: query.page, size: query.size }
  if (query.keyword) {
    params.keyword = query.keyword
  }
  if (query.category) {
    params.category = query.category
  }
  return http.get<PageResult<SupplierSummary>>('/suppliers', params)
}

export function createSupplier(payload: CreateSupplierPayload): Promise<SupplierSummary> {
  return http.post<SupplierSummary>('/suppliers', payload)
}

export function updateSupplier(
  id: number,
  payload: UpdateSupplierPayload,
): Promise<SupplierSummary> {
  return http.put<SupplierSummary>(`/suppliers/${id}`, payload)
}

/** 启停：只传目标状态，前端状态（守卫 UPDATE，冲突 3001） */
export function changeSupplierStatus(
  id: number,
  status: SupplierStatus,
  version: number,
): Promise<void> {
  return http.put<void>(`/suppliers/${id}/status`, { status, version })
}
