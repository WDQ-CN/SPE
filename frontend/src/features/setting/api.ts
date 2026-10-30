import { http } from '@/app/http'
import type { GradeThresholds } from '@/shared/types/dto'

/**
 * 系统设置接口（02 §4 / 01 SM2），**仅 ADMIN**。
 *
 * <p>按键分路径（10 §E14）：本轮只有等级阈值一项，将来预警规则、默认审批链各自成路径，
 * 于是每个设置项都有自己的强类型出入参，不会把一批异构配置塞进同一个 `Map`。
 *
 * <p>跨字段约束（`0 ≤ D ≤ C ≤ B ≤ A ≤ 100`，允许相等）由**后端**判定并返回 `2005`；
 * 前端只做即时提示（`shared/validation.ts` 的 `thresholdOrderingError`），两边口径一致，
 * 但判定权在后端——前端不因为「我算过了」就跳过错误处理。
 */

export function getGradeThresholds(): Promise<GradeThresholds> {
  return http.get<GradeThresholds>('/settings/grade-thresholds')
}

export function updateGradeThresholds(payload: GradeThresholds): Promise<GradeThresholds> {
  return http.put<GradeThresholds>('/settings/grade-thresholds', payload)
}

/** ---- M5 SM3：预警规则 ---- */

export interface AlertRulesPayload {
  enabled: boolean
  totalThreshold: number
  keyDimensionThreshold: number
  trendPeriods: number
  trendDrop: number
}

export function getAlertRules(): Promise<AlertRulesPayload> {
  return http.get<AlertRulesPayload>('/settings/alert-rules')
}

export function updateAlertRules(payload: AlertRulesPayload): Promise<AlertRulesPayload> {
  return http.put<AlertRulesPayload>('/settings/alert-rules', payload)
}
