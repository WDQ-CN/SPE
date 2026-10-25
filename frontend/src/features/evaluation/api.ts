import { http } from '@/app/http'
import type { PageResult } from '@/shared/types/result'
import type {
  ApprovalTodoView,
  ImportReport,
  MySheetView,
  NotificationSummary,
  PublishResult,
  ResultTaskSummary,
  SheetBrief,
  SheetPreview,
  SheetScoreView,
  SubmitResult,
  TaskDetail,
  TaskProgress,
  TaskResults,
  TaskSummary,
  TaskSupplierReport,
} from '@/shared/types/dto'
import type { EvalTaskStatus } from '@/shared/types/enums'

/**
 * 评估流程的接口封装（02 §4 的任务/评分/通知三行；13 §E18）。
 *
 * <p>与 P2 的 `template/api.ts` 同一形态：本文件是 feature 的**唯一出口**（05 §11），
 * 组件不直接碰 `http`——否则端点路径、参数名、错误语义会散到每个页面。
 *
 * <p>⚠️ **带 `version` 的写操作一律要求调用方把"手里那份数据的版本"传进来**：这是后端守卫 UPDATE
 * 的约定（0 行 ⇒ 3001）。前端不要自己推算版本（04-040），版本一律来自最近一次读接口的响应。
 */

/** 任务列表查询（`status` 与 `keyword` 都可省） */
export interface TaskQuery {
  page: number
  size: number
  status?: EvalTaskStatus
  keyword?: string
}

/** 建/改任务草稿的入参（后端 `CreateTaskRequest` / `UpdateTaskRequest`） */
export interface TaskDraftPayload {
  name: string
  year: number
  quarter: number
  /** 所选模板；与 templateVersionId 一并提交，后端校验归属一致（03 §41.2 的 P1-17） */
  templateId: number
  templateVersionId: number
  deadline: string
  supplierIds: number[]
  approverIds: number[]
}

/**
 * 打分格子的入参（后端 `SaveScoresRequest.Entry`）。
 *
 * ⚠️ `rawValue` 为 `null` 表示**清空这一格**（03 §38 的 P2-15；后端 `ScoreWriter` 会删掉这行），
 * 而"这一格整个不在数组里"是另一种语义：**不动它**。两者混不起——把 null 当"不传"就是
 * "删值→保存→刷新后旧值复活"那条用户可见的错行为；反过来把"不传"当 null 会让暂存把没碰过的格子全删掉。
 * 试算（`previewScores`）用不到清空语义，它只关心有值的候选。
 */
export interface ScoreEntryPayload {
  indicatorId: number
  rawValue: number | null
}

export function listTasks(query: TaskQuery): Promise<PageResult<TaskSummary>> {
  return http.get<PageResult<TaskSummary>>('/tasks', query)
}

export function getTask(taskId: number): Promise<TaskDetail> {
  return http.get<TaskDetail>(`/tasks/${taskId}`)
}

export function createTask(payload: TaskDraftPayload): Promise<number> {
  return http.post<number>('/tasks', payload)
}

/** 编辑草稿：`version` 必传（后端 `PUT /tasks/{id}`） */
export function updateTask(
  taskId: number,
  payload: TaskDraftPayload & { version: number },
): Promise<void> {
  return http.put<void>(`/tasks/${taskId}`, payload)
}

export function publishTask(taskId: number, version: number): Promise<PublishResult> {
  return http.post<PublishResult>(`/tasks/${taskId}/publish`, { version })
}

export function getTaskProgress(taskId: number): Promise<TaskProgress> {
  return http.get<TaskProgress>(`/tasks/${taskId}/progress`)
}

/** 任务下的评分单清单（缺口端点）：详情页的指派调整用它 */
export function listTaskSheets(taskId: number): Promise<SheetBrief[]> {
  return http.get<SheetBrief[]>(`/tasks/${taskId}/sheets`)
}

export function assignSheet(sheetId: number, assigneeId: number, version: number): Promise<void> {
  return http.put<void>(`/sheets/${sheetId}/assign`, { assigneeId, version })
}

