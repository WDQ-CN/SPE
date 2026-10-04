import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { getUnreadCount, markAllRead } from '@/features/evaluation/api'

import { useNotificationStore } from './notification-store'

// 只替换 api 模块（store 是"薄壳"，被测的是**状态怎么变**，不是 http 怎么发）
vi.mock('@/features/evaluation/api', () => ({
  getUnreadCount: vi.fn(),
  markAllRead: vi.fn(),
}))

const mockedUnread = vi.mocked(getUnreadCount)
const mockedMarkAllRead = vi.mocked(markAllRead)

describe('通知角标状态（W6.2）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('刷新后角标数就是后端返回的未读数', async () => {
    mockedUnread.mockResolvedValue(3)
    const store = useNotificationStore()
    await store.refresh()
    expect(store.unread).toBe(3)
  })

  it('点「全部已读」→ 角标**立即**归零，并真的调了后端', async () => {
    mockedUnread.mockResolvedValue(2)
    mockedMarkAllRead.mockResolvedValue(2)
    const store = useNotificationStore()
    await store.refresh()
    expect(store.unread).toBe(2)

    await store.readAll()
    expect(mockedMarkAllRead).toHaveBeenCalledTimes(1)
    expect(store.unread).toBe(0)
  })

  it('读全部已读失败时**不假装成功**：角标保持原值（不能清零——那会让人以为没有未读）', async () => {
    mockedUnread.mockResolvedValue(5)
    mockedMarkAllRead.mockRejectedValue(new Error('网络断了'))
    const store = useNotificationStore()
    await store.refresh()

    await expect(store.readAll()).rejects.toThrow('网络断了')
    expect(store.unread).toBe(5)
  })

  it('刷新失败保留上一次的数值（清零 = 静默失效）', async () => {
    mockedUnread.mockResolvedValue(4)
    const store = useNotificationStore()
    await store.refresh()
    expect(store.unread).toBe(4)

    mockedUnread.mockRejectedValue(new Error('后端不可达'))
    await store.refresh()
    expect(store.unread).toBe(4)
  })

  it('退出登录清空角标（换账号不能看到上一个人的未读数）', async () => {
    mockedUnread.mockResolvedValue(7)
    const store = useNotificationStore()
    await store.refresh()
    store.clear()
    expect(store.unread).toBe(0)
  })
})
