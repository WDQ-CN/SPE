import type {
  CorrectiveActionStatus,
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
  VerifyOutcome,
} from './enums'

/**
 * 后端响应投影的前端镜像：**字段与后端 record 一一对应**（camelCase）。
 *
 * <p>集中放在 `shared/types` 而不是各 feature 里，是因为它们跨越 feature 边界：
 * 用户页与将来的审批页都要看 `UserSummary`，供应商页与将来的任务页都要看 `SupplierSummary`。
 * <p>⚠️ 这里曾经写着"字段契约由 `dto.spec.ts` 用固定样例断言"——**那个文件不存在**（03 §38 的 P2-18）。
 * 留着这句话比没有更糟：它让人以为这 30+ 个接口有契约网。现状是**零 DTO 级测试**，
 * 后端改字段名既不编译失败也不测试失败（`effectiveVersion` 全前端零消费就是同一缺口的另一面）。
 * 已作为登记项列入 `11 §9`；要补就得补**真判据**（对照后端真实响应，而不是手写两份一样的样本——
 * 后者改一边就绿，属假覆盖），随各上下文消费时逐个补。
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

/**
 * 可选人员（后端 `UserOption`，02 §4 / 03 §38 P0-1）：审批链与改派两个选择器的数据源。
 *
 * <p>刻意**不含** `username`/`status`/`version`：MANAGER 只需要"选谁"，管理面（含启停要回传的版本号）
 * 仍留在 `/api/users` 那条 ADMIN-only 的边界后面。
 */
