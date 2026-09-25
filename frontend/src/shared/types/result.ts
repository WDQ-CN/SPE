/** 后端统一响应契约（09 D8）：`code = 0` 表示成功 */
export interface Result<T> {
  code: number
  message: string
  data: T
}

/** 分页响应契约（09 D8） */
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}
