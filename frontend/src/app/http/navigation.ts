type NavigateHandler = (path: string) => void

let handler: NavigateHandler | null = null

/** 由 router 注册跳转实现，使 http 层不必直接依赖路由模块（避免模块环） */
export function setNavigate(fn: NavigateHandler): void {
  handler = fn
}

export function navigateTo(path: string): void {
  handler?.(path)
}
