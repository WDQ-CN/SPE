import axios, {
  type AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'

import { refreshCurrentView } from '@/app/view-state'
import { BizError, LOGIN_FAILED, segmentOf, type ErrorSegment } from '@/shared/error-code'
import type { Result } from '@/shared/types/result'
import { navigateTo } from './navigation'
import { clearToken, getToken } from './token'

const DEFAULT_MESSAGE: Record<ErrorSegment, string> = {
  auth: '登录状态已失效，请重新登录',
  param: '请求参数有误',
  conflict: '数据已被他人改动，已刷新当前视图，请重试',
  forbidden: '无权限执行该操作',
  system: '系统异常，请稍后重试',
  unknown: '请求失败',
}

const instance = axios.create({ baseURL: '/api', timeout: 10_000 })

instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * 统一处理 Result 的非成功码（06 §3.3）：
 * 1xxx 清 token 跳登录；3xxx 提示并刷新当前视图；2xxx 不弹提示，交给表单回显；其余弹错误提示。
 * 处理完统一抛 BizError，调用方无需再判 code。
 */
function failWith(result: Result<unknown>): never {
  const segment = segmentOf(result.code)
  const message = result.message || DEFAULT_MESSAGE[segment]

  // 登录失败与 2xxx 同等待遇（10 §E2）：不清 token、不跳登录、不弹全局提示，只抛给表单回显。
  // 若按 1xxx 段处理，用户输错密码会看到「会话已失效」并被跳一次登录页。
  if (result.code === LOGIN_FAILED) {
    throw new BizError(result.code, message)
  }

  if (segment === 'auth') {
    clearToken()
    ElMessage.error(message)
    navigateTo('/login')
  } else if (segment === 'conflict') {
    ElMessage.warning(message)
    refreshCurrentView()
  } else if (segment !== 'param') {
    ElMessage.error(message)
  }

  throw new BizError(result.code, message)
}

/**
 * 传输层失败（网络不可达、超时、非 Result 报文）；message 面向用户可读，
 * cause 保留原始 AxiosError（status、URL、config）以便排查。
 */
export class TransportError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options)
    this.name = 'TransportError'
  }
}

function failWithTransport(error: AxiosError): never {
  const status = error.response?.status
  let message: string

  if (status === 401) {
    clearToken()
    message = DEFAULT_MESSAGE.auth
    navigateTo('/login')
  } else if (status === 403) {
    message = DEFAULT_MESSAGE.forbidden
  } else if (error.code === 'ECONNABORTED') {
    message = '请求超时，请稍后重试'
  } else if (status === undefined) {
    // 没有响应才算「连不上」：DNS 失败、连接被拒、CORS 拦截
    message = '无法连接后端服务，请确认后端已启动'
  } else if (status === 404) {
    message = '请求的接口不存在（404）'
  } else if (status >= 500) {
    message = `后端服务异常（${status}），请稍后重试`
  } else {
    message = `请求未被接受（${status}）`
  }

  ElMessage.error(message)
  throw new TransportError(message, { cause: error })
}

instance.interceptors.response.use(
  (response: AxiosResponse<Result<unknown>>) => {
    const result = response.data
    return result.code === 0 ? response : failWith(result)
  },
  (error: AxiosError<Result<unknown>>) => {
    const result = error.response?.data
    if (result && typeof result.code === 'number') {
      // 后端把 401/403 也包成 Result 输出（09 D6），按业务码统一处理
      failWith(result)
    }
    failWithTransport(error)
  },
)

// 拦截器负责校验与提示，这里解包 Result，调用方直接拿到 data
export const http = {
  get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
    return instance.get<Result<T>>(url, { params }).then((response) => response.data.data)
  },
  post<T>(url: string, data?: unknown): Promise<T> {
    return instance.post<Result<T>>(url, data).then((response) => response.data.data)
  },
  put<T>(url: string, data?: unknown): Promise<T> {
    return instance.put<Result<T>>(url, data).then((response) => response.data.data)
  },
  // 02 §4 已有 DELETE 类端点（如 /api-tokens），先备好，避免届时绕开统一封装
  delete<T>(url: string, params?: Record<string, unknown>): Promise<T> {
    return instance.delete<Result<T>>(url, { params }).then((response) => response.data.data)
  },
}
