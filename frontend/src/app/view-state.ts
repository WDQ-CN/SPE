import { ref, type Ref } from 'vue'

/**
 * 3xxx 冲突时递增。布局用它给 `router-view` 换 key，令当前视图重新挂载，
 * 等效「刷新当前视图」（06 §3.3 / 05 §11）；未使用 Pinia 以遵守「全局 store 仅 auth、notification」。
 */
const viewKey = ref(0)

export function refreshCurrentView(): void {
  viewKey.value += 1
}

export function useViewKey(): Ref<number> {
  return viewKey
}
