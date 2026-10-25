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

function sheetView(
  sheetId: number,
  dimensionName: string,
  rawValue: number | null,
): SheetScoreView {
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

/**
 * 暂存载荷的 null 语义（03 §38 的 P2-15，18 §6 裁决点 6）。
 *
 * <p>要分开的是两种长得一样的空格子：**被碰过又清空** ⇒ 发显式 `null`（后端删行），
 * **从没填过** ⇒ 根本不在载荷里（后端不动它）。混起来的两种坏法方向相反：
 * 把待删当未填 ⇒ "删值→保存→刷新后旧值复活"（本轮修的就是它），
 * 把未填当待删 ⇒ 一次保存把整张没填完的单子清空。
 */
describe('打分页：清空格子的暂存语义（P2-15）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.previewScores.mockImplementation((sheetId: number) =>
      Promise.resolve(emptyPreview(sheetId)),
    )
    mocks.saveScores.mockResolvedValue(1)
  })

  async function mountLoaded(sheet: SheetScoreView) {
    mocks.getSheetScores.mockResolvedValue(sheet)
    mocks.params.id = String(sheet.sheetId)
    const wrapper = mount(SheetScoringView, {
      global: { plugins: [createPinia(), ElementPlus] },
    })
    await flushPromises()
    return wrapper
  }

  async function clickSave(wrapper: Awaited<ReturnType<typeof mountLoaded>>): Promise<void> {
    const [target] = wrapper.findAll('button').filter((item) => item.text().includes('保存'))
    // 先拿到正例：按钮真的渲染出来了（canEdit 被误关时这里红，而不是用例静默空跑）
    expect(target, '保存按钮必须存在').toBeTruthy()
    await target?.trigger('click')
    await flushPromises()
  }

  function savedPayload() {
    const call = mocks.saveScores.mock.calls[0]
    expect(call, '点保存必须真的发出一次暂存请求').toBeTruthy()
    return call?.[1]
  }

  it('填过又清空 ⇒ 载荷里是显式 null，而不是"这一格不见了"', async () => {
    const wrapper = await mountLoaded(sheetView(2, '交付', 88))
    const cell = wrapper.findComponent({ name: 'ElInputNumber' })
    expect(cell.exists(), '评分格控件必须渲染').toBe(true)

    // 模拟"清空这个输入框"：v-model 更新为 null，并触发 @change（格子就此被记进 touchedIds）
    cell.vm.$emit('update:modelValue', null)
    cell.vm.$emit('change', null)
    await flushPromises()

    await clickSave(wrapper)
    expect(savedPayload()).toEqual([{ indicatorId: 102, rawValue: null }])
  })

  it('没碰过任何东西就保存 ⇒ 只发已填的值', async () => {
    const wrapper = await mountLoaded(sheetView(3, '服务', 77))

    await clickSave(wrapper)
    expect(savedPayload()).toEqual([{ indicatorId: 103, rawValue: 77 }])
  })

  it('库里本就为 null 的格子（从没填过）不会被当成待删', async () => {
    const wrapper = await mountLoaded(sheetView(4, '成本', null))

    await clickSave(wrapper)
    expect(savedPayload()).toEqual([])
  })
})
