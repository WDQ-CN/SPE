import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { TemplateVersionTree } from '@/shared/types/dto'

/**
 * 模板编辑器的行为（12 §3-W4）。
 *
 * <p>这里测的不是"渲染出来了"，而是几个**容易写错又难以察觉**的点：
 * 1. 权重合计不对时**只禁用发布**（保存不受影响，E4）——禁用条件写宽了会让用户改不动草稿；
 * 2. 已发布版本要提示"改动会自动复制"，且发布按钮必须禁用；
 * 3. **写响应里的 `effectiveVersionId` 与当前版本不同 → 必须 replace 到新版本**（E5/R-5）。
 *    这条错了的现象是"保存之后随便改点什么都提示数据已过期"，很难联想到是这里的分支写错；
 * 4. **切换版本时不得同时留下"旧版本的树"与"新版本的 URL"**（03 §30 的 P2-1）——窗口期内的写入口
 *    会拿旧版本的 id 往旧版本写。
 */

const mocks = vi.hoisted(() => ({
  getVersionTree: vi.fn(),
  updateDimension: vi.fn(),
  deleteDimension: vi.fn(),
  publishVersion: vi.fn(),
  replace: vi.fn(),
  push: vi.fn(),
  params: {} as Record<string, unknown>,
  roles: ['ADMIN'] as string[],
}))

vi.mock('@/features/template/api', () => ({
  getVersionTree: mocks.getVersionTree,
  addDimension: vi.fn(),
  updateDimension: mocks.updateDimension,
  deleteDimension: mocks.deleteDimension,
  addIndicator: vi.fn(),
  updateIndicator: vi.fn(),
  deleteIndicator: vi.fn(),
  publishVersion: mocks.publishVersion,
}))

// ⚠️ `params` 必须是**响应式**的：下面有一条用例要在挂载**之后**改 route param，构造
// "路由已指向新版本、而新版本的请求还在飞"的中间态；普通对象改属性不会触发组件里的 watch。
vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  mocks.params = reactive({}) as Record<string, unknown>
  return {
    useRoute: () => ({ params: mocks.params }),
    useRouter: () => ({ replace: mocks.replace, push: mocks.push }),
  }
})

vi.mock('@/features/auth/store', () => ({
  useAuthStore: () => ({ roles: mocks.roles }),
}))

import TemplateEditorView from './TemplateEditorView.vue'

// 响应式 params 让"挂载后改 param"可行，代价是**上个用例的组件不能还挂在树上**：否则改 param 会
// 连带触发它们的 loadTree，把 mock 的 once 队列（本用例的挂起响应）抢走。逐个用例自动卸载。
enableAutoUnmount(afterEach)

/** 一个"权重完全合规"的草稿：维度合计 100，每个维度内指标合计 100 */
function draftTree(): TemplateVersionTree {
  return {
    templateId: 1,
    templateName: '测试模板',
    versionId: 10,
    versionNo: 1,
    status: 'DRAFT',
    version: 2,
    dimensions: [
      {
        id: 100,
        name: '质量',
        weight: 60,
        sortNo: 1,
        suggestedDept: null,
        isKey: false,
        version: 0,
        indicators: [
          {
            id: 1000,
            name: '合格率',
            code: 'T_A',
            weight: 100,
            type: 'QUANT',
            scoreMode: 'RATIO',
            unit: '%',
            targetValue: null,
            standard: null,
            sortNo: 1,
            version: 0,
          },
        ],
      },
      {
        id: 101,
        name: '交付',
        weight: 40,
        sortNo: 2,
        suggestedDept: null,
        isKey: false,
        version: 0,
        indicators: [
          {
            id: 1001,
            name: '准时率',
            code: 'T_B',
            weight: 100,
            type: 'QUANT',
            scoreMode: 'RATIO',
            unit: '%',
            targetValue: null,
            standard: null,
            sortNo: 1,
            version: 0,
          },
        ],
      },
    ],
  }
}

async function mountEditor(): Promise<VueWrapper> {
  mocks.params.versionId = '10'
  const wrapper = mount(TemplateEditorView, {
    global: { plugins: [ElementPlus, createPinia()] },
  })
  await flushPromises()
  return wrapper
}

/** 找到"发布 vN"按钮（它带 :disabled，是发布闸门的唯一体现） */
function publishButton(wrapper: VueWrapper) {
  return wrapper.findAll('button').find((button) => button.text().startsWith('发布'))
}

/** 模板里所有"会往服务端写"的按钮文案 */
const WRITE_ENTRY_LABELS = ['加指标', '改维度', '删维度', '添加维度', '添加第一个维度']

/**
 * 当前页面上还活着的写入口。
 *
 * <p>断言形状刻意是"**把它们全列出来**"而不是逐条 `not.toContain`：漏了一种按钮（比如空态里的
 * "添加第一个维度"）逐条断言不会报错，而这里会。P2-1 的不变式就是它必须为空集。
 */
function writeEntryLabels(wrapper: VueWrapper): string[] {
  const labels = wrapper.findAll('button').map((button) => button.text())
  return WRITE_ENTRY_LABELS.filter((label) => labels.includes(label))
}

