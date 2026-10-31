import { http } from '@/app/http'
import type { AuditLogView, PageParams } from '@/shared/types/dto'
import type { PageResult } from '@/shared/types/result'

/** 操作日志查询（M5 SM5，02 §4）：仅 ADMIN。 */

export interface AuditLogQuery extends PageParams {
  action?: string
  targetType?: string
  operator?: string
  /** ISO-8601 instant（前端把本地日期边界转换后传；服务端折算 UTC） */
  from?: string
  to?: string
}

export function listAuditLogs(query: AuditLogQuery): Promise<PageResult<AuditLogView>> {
  return http.get<PageResult<AuditLogView>>('/audit-logs', {
    page: query.page,
    size: query.size,
    action: query.action || undefined,
    targetType: query.targetType || undefined,
    operator: query.operator || undefined,
    from: query.from || undefined,
    to: query.to || undefined,
  })
}

/** 动作筛选项：来自数据库 distinct，不在前端写死枚举（22 §2 D8）。 */
export function listAuditActions(): Promise<string[]> {
  return http.get<string[]>('/audit-logs/actions')
}
