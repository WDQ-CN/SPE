import { http } from '@/app/http'
import type {
  CorrectiveActionDetail,
  CorrectiveActionSummary,
  PageParams,
} from '@/shared/types/dto'
import type { CorrectiveActionStatus } from '@/shared/types/enums'
import type { PageResult } from '@/shared/types/result'

/**
 * 整改任务接口（02 §4 / 29 §3.5）。
 *
 * <p>本文件是整改 feature 的唯一 HTTP 出口。所有写接口都返回**最新详情**：调用方下一步的
 * `version` 必须取自响应，不能自己推算（守卫 UPDATE 冲突 → 3001）。
 */

export interface CorrectiveActionQuery extends PageParams {
  status?: CorrectiveActionStatus | ''
  /** true = 仅看超期；false 当前 UI 不发送，后端仍支持 */
  overdue?: boolean
  /** 仅 ADMIN/MANAGER 使用；EVALUATOR 由后端强制本人 */
  supplierId?: number
  ownerId?: number
}

/** 从预警创建整改（`POST /api/alerts/{id}/corrective-actions`） */
export interface CreateCorrectiveActionPayload {
  ownerId: number
  /** yyyy-MM-dd；本地日期字符串，不做时区转换 */
  dueDate: string
  measure: string
  expectedResult?: string
}

/** 编辑整改：负责人与截止日期也可改；EVALUATOR 必须原样回传 ownerId */
export interface UpdateCorrectiveActionPayload extends CreateCorrectiveActionPayload {
  version: number
}

export function listCorrectiveActions(
  query: CorrectiveActionQuery,
): Promise<PageResult<CorrectiveActionSummary>> {
  return http.get<PageResult<CorrectiveActionSummary>>('/corrective-actions', {
    page: query.page,
    size: query.size,
    status: query.status || undefined,
    overdue: query.overdue,
    supplierId: query.supplierId,
    ownerId: query.ownerId,
  })
}

export function getCorrectiveAction(id: number): Promise<CorrectiveActionDetail> {
  return http.get<CorrectiveActionDetail>(`/corrective-actions/${id}`)
}

/** 创建入口挂在预警下；2038/2040/2041 为表单级错误 */
export function createCorrectiveAction(
  alertId: number,
  payload: CreateCorrectiveActionPayload,
): Promise<CorrectiveActionDetail> {
  return http.post<CorrectiveActionDetail>(
    `/alerts/${alertId}/corrective-actions`,
    payload,
  )
}

export function updateCorrectiveAction(
  id: number,
  payload: UpdateCorrectiveActionPayload,
): Promise<CorrectiveActionDetail> {
  return http.put<CorrectiveActionDetail>(`/corrective-actions/${id}`, payload)
}

export function startCorrectiveAction(
  id: number,
  version: number,
): Promise<CorrectiveActionDetail> {
  return http.post<CorrectiveActionDetail>(`/corrective-actions/${id}/start`, { version })
}

export function submitCorrectiveAction(
  id: number,
  version: number,
  comment?: string,
): Promise<CorrectiveActionDetail> {
  return http.post<CorrectiveActionDetail>(`/corrective-actions/${id}/submit`, {
    version,
    comment: comment || undefined,
  })
}

export function verifyCorrectiveAction(
  id: number,
  version: number,
  pass: boolean,
  comment?: string,
): Promise<CorrectiveActionDetail> {
  return http.post<CorrectiveActionDetail>(`/corrective-actions/${id}/verify`, {
    version,
    pass,
    comment: comment || undefined,
  })
}

export function cancelCorrectiveAction(
  id: number,
  version: number,
  reason: string,
): Promise<CorrectiveActionDetail> {
  return http.post<CorrectiveActionDetail>(`/corrective-actions/${id}/cancel`, {
    version,
    reason,
  })
}
