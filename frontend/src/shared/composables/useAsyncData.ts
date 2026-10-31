import { ref, type Ref } from 'vue'

export interface AsyncData<T> {
  data: Ref<T>
  loading: Ref<boolean>
  error: Ref<string>
  run: () => Promise<void>
  /** 取消在飞请求并把数据恢复到 initial（切路由/清场用）。 */
  reset: () => void
}

/**
 * 单资源加载的唯一骨架（03 §51 P2-7）：loading + error + 序号栅栏 + 失败清空。
 *
 * <p>序号栅栏保证迟到响应不会覆盖新请求；失败时回到 `initial`（列表用空数组、详情用 null），
 * 避免"旧数据 + 新错误"被误读成当前结果。
 */
export interface AsyncDataOptions<T> {
  /** 每次 run / reset 前清场（列表/表单的本地状态）。 */
  onReset?: () => void
  /** 仅当这次响应仍是最新时调用（用于图表/子加载等成功副作用）。 */
  onSuccess?: (data: T) => void | Promise<void>
}

export function useAsyncData<T>(
  loader: () => Promise<T>,
  initial: T,
  options: AsyncDataOptions<T> = {},
): AsyncData<T> {
  const data = ref(initial) as Ref<T>
  const loading = ref(false)
  const error = ref('')
  let seq = 0

  async function run(): Promise<void> {
    const current = ++seq
    options.onReset?.()
    loading.value = true
    error.value = ''
    try {
      const result = await loader()
      if (current === seq) {
        data.value = result
        await options.onSuccess?.(result)
      }
    } catch (caught) {
      if (current === seq) {
        data.value = initial
        error.value = caught instanceof Error ? caught.message : '加载失败'
      }
    } finally {
      if (current === seq) {
        loading.value = false
      }
    }
  }

  function reset(): void {
    seq += 1
    options.onReset?.()
    data.value = initial
    loading.value = false
    error.value = ''
  }

  return { data, loading, error, run, reset }
}
