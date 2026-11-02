/**
 * 审计 detail 的展示格式化（`audit_log.detail` 是 JSON 字符串）。
 *
 * <p>操作日志页与整改详情的「操作审计」共用：两个页面各自实现一遍，迟早会在
 * 异常 JSON 的兜底上分叉。
 */
export function prettyAuditDetail(detail: string | null): string {
  if (!detail) {
    return '—'
  }
  try {
    return JSON.stringify(JSON.parse(detail), null, 2)
  } catch {
    return detail
  }
}
