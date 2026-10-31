import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElOption, ElSelect } from 'element-plus'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 建任务弹窗里两处容易被"看起来对"骗过去的逻辑：
 * 1. **模板切换的迟到响应**不得覆盖新选择（03 §41.2 的 P1-17）——慢网下先选 A 再选 B，
 *    A 的晚到列表会让 B 的下拉里显示 A 的版本，任务随后被静默绑错模板；
 * 2. ADMIN 能进任务列表但没有 MANAGER 写权限，按钮必须按角色隐藏（03 §41.3 的 P2-38）。
 */
const mocks = vi.hoisted(() => ({
  listTasks: vi.fn(),
  createTask: vi.fn(),
  getTask: vi.fn(),
  updateTask: vi.fn(),
  publishTask: vi.fn(),
  listTemplates: vi.fn(),
  listPublishedVersions: vi.fn(),
  listSuppliers: vi.fn(),
  listUserOptions: vi.fn(),
  push: vi.fn(),
  roles: ['MANAGER'] as string[],
}))

vi.mock('@/features/evaluation/api', () => ({
  listTasks: mocks.listTasks,
  createTask: mocks.createTask,
  getTask: mocks.getTask,
  updateTask: mocks.updateTask,
  publishTask: mocks.publishTask,
}))
vi.mock('@/features/template/api', () => ({
  listTemplates: mocks.listTemplates,
  listPublishedVersions: mocks.listPublishedVersions,
}))
vi.mock('@/features/supplier/api', () => ({ listSuppliers: mocks.listSuppliers }))
vi.mock('@/features/user/api', () => ({ listUserOptions: mocks.listUserOptions }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock('@/features/auth/store', () => ({ useAuthStore: () => ({ roles: mocks.roles }) }))

import TaskListView from './TaskListView.vue'

const mountView = () =>
  mount(TaskListView, { global: { plugins: [ElementPlus, createPinia()] } })

const versionLabels = (wrapper: ReturnType<typeof mountView>): string[] =>
  wrapper
    .findAllComponents(ElOption)
    .map((option) => String(option.props('label')))
    .filter((label) => /^v\d+$/.test(label))

/** 按 placeholder 定位（页面筛选区还有一个状态下拉，按序号取会错位）。 */
const selectByPlaceholder = (wrapper: ReturnType<typeof mountView>, placeholder: string) => {
  const select = wrapper
    .findAllComponents(ElSelect)
    .find((item) => item.props('placeholder') === placeholder)
  expect(select, `找不到 placeholder=${placeholder} 的下拉`).toBeTruthy()
  return select!
}

async function openCreateDialog(wrapper: ReturnType<typeof mountView>): Promise<void> {
  const button = wrapper.findAll('button').find((item) => item.text() === '新建任务')
  expect(button, 'MANAGER 必须看到「新建任务」').toBeTruthy()
  await button!.trigger('click')
  await flushPromises()
}

beforeEach(() => {
  mocks.roles = ['MANAGER']
  mocks.listTasks.mockResolvedValue({ list: [], total: 0, page: 1, size: 10 })
  mocks.listTemplates.mockResolvedValue({ list: [], total: 0, page: 1, size: 100 })
  mocks.listUserOptions.mockResolvedValue([])
  mocks.listSuppliers.mockResolvedValue({ list: [], total: 0, page: 1, size: 20 })
  mocks.createTask.mockResolvedValue(1)
  mocks.updateTask.mockResolvedValue(undefined)
  mocks.getTask.mockReset()
  mocks.publishTask.mockResolvedValue({})
  mocks.listPublishedVersions.mockReset()
})

describe('TaskListView（建任务弹窗）', () => {
  it('模板切换的迟到响应不会覆盖新选择（P1-17）', async () => {
    let resolveA: (value: unknown) => void = () => {}
    let resolveB: (value: unknown) => void = () => {}
    mocks.listPublishedVersions
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveA = resolve
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveB = resolve
          }),
      )

    const wrapper = mountView()
    await flushPromises()
    await openCreateDialog(wrapper)

    // 按 placeholder 定位模板下拉（页面筛选区还有一个状态下拉）
    const templateSelect = selectByPlaceholder(wrapper, '选择模板')
    templateSelect.vm.$emit('change', 1)
    templateSelect.vm.$emit('change', 2)
    await flushPromises()

    // B 先回、A 后回：A 是过期响应，不得写进 versions
    resolveB([{ versionId: 202, versionNo: 2 }])
    await flushPromises()
    resolveA([{ versionId: 101, versionNo: 9 }])
    await flushPromises()

    expect(versionLabels(wrapper)).toEqual(['v2'])
  })

  it('模板版本加载失败：点名失败项并清空版本列表（P1-17）', async () => {
    mocks.listPublishedVersions.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()
    await openCreateDialog(wrapper)

    selectByPlaceholder(wrapper, '选择模板').vm.$emit('change', 1)
    await flushPromises()

    expect(wrapper.text()).toContain('模板版本选项加载失败')
    expect(versionLabels(wrapper)).toEqual([])
  })

  it('ADMIN 看不到 MANAGER-only 的写按钮（P2-38）', async () => {
    mocks.roles = ['ADMIN']
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.findAll('button').some((item) => item.text() === '新建任务')).toBe(false)
  })

  it('编辑草稿：按详情回填并带 version 调 updateTask（P2-2）', async () => {
    mocks.listTasks.mockResolvedValue({
      list: [
        {
          id: 5,
          name: '旧草稿',
          year: 2026,
          quarter: 1,
          templateVersionId: 101,
          deadline: '2026-03-31',
          status: 'DRAFT',
          version: 3,
        },
      ],
      total: 1,
      page: 1,
      size: 10,
    })
    mocks.listTemplates.mockResolvedValue({
      list: [{ id: 9, name: '默认模板' }],
      total: 1,
      page: 1,
      size: 100,
    })
    mocks.listUserOptions.mockResolvedValue([
      { id: 2, name: '经理', role: 'MANAGER', department: 'OTHER' },
    ])
    mocks.listSuppliers.mockResolvedValue({
      list: [
        { id: 1, code: 'S1', name: '供应商一', category: null, status: 'ENABLED', version: 0 },
      ],
      total: 1,
      page: 1,
      size: 50,
    })
    mocks.listPublishedVersions.mockResolvedValue([{ versionId: 101, versionNo: 1 }])
    mocks.getTask.mockResolvedValue({
      id: 5,
      name: '旧草稿',
      year: 2026,
      quarter: 1,
      templateId: 9,
      templateVersionId: 101,
      deadline: '2026-03-31',
      status: 'DRAFT',
      version: 3,
      suppliers: [{ id: 1, code: 'S1', name: '供应商一' }],
      approvers: [{ userId: 2, name: '经理', seq: 1 }],
      sheetCount: 0,
      unsubmittedSheetCount: 0,
    })

    const wrapper = mountView()
    await flushPromises()
    const edit = wrapper.findAll('button').find((item) => item.text() === '编辑')
    expect(edit, 'DRAFT 行必须有编辑入口').toBeTruthy()
    await edit!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('编辑草稿')
    const save = wrapper.findAll('button').find((item) => item.text() === '保存草稿')
    await save!.trigger('click')
    await flushPromises()

    expect(mocks.updateTask).toHaveBeenCalledWith(
      5,
      expect.objectContaining({
        name: '旧草稿',
        templateId: 9,
        templateVersionId: 101,
        supplierIds: [1],
        approverIds: [2],
        version: 3,
      }),
    )
  })
})
