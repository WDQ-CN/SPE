/**
 * 错误码分段（02 §4 / 06 §2.6 / 09 D8）：
 * 1xxx 认证、2xxx 参数、3xxx 状态冲突、4xxx 权限、5xxx 系统。
 */
export type ErrorSegment = 'auth' | 'param' | 'conflict' | 'forbidden' | 'system' | 'unknown'

const SEGMENT_BY_THOUSAND: Record<number, ErrorSegment> = {
  1: 'auth',
  2: 'param',
  3: 'conflict',
  4: 'forbidden',
  5: 'system',
}

export function segmentOf(code: number): ErrorSegment {
  return SEGMENT_BY_THOUSAND[Math.trunc(code / 1000)] ?? 'unknown'
}

/**
 * 登录失败（10 号计划 E2）：它在 1xxx 认证段里，但语义不是「会话失效」——
 * 登录页要按 2xxx 处理它（只在表单回显），否则「账号或密码错误」会被提示成会话过期并触发跳转。
 */
export const LOGIN_FAILED = 1002

/** 业务异常：拦截器按分段提示后仍抛出，调用方据此决定是否回显到表单 */
export class BizError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'BizError'
    this.code = code
  }
}
