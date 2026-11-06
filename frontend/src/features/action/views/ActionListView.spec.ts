import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import ElementPlus, { ElOption, ElSelect } from 'element-plus'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import ListPagination from '@/shared/components/ListPagination.vue'

/**
 * 整改列表筛选（W4 补齐）：ADMIN/MANAGER 可按供应商/负责人过滤，
 * EVALUATOR 不加载这两个目录面，查询也不携带对应参数。
 */
const mocks = vi.hoisted(() => ({
  listCorrectiveActions: vi.fn(),
  listSuppliers: vi.fn(),
  listUserOptions: vi.fn(),
  roles: ['ADMIN'] as string[],
}))

vi.mock('@/features/action/api', () => ({
  listCorrectiveActions: mocks.listCorrectiveActions,
}))
vi.mock('@/features/supplier/api', () => ({ listSuppliers: mocks.listSuppliers }))
vi.mock('@/features/user/api', () => ({ listUserOptions: mocks.listUserOptions }))
vi.mock('@/features/auth/store', () => ({ useAuthStore: () => ({ roles: mocks.roles }) }))

import ActionListView from './ActionListView.vue'

function mountView(): ReturnType<typeof mount> {
  return mount(ActionListView, {
    global: {
      plugins: [ElementPlus, createPinia()],
      stubs: { RouterLink: RouterLinkStub },
    },
  })
}

function selectByPlaceholder(wrapper: ReturnType<typeof mount>, placeholder: string) {
  return wrapper
    .findAllComponents(ElSelect)
    .find((select) => select.props('placeholder') === placeholder)
}

beforeEach(() => {
  mocks.roles = ['ADMIN']
  mocks.listCorrectiveActions.mockResolvedValue({ list: [], total: 0, page: 1, size: 10 })
  mocks.listSuppliers.mockResolvedValue({
    list: [
      { id: 1, code: 'SUP-001', name: '供应商一', category: null, status: 'ENABLED', version: 1 },
      { id: 2, code: 'SUP-002', name: '供应商二', category: null, status: 'ENABLED', version: 1 },
    ],
    total: 2,
    page: 1,
    size: 50,
  })
  mocks.listUserOptions.mockResolvedValue([
    { id: 41, name: '李衡', role: 'EVALUATOR', department: 'QUALITY' },
  ])
})

describe('ActionListView 筛选', () => {
  it('ADMIN 加载供应商/负责人选项，并把筛选项传给列表接口', async () => {
    const wrapper = mountView()
    await flushPromises()
    await flushPromises()

    expect(mocks.listSuppliers).toHaveBeenCalledWith(
      expect.objectContaining({ page: 1, size: 50 }),
    )
    expect(mocks.listUserOptions).toHaveBeenCalledWith(['EVALUATOR', 'MANAGER'])

    const supplierSelect = selectByPlaceholder(wrapper, '全部供应商')
    const ownerSelect = selectByPlaceholder(wrapper, '全部负责人')
    expect(supplierSelect).toBeTruthy()
    expect(ownerSelect).toBeTruthy()

    supplierSelect!.vm.$emit('update:modelValue', 2)
    supplierSelect!.vm.$emit('change', 2)
    await flushPromises()
    expect(mocks.listCorrectiveActions).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, supplierId: 2, ownerId: undefined }),
    )

    ownerSelect!.vm.$emit('update:modelValue', 41)
    ownerSelect!.vm.$emit('change', 41)
    await flushPromises()
    expect(mocks.listCorrectiveActions).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, supplierId: 2, ownerId: 41 }),
    )

    // P3：供应商搜索的迟到响应不能覆盖最新结果
    let resolveStale: (value: unknown) => void = () => {}
    let resolveLatest: (value: unknown) => void = () => {}
    mocks.listSuppliers
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveStale = resolve
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveLatest = resolve
          }),
      )
    const remote = supplierSelect!.props('remoteMethod') as (keyword: string) => void
    remote('old')
    remote('new')
    resolveLatest({
      list: [{ id: 3, code: 'NEW', name: '最新供应商', category: null, status: 'ENABLED', version: 1 }],
      total: 1,
      page: 1,
      size: 50,
    })
    await flushPromises()
    resolveStale({
      list: [{ id: 4, code: 'OLD', name: '过期供应商', category: null, status: 'ENABLED', version: 1 }],
      total: 1,
      page: 1,
      size: 50,
    })
    await flushPromises()
    const labels = wrapper.findAllComponents(ElOption).map((option) => option.props('label'))
    expect(labels).toContain('最新供应商 (NEW)')
    expect(labels).not.toContain('过期供应商 (OLD)')

    // P3：翻页/改页大小先写回 page/size 再请求
    const pager = wrapper.getComponent(ListPagination)
    pager.vm.$emit('update:page', 2)
    pager.vm.$emit('change', 2)
    await flushPromises()
    expect(mocks.listCorrectiveActions).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 2, size: 10 }),
    )
    pager.vm.$emit('update:size', 20)
    pager.vm.$emit('size-change', 20)
    await flushPromises()
    expect(mocks.listCorrectiveActions).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, size: 20 }),
    )
  })

  it('EVALUATOR 不加载供应商/负责人目录，查询也不带这两个参数', async () => {
    mocks.roles = ['EVALUATOR']
    const wrapper = mountView()
    await flushPromises()
    await flushPromises()

    expect(mocks.listSuppliers).not.toHaveBeenCalled()
    expect(mocks.listUserOptions).not.toHaveBeenCalled()
    expect(selectByPlaceholder(wrapper, '全部供应商')).toBeUndefined()
    expect(selectByPlaceholder(wrapper, '全部负责人')).toBeUndefined()
    expect(mocks.listCorrectiveActions).toHaveBeenLastCalledWith(
      expect.objectContaining({ supplierId: undefined, ownerId: undefined }),
    )
  })
})