export interface UserOption {
  id: number
  name: string
  role: Role
  department: Department
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
  /** 任务所属模板（编辑草稿时回填模板下拉；后端 `TaskDetail` 已含此字段） */
  templateId: number
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

// ---------- P4 审批与结果（02 §4 / 15 §E8/E15；字段名与后端 record 逐字对齐） ----------

/** 我的审批待办（后端 `ApprovalTodoView`）：`version` 是审批动作的乐观锁前置条件 */
export interface ApprovalTodoView {
  taskSupplierId: number
  taskId: number
  taskName: string
  year: number
  quarter: number
  deadline: string
  supplierId: number
  supplierCode: string | null
  supplierName: string | null
  status: TaskSupplierStatus
  currentNode: number
  round: number
  version: number
}

/** 结果报表入口的任务行（后端 `ResultTaskSummary`）：只含已有已生效评估单的任务 */
export interface ResultTaskSummary {
  taskId: number
  taskName: string
  year: number
  quarter: number
  deadline: string
  status: EvalTaskStatus
  resultCount: number
}

/** 任务级结果列表（后端 `TaskResults`）：只含已生效评估单 */
export interface TaskResults {
  taskId: number
  taskName: string
  year: number
  quarter: number
  rows: TaskResultRow[]
}

export interface TaskResultRow {
  taskSupplierId: number
  supplierId: number
  supplierCode: string | null
  supplierName: string | null
  totalScore: number | null
  totalGrade: string | null
  dimensions: DimensionScore[]
}

export interface DimensionScore {
  dimensionId: number
  dimensionName: string
  weight: number
  score: number | null
  grade: string | null
}

/**
 * 评估报告（后端 `TaskSupplierReport`）。
 *
 * ⚠️ `scoreMode` 是两套口径的唯一标记：`SNAPSHOT` = 已生效读定稿快照，`TRIAL` = 未生效降级试算
 * （15 §E15）。前端必须把 TRIAL 在界面上明确标出来，不能把试算分当定稿分展示（01 R1）。
 */
export interface TaskSupplierReport {
  taskSupplierId: number
  taskId: number
  taskName: string
  year: number
  quarter: number
  deadline: string
  supplierId: number
  supplierCode: string | null
  supplierName: string | null
  status: TaskSupplierStatus
  currentNode: number
  round: number
  version: number
  scoreMode: 'SNAPSHOT' | 'TRIAL'
  totalScore: number | null
  totalGrade: string | null
  complete: boolean
  /** 当前登录人是否就是当前节点的审批人（后端判定，`03 §41.3` 的 P2-27）；决定按钮显隐 */
  actableNow: boolean
  dimensions: ReportDimension[]
  timeline: ApprovalTimelineEntry[]
}

export interface ReportDimension {
  dimensionId: number
  dimensionName: string
  weight: number
  score: number | null
  grade: string | null
  complete: boolean
  indicators: ReportIndicator[]
}

export interface ReportIndicator {
  indicatorId: number
  code: string
  name: string
  weight: number
  type: IndicatorType
  scoreMode: ScoreMode | null
  rawValue: number | null
  scoreValue: number | null
  unit: string | null
  source: string | null
}

/** 审批时间线条目（后端 `TimelineEntry`）：按 `(round, seq)` 平铺，前端按轮分组渲染 */
export interface ApprovalTimelineEntry {
  round: number
  seq: number
  approverId: number
  approverName: string | null
  action: string
  comment: string | null
  createdAt: string
}

/** ---- M5：分析看板（B1/B2/B4/B5） ---- */

export interface AnalyticsPeriod {
  year: number
  quarter: number
}

export interface AnalyticsSupplierOption {
  id: number
  code: string
  name: string
  category: string | null
}

export interface AnalyticsDimensionPoint {
  dimensionName: string
  score: number | null
  grade: string | null
}

export interface AnalyticsTrendPoint {
  year: number
  quarter: number
  totalScore: number | null
  totalGrade: string | null
  dimensions: AnalyticsDimensionPoint[]
}

export interface AnalyticsTrend {
  supplierId: number
  supplierCode: string
  supplierName: string
  periods: AnalyticsTrendPoint[]
}

export interface AnalyticsRankingRow {
  supplierId: number
  supplierCode: string
  supplierName: string
  category: string | null
  totalScore: number | null
  totalGrade: string | null
  previousScore: number | null
  previousYear: number | null
  previousQuarter: number | null
  delta: number | null
}

export interface AnalyticsRanking {
  year: number
  quarter: number
  rows: AnalyticsRankingRow[]
}

export interface AnalyticsGradeCount {
  grade: string
  count: number
}

export interface AnalyticsCompareRow {
  supplierId: number
  supplierCode: string
  supplierName: string
  totalScore: number | null
  totalGrade: string | null
  dimensions: AnalyticsDimensionPoint[]
}

export interface AnalyticsCompare {
  year: number
  quarter: number
  rows: AnalyticsCompareRow[]
}

/** ---- M5：预警（W3） ---- */

export interface AlertView {
  id: number
  taskSupplierId: number
  supplierId: number
  supplierCode: string
  supplierName: string
  year: number
  quarter: number
  type: string
  title: string
  content: string
  dimensionId: number | null
  baselineScore: number | null
  targetScore: number | null
  status: string
  correctiveActionId: number | null
  correctiveActionStatus: CorrectiveActionStatus | null
  /** 服务端判定：UNREAD + 有基线/目标 + 尚未创建整改；前端不要重算 */
  actionable: boolean
  handledBy: number | null
  handledByName: string | null
  handledComment: string | null
  handledAt: string | null
  createdAt: string
}

/** ---- 整改闭环（W4） ---- */

/** 整改列表行（后端 `CorrectiveActionSummaryView`）：`overdue` 由服务端计算 */
export interface CorrectiveActionSummary {
  id: number
  alertId: number
  alertType: string
  alertTitle: string
  taskSupplierId: number
  supplierId: number
  supplierCode: string
  supplierName: string
  dimensionId: number | null
  dimensionName: string | null
  baselineYear: number
  baselineQuarter: number
  baselineScore: number
  targetScore: number
  ownerId: number
  ownerName: string | null
  dueDate: string
  status: CorrectiveActionStatus
  overdue: boolean
  verifyYear: number | null
  verifyQuarter: number | null
  verifyScore: number | null
  verifyOutcome: VerifyOutcome | null
  version: number
  createdAt: string
  updatedAt: string
}

/** 整改详情（后端 `CorrectiveActionDetailView`）；所有写接口也返回这个形状 */
export interface CorrectiveActionDetail extends CorrectiveActionSummary {
  /** 已重新提交、等待下期定稿后重新验证；此时经理不能提前确认 */
  reverifyPending: boolean
  measure: string
  expectedResult: string | null
  submitComment: string | null
  submittedAt: string | null
  verifyTaskSupplierId: number | null
  verifyComment: string | null
  verifiedBy: number | null
  verifiedByName: string | null
  verifiedAt: string | null
  canceledReason: string | null
  createdBy: number
  createdByName: string | null
  /** 该整改的动作审计轨迹（时间正序，最多 50 条） */
  auditTrail: AuditTrailEntry[]
}

/** 审计摘要条目（后端 `AuditTrailEntry`）；detail 保持原始 JSON 字符串 */
export interface AuditTrailEntry {
  id: number
  action: string
  operatorId: number | null
  operatorName: string | null
  detail: string | null
  createdAt: string
}

/** ---- M6：开放 API Token（I3） ---- */

export interface ApiTokenSummary {
  id: number
  name: string
  tokenPrefix: string
  status: 'ENABLED' | 'DISABLED'
  lastUsedAt: string | null
  createdAt: string
  version: number
}

/** 生成响应；token 是唯一一次明文，列表不会返回它 */
export interface ApiTokenCreated {
  id: number
  name: string
  tokenPrefix: string
  token: string
  createdAt: string
}

/** ---- M5：操作日志（SM5） ---- */

export interface AuditLogView {
  id: number
  operatorId: number | null
  operatorName: string | null
  action: string
  targetType: string | null
  targetId: number | null
  detail: string | null
  ip: string | null
  createdAt: string
}
