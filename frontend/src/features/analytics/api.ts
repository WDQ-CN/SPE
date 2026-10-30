import { http } from '@/app/http'
import type {
  AnalyticsCompare,
  AnalyticsGradeCount,
  AnalyticsPeriod,
  AnalyticsRanking,
  AnalyticsSupplierOption,
  AnalyticsTrend,
} from '@/shared/types/dto'

/** 分析看板接口（M5 W4，02 §4）：只读 EFFECTIVE；权限 ADMIN/MANAGER/VIEWER。 */

export interface PeriodParams {
  year: number
  quarter: number
}

export interface RankingParams extends PeriodParams {
  category?: string
  grade?: string
}

export function listAnalyticsPeriods(): Promise<AnalyticsPeriod[]> {
  return http.get<AnalyticsPeriod[]>('/analytics/periods')
}

export function listAnalyticsSuppliers(keyword?: string): Promise<AnalyticsSupplierOption[]> {
  return http.get<AnalyticsSupplierOption[]>('/analytics/suppliers', {
    keyword: keyword || undefined,
  })
}

export function getAnalyticsTrend(supplierId: number): Promise<AnalyticsTrend> {
  return http.get<AnalyticsTrend>('/analytics/trend', { supplierId })
}

export function getAnalyticsRanking(params: RankingParams): Promise<AnalyticsRanking> {
  return http.get<AnalyticsRanking>('/analytics/ranking', {
    year: params.year,
    quarter: params.quarter,
    category: params.category || undefined,
    grade: params.grade || undefined,
  })
}

export function getGradeDistribution(params: PeriodParams): Promise<AnalyticsGradeCount[]> {
  return http.get<AnalyticsGradeCount[]>('/analytics/grade-distribution', params)
}

export function getAnalyticsCompare(
  params: PeriodParams & { supplierIds: number[] },
): Promise<AnalyticsCompare> {
  // ⚠️ 逗号拼接：axios 默认把数组序列化成 `supplierIds[]=1`，后端 `@RequestParam List<Long>` 只认 `1,2`
  return http.get<AnalyticsCompare>('/analytics/compare', {
    year: params.year,
    quarter: params.quarter,
    supplierIds: params.supplierIds.join(','),
  })
}
