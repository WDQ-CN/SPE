import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { TemplateVersionTree } from '@/shared/types/dto'

/**
 * 模板编辑器的行为（12 §3-W4）。
 *
 * <p>这里测的不是"渲染出来了"，而是三个**容易写错又难以察觉**的点：
 * 1. 权重合计不对时**只禁用发布**（保存不受影响，E4）——禁用条件写宽了会让用户改不动草稿；
 * 2. 已发布版本要提示"改动会自动复制"，且发布按钮必须禁用；
 * 3. **写响应里的 `effectiveVersionId` 与当前版本不同 → 必须 replace 到新版本**（E5/R-5）。
 *    这条错了的现象是"保存之后随便改点什么都提示数据已过期"，很难联想到是这里的分支写错。
 */

const mocks = vi.hoisted(() => ({
  getVersionTree: vi.fn(),
  updateDimension: vi.fn(),
  deleteDimension: vi.fn(),
  publishVersion: vi.fn(),
  replace: vi.fn(),
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

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: mocks.params }),
  useRouter: () => ({ replace: mocks.replace, push: vi.fn() }),
}))

vi.mock('@/features/auth/store', () => ({
  useAuthStore: () => ({ roles: mocks.roles }),
}))

import TemplateEditorView from './TemplateEditorView.vue'

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

describe('模板编辑器：发布闸门与权重提示', () => {
  beforeEach(() => {
    mocks.getVersionTree.mockReset()
    mocks.updateDimension.mockReset()
    mocks.deleteDimension.mockReset()
    mocks.publishVersion.mockReset()
    mocks.replace.mockReset()
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

    expect(wrapper.text()).toContain('还差 10.00')
    expect(publishButton(wrapper)?.attributes('disabled')).toBeDefined()
    // 写入口还在：草稿允许中间态，拦的只是发布（12 §E4）
    expect(wrapper.findAll('button').some((button) => button.text() === '加指标')).toBe(true)
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
    const labels = wrapper.findAll('button').map((button) => button.text())
    expect(labels).not.toContain('加指标')
    expect(labels).not.toContain('改维度')
    expect(labels).not.toContain('删维度')
    expect(labels).not.toContain('添加维度')
  })
})

describe('模板编辑器：写响应与版本切换（E5/R-5）', () => {
  beforeEach(() => {
    mocks.getVersionTree.mockReset()
    mocks.updateDimension.mockReset()
    mocks.deleteDimension.mockReset()
    mocks.replace.mockReset()
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
  })
})
