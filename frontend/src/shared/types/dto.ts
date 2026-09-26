import type {
  Department,
  IndicatorType,
  Role,
  ScoreMode,
  SupplierStatus,
  TemplateVersionStatus,
  UserStatus,
} from './enums'

/**
 * 后端响应投影的前端镜像：**字段与后端 record 一一对应**（camelCase）。
 *
 * <p>集中放在 `shared/types` 而不是各 feature 里，是因为它们跨越 feature 边界：
 * 用户页与将来的审批页都要看 `UserSummary`，供应商页与将来的任务页都要看 `SupplierSummary`。
 * 字段契约由 `dto.spec.ts` 用固定样例断言（后端加字段而前端不知道，是这类项目最常见的漂移）。
 */

/** 分页请求参数（`page` 从 1 开始，与后端 `PageQuery` 一致；收敛规则在后端，前端不重复实现） */
export interface PageParams {
  page: number
  size: number
}

/** 用户列表行（后端 `UserSummary`）：`version` 是编辑与启停的守卫条件，必须随行携带 */
export interface UserSummary {
  id: number
  username: string
  name: string
  role: Role
  department: Department
  status: UserStatus
  version: number
}

/** 供应商列表行（后端 `SupplierSummary`）：`category` 可空（10 §E10） */
export interface SupplierSummary {
  id: number
  code: string
  name: string
  category: string | null
  status: SupplierStatus
  version: number
}

/** 等级阈值（后端 `GradeThresholds`）：后端是 `BigDecimal`，JSON 里是数字 */
export interface GradeThresholds {
  a: number
  b: number
  c: number
  d: number
}

/**
 * 模板列表行（后端 `TemplateSummary`）。
 *
 * `latestPublishedNo` 为 `null` 表示**从未发布过**（与"最新版本号"是两回事）；
 * `latestVersionId` 供列表直接进入编辑器。
 */
export interface TemplateSummary {
  id: number
  name: string
  versionCount: number
  draftCount: number
  latestPublishedNo: number | null
  latestVersionId: number | null
}

/** 指标（后端 `IndicatorView`）：`scoreMode` 仅定量有值；`unit`/`targetValue`/`standard` 可空 */
export interface IndicatorView {
  id: number
  name: string
  code: string
  weight: number
  type: IndicatorType
  scoreMode: ScoreMode | null
  unit: string | null
  targetValue: number | null
  standard: string | null
  sortNo: number
  version: number
}

/** 维度（后端 `DimensionView`）：`version` 是改/删的乐观锁守卫，必须原样回传 */
export interface DimensionView {
  id: number
  name: string
  weight: number
  sortNo: number
  suggestedDept: string | null
  isKey: boolean
  version: number
  indicators: IndicatorView[]
}

/**
 * 版本详情整棵树（后端 `TemplateVersionTree`）。
 *
 * ⚠️ 这里的 `version` 是**版本自身**的乐观锁版本（发布时回传它），与每个维度/指标各自的
 * `version` 是三份互不相干的守卫条件（12 §E12）。
 */
export interface TemplateVersionTree {
  templateId: number
  templateName: string
  versionId: number
  versionNo: number
  status: TemplateVersionStatus
  version: number
  dimensions: DimensionView[]
}

/**
 * 写端点的统一响应（后端 `WriteResult`）：见 `features/template/api.ts` 的说明。
 *
 * ⚠️ 刻意**没有**"旧行 id → 新行 id"的映射：服务端不回传它，前端在 `effectiveVersionId` 变化时
 * 按新版本重新拉一次整棵树即可拿到新行 id。
 */
export interface WriteResult {
  effectiveVersionId: number
  effectiveVersion: number
}

/** 已发布版本（后端 `PublishedVersionBrief`）：⚠️ 字段是 `versionId` 而**不是** `id` */
export interface PublishedVersionBrief {
  versionId: number
  versionNo: number
  publishedAt: string | null
}
