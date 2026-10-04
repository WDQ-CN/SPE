import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { SheetPreview, SheetScoreView } from '@/shared/types/dto'

/**
 * 打分页的**加载竞态**（04-096 / `03 §34` 的 P0-2）。
 *
 * <p>为什么这条值得一个组件级用例：暂存接口**不带乐观锁**（`saveScores` 只传 scores，
 * `02 §4` 如此），而 `load()` 原先"先清状态 → await → 返回后**无条件赋值**"。切评分单时
 * 旧请求**后返回**，就会出现"屏幕上是 B、`values` 是 A"——用户点保存，A 的录入值被写进 B，
 * 而且**后端无从拒绝**（B 也是指派给本人的，`requireWritable` 会放行）⇒ 静默写错分。
 *
 * <p>修法：请求编号，只认最新那一次。**变异验证**：去掉 `if (seq !== loadSeq) return`
 * ⇒ 本用例必须变红（否则这条守卫等于没写）。
 */

const mocks = vi.hoisted(() => ({
  getSheetScores: vi.fn(),
  previewScores: vi.fn(),
  saveScores: vi.fn(),
  submitSheet: vi.fn(),
  importScores: vi.fn(),
  downloadImportTemplate: vi.fn(),
  params: {} as Record<string, unknown>,
}))

vi.mock('@/features/evaluation/api', () => ({
  getSheetScores: mocks.getSheetScores,
  saveScores: mocks.saveScores,
  previewScores: mocks.previewScores,
  submitSheet: mocks.submitSheet,
  importScores: mocks.importScores,
  downloadImportTemplate: mocks.downloadImportTemplate,
}))

// ⚠️ `params` 必须是**响应式**的：用例要在挂载**之后**改 route param，构造"路由已指向 B、
// 而 A 的请求还在飞"的中间态；普通对象改属性不会触发组件里的 watch。
vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  mocks.params = reactive({}) as Record<string, unknown>
  return {
    useRoute: () => ({ params: mocks.params }),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  }
})

vi.mock('@/features/auth/store', () => ({
  useAuthStore: () => ({ roles: ['EVALUATOR'] }),
}))

import SheetScoringView from './SheetScoringView.vue'

enableAutoUnmount(afterEach)

function sheetView(sheetId: number, dimensionName: string, rawValue: number): SheetScoreView {
  return {
    sheetId,
    taskId: 1,
    taskSupplierId: 1,
    dimensionId: sheetId,
    dimensionName,
    status: 'SCORING',
    version: 0,
    assigneeId: 7,
    indicators: [
      {
        indicatorId: 100 + sheetId,
        code: 'IQC_PASS_RATE',
        name: '来料批次合格率',
        unit: '%',
        type: 'QUANT',
        scoreMode: 'RATIO',
        weight: 30,
        targetValue: null,
        standard: null,
        rawValue,
        scoreValue: rawValue,
      },
    ],
  }
}

function emptyPreview(sheetId: number): SheetPreview {
  return {
    sheetId,
    dimensionId: sheetId,
    taskSupplier: { sheets: [], totalScore: null, totalGrade: null, complete: false },
  }
}

describe('打分页：切评分单时的加载竞态（04-096）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.previewScores.mockImplementation((sheetId: number) =>
      Promise.resolve(emptyPreview(sheetId)),
    )
  })

  it('旧请求**后返回**时被丢弃：B 的页面上不会出现 A 的维度与录入值', async () => {
    let resolveFirst: ((value: SheetScoreView) => void) | undefined
    mocks.getSheetScores.mockImplementation((sheetId: number) => {
      if (Number(sheetId) === 1) {
        return new Promise<SheetScoreView>((resolve) => {
          resolveFirst = resolve
        })
      }
      return Promise.resolve(sheetView(2, '交付', 88))
    })

    mocks.params.id = '1'
    const wrapper = mount(SheetScoringView, {
      global: { plugins: [createPinia(), ElementPlus] },
    })
    await flushPromises()

    // 切到第二张评分单，它的响应立刻回来
    mocks.params.id = '2'
    await flushPromises()
    expect(wrapper.text()).toContain('交付')

    // 第一张的响应这才姗姗来迟 —— 它已经过期，不许覆盖
    resolveFirst?.(sheetView(1, '质量', 42))
    await flushPromises()

    expect(wrapper.text(), 'B 的页面必须还是 B').toContain('交付')
    expect(wrapper.text(), 'A 的维度不许出现（否则保存会把 A 的值写进 B）').not.toContain('质量')
  })
})
