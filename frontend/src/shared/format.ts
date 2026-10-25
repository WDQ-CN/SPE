/**
 * 分值的界面格式（唯一实现点）：null 显示 `—`，**不显示 0**——"还没填"与"得 0 分"是两件事（13 §E6）。
 */
export function formatScore(value: number | null | undefined): string {
  return value == null ? '—' : value.toFixed(2)
}

export function formatGrade(value: string | null | undefined): string {
  return value ?? '—'
}
