/**
 * 分值的界面格式（唯一实现点）：null 显示 `—`，**不显示 0**——"还没填"与"得 0 分"是两件事（13 §E6）。
 */
export function formatScore(value: number | null | undefined): string {
  return value == null ? '—' : value.toFixed(2)
}

export function formatGrade(value: string | null | undefined): string {
  return value ?? '—'
}

/** 等级色阶的类名（颜色定义在 base.css 的 `.grade--*`，两处使用同一份配色）。 */
export function gradeClass(value: string | null | undefined): string {
  return value ? `grade--${value.toLowerCase()}` : ''
}

/** 权重展示用（仅文案，不参与判定）；与分值的 null 语义不同，故单独命名。 */
export function formatWeight(value: number): string {
  return value.toFixed(2)
}
