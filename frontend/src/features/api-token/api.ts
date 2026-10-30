import { http } from '@/app/http'
import type { PageResult } from '@/shared/types/result'
import type { PageParams } from '@/shared/types/dto'
import type { ApiTokenCreated, ApiTokenSummary } from '@/shared/types/dto'

/** 开放 API Token 管理（M6 I3，仅 ADMIN）：列表不含明文，生成响应只出现一次。 */

export function listApiTokens(params: PageParams): Promise<PageResult<ApiTokenSummary>> {
  return http.get<PageResult<ApiTokenSummary>>('/api-tokens', params)
}

export function createApiToken(payload: { name: string }): Promise<ApiTokenCreated> {
  return http.post<ApiTokenCreated>('/api-tokens', payload)
}

export function disableApiToken(id: number, version: number): Promise<void> {
  return http.put<void>(`/api-tokens/${id}/disable`, { version })
}
