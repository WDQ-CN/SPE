import { http } from '@/app/http'
import type { PageResult } from '@/shared/types/result'
import type {
  PageParams,
  PublishedVersionBrief,
  TemplateSummary,
  TemplateVersionTree,
  WriteResult,
} from '@/shared/types/dto'
import type { IndicatorType, ScoreMode } from '@/shared/types/enums'

/**
 * 模板接口封装（02 §4 的模板行 / 12 §3-W3 落地的 13 个端点）。
 *
 * <p><b>关于写端点的返回值</b>：它们统一是 {@link WriteResult} 而不是被改的那一行。对**已发布**
 * 版本发起改动时，服务端会在同一事务里复制出新草稿再改，于是：
 *
 * <ul>
 *   <li>`effectiveVersionId !== 请求里的 versionId` → **换了版本**（多半是新建了草稿，也可能是
 *       复用一个**已存在**的同源草稿），行 id 全变了：必须按新 id **重新拉整棵树**，不能拿响应
 *       去就地改手里的数组（服务端刻意不回"旧行 id → 新行 id"的映射，见 `shared/types/dto.ts`）；</li>
 *   <li>相等 → 版本没换，行 id 仍然有效。</li>
 * </ul>
 *
 * <p>⚠️ **前端对这两种情况的处理是同一个动作：整树重取**（`TemplateEditorView.runWrite`）。
 * 差别只在"要不要 `router.replace` 到新版本"。所以这里的 {@link WriteResult.effectiveVersion}
 * ——重取回来的树自带每个新行的 `version`，就地写回反倒要自己维护一份映射——**全前端零消费**
 * （`WriteOutcome` 只取 id 与文案）。它仍然必须回（12 §R-5 要求 id 与 version 都回），
 * 因为"改成就地更新、省掉一次重取"是当初留好的口子；03 §30 的 P2-4 记的就是这段说明
 * 与实现（整树重取）对不上。
 *
 * <p>这就是 12 §R-5 说的那件事：只回 id 不够，只回 version 也不够——**两个都要**，否则下一次改删
 * 会被后端的乐观锁守卫用 3001 拒掉。
 */

/** 新增维度（后端 `CreateDimensionRequest`）：`sortNo`/`isKey` 可省略，服务层补默认值 */
export interface CreateDimensionPayload {
  name: string
  weight: number
  sortNo?: number
  suggestedDept?: string | null
  isKey?: boolean
}

/** 改维度（后端 `UpdateDimensionRequest`）：`version` 是守卫，必传 */
export interface UpdateDimensionPayload extends CreateDimensionPayload {
  version: number
}

/** 新增指标（后端 `CreateIndicatorRequest`）：`scoreMode` 仅定量需要；`type=QUAL` 时应为 `null` */
export interface CreateIndicatorPayload {
  name: string
  code: string
  weight: number
  type: IndicatorType
  scoreMode?: ScoreMode | null
  unit?: string | null
  targetValue?: number | null
  standard?: string | null
  sortNo?: number
}

/** 改指标（后端 `UpdateIndicatorRequest`） */
export interface UpdateIndicatorPayload extends CreateIndicatorPayload {
  version: number
}

export function listTemplates(query: PageParams): Promise<PageResult<TemplateSummary>> {
  return http.get<PageResult<TemplateSummary>>('/templates', query)
}

export function createTemplate(name: string): Promise<TemplateSummary> {
  return http.post<TemplateSummary>('/templates', { name })
}

/** 改**模板**名（没有 `version`：`template` 表没有乐观锁列，12 §E2） */
export function renameTemplate(id: number, name: string): Promise<void> {
  return http.put<void>(`/templates/${id}`, { name })
}

export function getVersionTree(versionId: number): Promise<TemplateVersionTree> {
  return http.get<TemplateVersionTree>(`/template-versions/${versionId}`)
}

/**
 * 已发布版本清单（P3 选版本也走同一份数据；草稿不会出现）。
 *
 * <p>⚠️ **当前（P2）没有调用方**：模板页按 12 §E18 只做"看默认模板 → 改权重 → 发布"，不需要选版本。
 * 它对应 12 §E16 里"P3 建任务时选版本"的入口，**是为 P3 预留的**——CLAUDE 第 1 条要求占位式声明
 * 要么当场用一次、要么删掉，这里选择保留并把用途写明（P3 直接消费，不必再补一个函数）。
 */
export function listPublishedVersions(templateId: number): Promise<PublishedVersionBrief[]> {
  return http.get<PublishedVersionBrief[]>(`/templates/${templateId}/published-versions`)
}

export function publishVersion(versionId: number, version: number): Promise<void> {
  return http.post<void>(`/template-versions/${versionId}/publish`, { version })
}

export function addDimension(
  versionId: number,
  payload: CreateDimensionPayload,
): Promise<WriteResult> {
  return http.post<WriteResult>(`/template-versions/${versionId}/dimensions`, payload)
}

export function updateDimension(
  dimensionId: number,
  payload: UpdateDimensionPayload,
): Promise<WriteResult> {
  return http.put<WriteResult>(`/dimensions/${dimensionId}`, payload)
}

/**
 * 删维度：其下的指标会由服务端在同一事务里一并删除（12 §E13）。
 *
 * <p>`version` 走 **query 参数**：DELETE 带 body 不在规范内，各客户端与代理行为不一致
 * （与后端 `@RequestParam` 对应）。
 */
export function deleteDimension(dimensionId: number, version: number): Promise<WriteResult> {
  return http.delete<WriteResult>(`/dimensions/${dimensionId}`, { version })
}

export function addIndicator(
  dimensionId: number,
  payload: CreateIndicatorPayload,
): Promise<WriteResult> {
  return http.post<WriteResult>(`/dimensions/${dimensionId}/indicators`, payload)
}

export function updateIndicator(
  indicatorId: number,
  payload: UpdateIndicatorPayload,
): Promise<WriteResult> {
  return http.put<WriteResult>(`/indicators/${indicatorId}`, payload)
}

export function deleteIndicator(indicatorId: number, version: number): Promise<WriteResult> {
  return http.delete<WriteResult>(`/indicators/${indicatorId}`, { version })
}
