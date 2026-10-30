import { http } from '@/app/http'
import type { AlertView } from '@/shared/types/dto'
import type { PageResult } from '@/shared/types/result'

/** 预警列表与处理（M5 W2/W3，02 §4）。 */

export interface AlertQuery {
  page: number
  size: number
  status?: 'UNREAD' | 'HANDLED' | ''
}

export function listAlerts(query: AlertQuery): Promise<PageResult<AlertView>> {
  return http.get<PageResult<AlertView>>('/alerts', {
    page: query.page,
    size: query.size,
    status: query.status || undefined,
  })
}

export function handleAlert(id: number, comment?: string): Promise<AlertView> {
  return http.put<AlertView>(`/alerts/${id}/handle`, { comment: comment || undefined })
}
