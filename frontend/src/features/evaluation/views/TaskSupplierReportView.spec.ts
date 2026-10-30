import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { BizError } from '@/shared/error-code'
import type { TaskSupplierReport } from '@/shared/types/dto'

/**
 * 报告页解锁的**错误出口**（03 §44 的 M5R-03 / 04-120）：2xxx 由 useSubmit 记下，
 * 拦截器刻意不弹全局提示，页面必须自己渲染，否则管理员点「确认解锁」后毫无反馈。
 */
const mocks = vi.hoisted(() => ({
  getReport: vi.fn(),
  unlock: vi.fn(),
  prompt: vi.fn(),
  push: vi.fn(),
  back: vi.fn(),
}))

vi.mock('@/features/evaluation/api', () => ({
  getTaskSupplierReport: mocks.getReport,
  unlockTaskSupplier: mocks.unlock,
}))
vi.mock('@/features/auth/store', () => ({ useAuthStore: () => ({ roles: ['ADMIN'] }) }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '7' } }),
  useRouter: () => ({ push: mocks.push, back: mocks.back }),
}))
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return { ...actual, ElMessageBox: { ...actual.ElMessageBox, prompt: mocks.prompt } }
})

import TaskSupplierReportView from './TaskSupplierReportView.vue'

enableAutoUnmount(afterEach)

const report: TaskSupplierReport = {
  taskSupplierId: 7,
  taskId: 3,
  taskName: '解锁错误出口',
  year: 2026,
  quarter: 3,
  deadline: '2026-09-30',
  supplierId: 9,
  supplierCode: 'SUP-9',
  supplierName: '供应商九',
  status: 'EFFECTIVE',
  currentNode: 2,
  round: 1,
  version: 0,
  scoreMode: 'SNAPSHOT',
  totalScore: 80,
  totalGrade: 'B',
  complete: true,
  actableNow: false,
  dimensions: [],
  timeline: [],
}

function mountView(): ReturnType<typeof mount> {
  return mount(TaskSupplierReportView, {
    global: {
      plugins: [ElementPlus, createPinia()],
      stubs: { ScoreRadar: true, ScoreBreakdown: true, ApprovalTimeline: true },
    },
  })
}

beforeEach(() => {
  mocks.getReport.mockReset()
  mocks.unlock.mockReset()
  mocks.prompt.mockReset()
  mocks.prompt.mockResolvedValue({ value: '复核', action: 'confirm' })
})

describe('TaskSupplierReportView（解锁错误出口）', () => {
  it('解锁返回 2020 时在页面回显，而不是静默失败', async () => {
    mocks.getReport.mockResolvedValue(report)
    mocks.unlock.mockRejectedValue(new BizError(2020, '评估单当前状态不允许解锁'))

    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find((item) => item.text().includes('解锁重评'))
    expect(button, 'ADMIN 在 EFFECTIVE 报告上必须看到解锁按钮').toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(mocks.unlock).toHaveBeenCalledWith(7, 0, '复核')
    expect(wrapper.text()).toContain('评估单当前状态不允许解锁')
  })
})
