import { ref, type Ref } from 'vue'

import { isFormLevelError } from '@/shared/error-code'

/**
 * 表单/对话框提交的公共外壳：**防重复提交** + **只回显需要页面显示的错误**。
 *
 * <p>两个都是容易各写一遍、然后各写错的点：
 * 1. 重复提交：保存按钮没有闸门时，手快连点两次就会发两次请求（新增接口会造出两条数据）；
 * 2. 错误回显：`4xxx/5xxx/3xxx` 已被 http 拦截器全局提示（甚至已刷新视图），再在对话框里
 *    显示一遍是重复打扰；而 `2xxx` 相反——拦截器刻意不提示，必须由表单回显。
 *    这个区分收敛在 `isFormLevelError`，页面不需要自己判 code。
 */
export interface SubmitState {
  submitting: Ref<boolean>
  /** 需要页面显示的错误文案；空串表示没有需要回显的错误 */
  errorMessage: Ref<string>
  /** 执行一次提交；返回是否成功（调用方据此关闭对话框、刷新列表） */
  run: (action: () => Promise<void>) => Promise<boolean>
  clearError: () => void
}

export function useSubmit(): SubmitState {
  const submitting = ref(false)
  const errorMessage = ref('')

  async function run(action: () => Promise<void>): Promise<boolean> {
    if (submitting.value) {
      return false
    }
    submitting.value = true
    errorMessage.value = ''
    try {
      await action()
      return true
    } catch (caught) {
      errorMessage.value = isFormLevelError(caught) ? caught.message : ''
      return false
    } finally {
      submitting.value = false
    }
  }

  return { submitting, errorMessage, run, clearError: () => (errorMessage.value = '') }
}
