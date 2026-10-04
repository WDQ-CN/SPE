import { defineStore } from 'pinia'
import { ref } from 'vue'

import { getUnreadCount, markAllRead } from '@/features/evaluation/api'

/**
 * 未读通知的全局状态（05 §11 / 13 §E19：通知并入 `app/` 的全局 store，不建 `features/notify`）。
 *
 * <p>为什么要有这个 store 而不是让布局直接调接口：**角标数会被两处改**——定时/进入时刷新，
 * 以及用户点"全部已读"。两处各写一份状态，就会出现"读完通知后角标还在"（这正是 W6.2 要守的判据）。
 * 收敛成一个 store 后，"未读数"只有一个来源。
 *
 * <p>⚠️ 刷新失败时**保留上一次的数值**而不是清零：清零会让角标消失，用户以为"没有未读"——
 * 那是静默失效（CLAUDE 默认约定第 1 条）。错误本身已由 http 层统一提示。
 */
export const useNotificationStore = defineStore('notification', () => {
  /** 未读数 = 顶部角标显示的数（W6.2 判据：角标数 = 未读数） */
  const unread = ref(0)
  const loading = ref(false)

  async function refresh(): Promise<void> {
    if (loading.value) {
      return
    }
    loading.value = true
    try {
      unread.value = await getUnreadCount()
    } catch {
      // 保持上一次的未读数（见类注释）
    } finally {
      loading.value = false
    }
  }

  /** 全部已读：成功后**立即**把角标归零，不必等下一次刷新（判据要求"实时变"） */
  async function readAll(): Promise<void> {
    await markAllRead()
    unread.value = 0
  }

  /** 退出登录时清空，避免下一个账号看到上一个人的角标 */
  function clear(): void {
    unread.value = 0
  }

  return { unread, loading, refresh, readAll, clear }
})
