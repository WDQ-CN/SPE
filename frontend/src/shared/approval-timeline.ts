import type { ApprovalTimelineEntry } from '@/shared/types/dto'

/**
 * 审批时间线按轮分组（01 P3 / D3 裁定）。
 *
 * <p>后端刻意返回按 `(round, seq)` 排序的**平铺数组**（02 §4）——分组是展示细节。
 * 抽成纯函数而不是写在组件里：分组的两个判据（轮次排序、当前轮标记来自 `task_supplier.round`
 * 而不是 `MAX(round)`）需要能单独测，且审批页与报告页共用同一份实现。
 */
export interface TimelineRound {
  round: number
  /** 当前轮 = 后端返回的 `task_supplier.round`；历史轮不高亮 */
  current: boolean
  entries: ApprovalTimelineEntry[]
}

/**
 * @param currentNode `task_supplier.current_node`；> 0 表示正停在某一审批节点。
 *     当前轮**还没有记录**（刚驳回、刚重提）时补一个空的当前轮占位（03 §41.3 的 P2-29）——
 *     否则「第 N 轮」的标题整块消失，用户看不出自己处在哪一轮。node=0（评分中）不补。
 */
export function groupTimelineByRound(
  entries: ApprovalTimelineEntry[],
  currentRound: number,
  currentNode = 0,
): TimelineRound[] {
  const sorted = [...entries].sort(
    (left, right) => left.round - right.round || left.seq - right.seq,
  )
  const groups = new Map<number, ApprovalTimelineEntry[]>()
  for (const entry of sorted) {
    const bucket = groups.get(entry.round)
    if (bucket) {
      bucket.push(entry)
    } else {
      groups.set(entry.round, [entry])
    }
  }
  const result = [...groups.entries()].map(([round, roundEntries]) => ({
    round,
    current: round === currentRound,
    entries: roundEntries,
  }))
  if (currentNode > 0 && !groups.has(currentRound)) {
    result.push({ round: currentRound, current: true, entries: [] })
    result.sort((left, right) => left.round - right.round)
  }
  return result
}

/**
 * 后端时间是 **UTC 的 LocalDateTime 串**（库与后端 TZ=UTC，无时区后缀）：
 * 直接 slice 会让中国用户看到早 8 小时的时间。这里按 UTC 解析、输出本地时间
 * （03 §41.3 的 P2-29）。已带时区后缀的值按原样解析。
 */
export function formatUtcLocal(value: string): string {
  if (!value) {
    return ''
  }
  const normalized = /(Z|[+-]\d{2}:?\d{2})$/.test(value) ? value : `${value}Z`
  const parsed = new Date(normalized)
  if (Number.isNaN(parsed.getTime())) {
    return value
  }
  const pad = (part: number): string => String(part).padStart(2, '0')
  return (
    `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ` +
    `${pad(parsed.getHours())}:${pad(parsed.getMinutes())}:${pad(parsed.getSeconds())}`
  )
}