export function getSheetScores(sheetId: number): Promise<SheetScoreView> {
  return http.get<SheetScoreView>(`/sheets/${sheetId}/scores`)
}

/** 暂存：只传本次改动的格子；返回写入的格子数 */
export function saveScores(sheetId: number, scores: ScoreEntryPayload[]): Promise<number> {
  return http.put<number>(`/sheets/${sheetId}/scores`, { scores })
}

/**
 * 试算（缺口端点）：候选值**不落库**，只用来实时预览。
 *
 * <p>可以传空数组 = 用库里已保存的值算一次（打分页首次进入时用）。
 */
export function previewScores(sheetId: number, scores: ScoreEntryPayload[]): Promise<SheetPreview> {
  return http.post<SheetPreview>(`/sheets/${sheetId}/preview`, { scores })
}

export function submitSheet(sheetId: number, version: number): Promise<SubmitResult> {
  return http.post<SubmitResult>(`/sheets/${sheetId}/submit`, { version })
}

/** 我的待办（只返回指派给我的，过滤在后端） */
export function listMySheets(): Promise<MySheetView[]> {
  return http.get<MySheetView[]>('/my/sheets')
}

/** 下载任务级导入模板（xlsx） */
export function downloadImportTemplate(taskId: number): Promise<Blob> {
  return http.download(`/tasks/${taskId}/import-template`)
}

/** 上传导入（字段名必须是 `file`，对应后端 `@RequestParam("file")`） */
export function importScores(taskId: number, file: File): Promise<ImportReport> {
  const form = new FormData()
  form.append('file', file)
  return http.upload<ImportReport>(`/tasks/${taskId}/import`, form)
}

export function listNotifications(
  page: number,
  size: number,
): Promise<PageResult<NotificationSummary>> {
  return http.get<PageResult<NotificationSummary>>('/notifications', { page, size })
}

export function getUnreadCount(): Promise<number> {
  return http.get<number>('/notifications/unread-count')
}

export function markAllRead(): Promise<number> {
  return http.post<number>('/notifications/read-all')
}

// ---------- P4 审批与结果（02 §4；视图只从这里调端点） ----------

/** 我的审批待办（过滤在后端：当前节点是我 + 状态在审批中） */
export function listMyApprovals(
  page: number,
  size: number,
): Promise<PageResult<ApprovalTodoView>> {
  return http.get<PageResult<ApprovalTodoView>>('/my/approvals', { page, size })
}

/** 通过：`version` 必须是列表/报告里读到的版本；非本节点 → 4003，已流转 → 3001 */
export function approveTaskSupplier(taskSupplierId: number, version: number): Promise<void> {
  return http.post<void>(`/task-suppliers/${taskSupplierId}/approve`, { version })
}

/** 驳回：意见必填（前端先拦，后端 2029 兜底） */
export function rejectTaskSupplier(
  taskSupplierId: number,
  version: number,
  comment: string,
): Promise<void> {
  return http.post<void>(`/task-suppliers/${taskSupplierId}/reject`, { version, comment })
}

/** 结果报表入口：只列已有已生效评估单的任务（VIEWER 可读，不暴露任务管理面） */
export function listResultTasks(query: {
  page: number
  size: number
  status?: EvalTaskStatus
  keyword?: string
}): Promise<PageResult<ResultTaskSummary>> {
  return http.get<PageResult<ResultTaskSummary>>('/results/tasks', query)
}

/** 任务级结果列表（只含已生效评估单） */
export function getTaskResults(taskId: number): Promise<TaskResults> {
  return http.get<TaskResults>(`/tasks/${taskId}/results`)
}

/** 评估报告：`SNAPSHOT`（已生效）或 `TRIAL`（未生效试算）见响应里的 `scoreMode` */
export function getTaskSupplierReport(taskSupplierId: number): Promise<TaskSupplierReport> {
  return http.get<TaskSupplierReport>(`/task-suppliers/${taskSupplierId}/report`)
}
