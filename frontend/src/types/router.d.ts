import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    /** 免登录访问 */
    public?: boolean
    title?: string
  }
}