/**
 * 顶部那条权重告警的文案。
 *
 * <p>必须**只**断言它：空版本时页面下方的空态段落里也写着"这个版本还没有维度"，
 * 拿整个 `wrapper.text()` 去断言会假绿——旧代码（告警说"还差 100.00"）照样过。
 */
function weightHint(wrapper: VueWrapper): string {
  return wrapper.find('.editor__weight').text()
}

describe('模板编辑器：发布闸门与权重提示', () => {
  beforeEach(() => {
    mocks.getVersionTree.mockReset()
    mocks.updateDimension.mockReset()
    mocks.deleteDimension.mockReset()
    mocks.publishVersion.mockReset()
    mocks.replace.mockReset()
    mocks.push.mockReset()
    mocks.roles = ['ADMIN']
  })

  it('权重合规时提示可以发布，且发布按钮可用', async () => {
    mocks.getVersionTree.mockResolvedValue(draftTree())

    const wrapper = await mountEditor()

    expect(wrapper.text()).toContain('权重合计正确，可以发布')
    expect(publishButton(wrapper)?.attributes('disabled')).toBeUndefined()
  })

  it('维度合计不为 100 时禁用发布，并提示差多少（保存不受影响）', async () => {
    const tree = draftTree()
    tree.dimensions[1].weight = 30 // 60 + 30 = 90
    mocks.getVersionTree.mockResolvedValue(tree)

    const wrapper = await mountEditor()

    expect(weightHint(wrapper)).toContain('还差 10.00')
    expect(publishButton(wrapper)?.attributes('disabled')).toBeDefined()
    // 写入口还在：草稿允许中间态，拦的只是发布（12 §E4）
    expect(writeEntryLabels(wrapper)).toContain('加指标')
  })

  it('一个维度都没有的草稿：提示"还没有维度"，而不是"权重合计 0.00、还差 100.00"（P2-2）', async () => {
    const tree = draftTree()
    tree.dimensions = []
    mocks.getVersionTree.mockResolvedValue(tree)

    const wrapper = await mountEditor()

    // 旧实现在这里走的是 `!report.dimensions.valid` 那条通用文案（合计 0.00 ⇒ 恒不合法），
    // 于是把"一个维度都还没建"说成了"权重还差 100"——用户按那句话去配也配不出来（没有维度可配）
    expect(weightHint(wrapper)).toContain('还没有维度')
    expect(weightHint(wrapper)).not.toContain('还差')
    // 空版本本来就不能发布（发布闸门另有出处：`weight.ts` 的 publishable，语义不动）
    expect(publishButton(wrapper)?.attributes('disabled')).toBeDefined()
  })

  it('某维度下没有指标时，提示指名道姓说出是哪个维度', async () => {
    const tree = draftTree()
    tree.dimensions[0].indicators = []
    mocks.getVersionTree.mockResolvedValue(tree)

    const wrapper = await mountEditor()

    expect(wrapper.text()).toContain('质量')
    expect(wrapper.text()).toContain('还没有指标')
    expect(publishButton(wrapper)?.attributes('disabled')).toBeDefined()
  })

  it('已发布版本：发布按钮禁用，并明确提示改动会自动复制草稿', async () => {
    const tree = draftTree()
    tree.status = 'PUBLISHED'
    mocks.getVersionTree.mockResolvedValue(tree)

    const wrapper = await mountEditor()

    expect(wrapper.text()).toContain('已发布、不可修改')
    expect(wrapper.text()).toContain('自动复制')
    expect(publishButton(wrapper)?.attributes('disabled')).toBeDefined()
  })

  it('MANAGER 只读：不出现任何写入口，也没有发布按钮', async () => {
    mocks.getVersionTree.mockResolvedValue(draftTree())
    mocks.roles = ['MANAGER']

    const wrapper = await mountEditor()

    expect(publishButton(wrapper)).toBeUndefined()
    // 一处不漏地把写入口列全：漏测一种按钮（如空态里的"添加第一个维度"）就等于没测
    expect(writeEntryLabels(wrapper)).toEqual([])
  })
})

