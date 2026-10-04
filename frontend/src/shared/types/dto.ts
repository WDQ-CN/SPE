import type {
  Department,
  EvalTaskStatus,
  IndicatorType,
  Role,
  ScoreMode,
  SheetStatus,
  SupplierStatus,
  TaskSupplierStatus,
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

// ---------- P3 评估流程（13 §E18 的接口形状；字段名与后端 record 逐字对齐） ----------

/** 任务列表行（后端 `TaskSummary`） */
export interface TaskSummary {
  id: number
  name: string
  year: number
  quarter: number
  templateVersionId: number
  deadline: string
  status: EvalTaskStatus
  version: number
}

/** 详情页里的供应商引用 */
export interface TaskSupplierRef {
  id: number
  code: string | null
  name: string | null
}

/** 详情页里的审批人引用（seq 从 1 起） */
export interface TaskApproverRef {
  userId: number
  name: string | null
  seq: number
}

/** 任务详情（后端 `TaskDetail`；缺口端点所对应的形状） */
export interface TaskDetail {
  id: number
  name: string
  year: number
  quarter: number
  templateVersionId: number
  deadline: string
  status: EvalTaskStatus
  version: number
  suppliers: TaskSupplierRef[]
  approvers: TaskApproverRef[]
  sheetCount: number
  unsubmittedSheetCount: number
}

/** 看板行：试算分与等级在不完整时为 null（13 §E6 的不完整口径） */
export interface TaskProgressRow {
  taskSupplierId: number
  supplierId: number
  supplierCode: string | null
  supplierName: string | null
  status: TaskSupplierStatus
  currentNode: number | null
  trialScore: number | null
  trialGrade: string | null
  complete: boolean
  unassignedSheets: number
  unsubmittedSheets: number
}

export interface TaskProgress {
  taskId: number
  rows: TaskProgressRow[]
}

/** 发布结果（后端 `PublishResult`）：四个计数都用「供应商数 × 维度数」的自洽关系断言 */
export interface PublishResult {
  supplierCount: number
  sheetCount: number
  assignedCount: number
  unassignedCount: number
}

/** 待办行（后端 `MySheetView`）：带 `version` —— 打分页提交要用 */
export interface MySheetView {
  sheetId: number
  taskId: number
  taskName: string
  deadline: string
  taskSupplierId: number
  supplierId: number
  supplierCode: string | null
  supplierName: string | null
  dimensionId: number
  dimensionName: string
  status: SheetStatus
  version: number
}

/** 任务下的评分单行（后端 `SheetBrief`）：指派调整要用 */
export interface SheetBrief {
  sheetId: number
  taskSupplierId: number
  supplierId: number
  supplierCode: string | null
  supplierName: string | null
  dimensionId: number
  dimensionName: string
  assigneeId: number | null
  assigneeName: string | null
  status: SheetStatus
  version: number
}

/** 打分页的一个指标（后端 `SheetScoreView.IndicatorEntry`） */
export interface SheetIndicatorEntry {
  indicatorId: number
  code: string
  name: string
  unit: string | null
  type: IndicatorType
  scoreMode: ScoreMode | null
  weight: number
  targetValue: number | null
  standard: string | null
  rawValue: number | null
  scoreValue: number | null
}

/** 打分页数据（后端 `SheetScoreView`）：`version` 是提交时的乐观锁 */
export interface SheetScoreView {
  sheetId: number
  taskId: number
  taskSupplierId: number
  dimensionId: number
  dimensionName: string
  status: SheetStatus
  version: number
  assigneeId: number | null
  indicators: SheetIndicatorEntry[]
}

/** 试算里的单指标结果（后端 `IndicatorScore`） */
export interface IndicatorScoreView {
  indicatorId: number
  code: string
  name: string
  weight: number
  type: IndicatorType
  scoreMode: ScoreMode | null
  rawValue: number | null
  scoreValue: number | null
  contribution: number | null
}

/** 试算里的单维度结果（后端 `ScoreSheetResult`） */
export interface ScoreSheetResultView {
  dimensionId: number
  dimensionName: string
  weight: number
  indicators: IndicatorScoreView[]
  score: number | null
  grade: string | null
  complete: boolean
}

/** 整个评估单的试算结果（后端 `TaskSupplierScore`） */
export interface TaskSupplierScoreView {
  sheets: ScoreSheetResultView[]
  totalScore: number | null
  totalGrade: string | null
  complete: boolean
}

/** 试算出参（后端 `SheetPreview`）：**不落库** */
export interface SheetPreview {
  sheetId: number
  dimensionId: number
  taskSupplier: TaskSupplierScoreView
}

/** 提交出参（后端 `SubmitResult`） */
export interface SubmitResult {
  sheetId: number
  taskSupplierId: number
  taskSupplierStatus: TaskSupplierStatus
  submittedIndicators: number
  correctedCells: number
}

/** 导入结果（后端 `ImportReport`）：有错时后端抛 2027，这里只描述成功的那次 */
export interface ImportReport {
  batchNo: string
  writtenCells: number
  skippedCells: number
}

/** 通知（后端 `Notification`）：`isRead` 是列名派生的属性名 */
export interface NotificationSummary {
  id: number
  type: string
  title: string
  content: string
  isRead: boolean
  createdAt: string
}
