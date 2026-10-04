import { http } from '@/app/http'
import type { PageResult } from '@/shared/types/result'
import type {
  ImportReport,
  MySheetView,
  NotificationSummary,
  PublishResult,
  SheetBrief,
  SheetPreview,
  SheetScoreView,
  SubmitResult,
  TaskDetail,
  TaskProgress,
  TaskSummary,
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
  templateVersionId: number
  deadline: string
  supplierIds: number[]
  approverIds: number[]
}

/** 打分格子的入参（后端 `SaveScoresRequest.Entry`） */
export interface ScoreEntryPayload {
  indicatorId: number
  rawValue: number
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
