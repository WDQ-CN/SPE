import { http } from '@/app/http'

/**
 * P0 临时自检，用于验证「Vite 代理 → 后端」链路（09 WP2）。
 * `/api/ping` 归属 system 上下文 web 包（09 D6），其 data 契约由 WP1 定稿，
 * 故此处按 unknown 处理、页面原样展示；P1 接入真实登录后本函数移除。
 */
export function ping(): Promise<unknown> {
  return http.get<unknown>('/ping')
}