describe('模板编辑器：写响应与版本切换（E5/R-5）', () => {
  beforeEach(() => {
    mocks.getVersionTree.mockReset()
    mocks.updateDimension.mockReset()
    mocks.deleteDimension.mockReset()
    mocks.replace.mockReset()
    mocks.push.mockReset()
    mocks.roles = ['ADMIN']
  })

  it('生效版本与当前一致：只重新拉树，不跳转', async () => {
    mocks.getVersionTree.mockResolvedValue(draftTree())
    // 删除维度返回同一个版本 id → 原地更新
    mocks.deleteDimension.mockResolvedValue({ effectiveVersionId: 10, effectiveVersion: 3 })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const wrapper = await mountEditor()
    expect(mocks.getVersionTree).toHaveBeenCalledTimes(1)

    const deleteButton = wrapper.findAll('button').find((button) => button.text() === '删维度')
    await deleteButton?.trigger('click')
    await flushPromises()

    expect(mocks.deleteDimension).toHaveBeenCalledWith(100, 0)
    expect(mocks.replace).not.toHaveBeenCalled()
    // 重新拉了树（原地更新后要拿到最新的权重与版本号）
    expect(mocks.getVersionTree).toHaveBeenCalledTimes(2)
  })

  it('生效版本不同（自动复制）：replace 到新版本，并且带上新 id', async () => {
    mocks.getVersionTree.mockResolvedValue(draftTree())
    // 改的是已发布版本 → 服务端复制出新草稿，id 从 10 变成 22
    mocks.deleteDimension.mockResolvedValue({ effectiveVersionId: 22, effectiveVersion: 0 })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const wrapper = await mountEditor()

    const deleteButton = wrapper.findAll('button').find((button) => button.text() === '删维度')
    await deleteButton?.trigger('click')
    await flushPromises()

    expect(mocks.replace).toHaveBeenCalledWith({
      name: 'template-editor',
      params: { versionId: 22 },
    })
    // 必须是 replace 而不是 push：用户不打算回到那个不可修改的已发布版本
    expect(mocks.push).not.toHaveBeenCalled()
  })

  it('判据只能是版本 id：effectiveVersion 与手里的一致，也必须 replace 到生效 id', async () => {
    // 树自己的乐观锁 version 是 2；响应里的 effectiveVersion 也恰好是 2。
    // 「按行版本判断是否换了版本」（复制出来的行 version 从 0 起算，一眼看着像"没变"）会在这里
    // 判成"原地更新"——而实际上行 id 已经全变了，下一次改删必被 3001 拒。
    // 实测：把 copied 改成 `result.effectiveVersion !== current.version`，本例变红。
    mocks.getVersionTree.mockResolvedValue(draftTree())
    mocks.deleteDimension.mockResolvedValue({ effectiveVersionId: 22, effectiveVersion: 2 })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const wrapper = await mountEditor()

    const deleteButton = wrapper.findAll('button').find((button) => button.text() === '删维度')
    await deleteButton?.trigger('click')
    await flushPromises()

    expect(mocks.replace).toHaveBeenCalledWith({
      name: 'template-editor',
      params: { versionId: 22 },
    })
  })

  it('切换版本且新版本的树还在飞时：旧树先清掉，写入口一个都不剩（P2-1）', async () => {
    // 路由组件被复用（name 不变 ⇒ 不重新挂载）：在版本 A 的编辑器里返回列表、再进版本 B，
    // 走的正是这条 watch → loadTree 的路径
    mocks.getVersionTree.mockResolvedValueOnce(draftTree()) // 版本 A：versionId=10
    const wrapper = await mountEditor()
    expect(writeEntryLabels(wrapper)).toContain('加指标')

    // 版本 B（versionId=20）：响应挂起，把"路由已变、请求在飞"的窗口期钉住
    let releaseB: (tree: TemplateVersionTree) => void = () => {}
    mocks.getVersionTree.mockImplementationOnce(
      () => new Promise<TemplateVersionTree>((resolve) => {
        releaseB = resolve
      }),
    )

    mocks.params.versionId = '20'
    await flushPromises()

    // 不变式：窗口期内不得同时存在"旧版本的树"与"新版本的 URL"——旧树在，写按钮就在，
    // 而它们写的是 `tree.value.versionId`（= 旧的 10），改动会落到旧版本上
    expect(wrapper.text()).not.toContain('质量')
    expect(writeEntryLabels(wrapper)).toEqual([])
    expect(publishButton(wrapper)).toBeUndefined()

    // 收尾：B 到达后照常渲染（清树只是窗口期行为，不是把页面永久清空）
    releaseB({ ...draftTree(), versionId: 20, versionNo: 3 })
    await flushPromises()

    expect(wrapper.text()).toContain('质量')
    expect(writeEntryLabels(wrapper)).toContain('加指标')
  })

  it('同一个版本的重载（写完重取）不清树，页面不闪', async () => {
    // 清树的判据是"树与目标版本不一致"，不是"每次 loadTree 都清"——否则每次保存都会
    // 把整页内容清掉再填回来，用户看到的是白屏闪烁。这里把重取的响应挂起，把那一刻钉住。
    mocks.getVersionTree.mockResolvedValueOnce(draftTree())
    mocks.deleteDimension.mockResolvedValue({ effectiveVersionId: 10, effectiveVersion: 3 })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const wrapper = await mountEditor()

    let releaseReload: (tree: TemplateVersionTree) => void = () => {}
    mocks.getVersionTree.mockImplementationOnce(
      () => new Promise<TemplateVersionTree>((resolve) => {
        releaseReload = resolve
      }),
    )

    const deleteButton = wrapper.findAll('button').find((button) => button.text() === '删维度')
    await deleteButton?.trigger('click')
    await flushPromises()

    // 同一版本的重取在飞时，旧树（内容仍然有效）必须还在：写入口照旧
    expect(writeEntryLabels(wrapper)).toContain('加指标')

    releaseReload(draftTree())
    await flushPromises()
  })
})
