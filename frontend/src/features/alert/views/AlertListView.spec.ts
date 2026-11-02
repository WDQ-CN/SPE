import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { AlertView } from '@/shared/types/dto'

/**
 * 预警页的整改入口（W4）：已有整改、可创建、旧预警、已处理四种行状态，
 * 以及 VIEWER 不能看到任何写入口。
 */
const mocks = vi.hoisted(() => ({
  listAlerts: vi.fn(),
  handleAlert: vi.fn(),
  roles: ['ADMIN'] as string[],
}))

vi.mock('@/features/alert/api', () => ({
  listAlerts: mocks.listAlerts,
  handleAlert: mocks.handleAlert,
}))
vi.mock('@/features/auth/store', () => ({ useAuthStore: () => ({ roles: mocks.roles }) }))
vi.mock('@/features/action/components/ActionFormDialog.vue', async () => {
  const { defineComponent } = await import('vue')
  return {
    default: defineComponent({
      name: 'ActionFormDialog',
      props: {
        modelValue: { type: Boolean, default: false },
        alert: { type: Object, default: null },
        manager: { type: Boolean, default: false },
      },
      emits: ['update:modelValue', 'saved'],
      template: '<div class="action-form-stub" />',
    }),
  }
})

import AlertListView from './AlertListView.vue'

function alert(overrides: Partial<AlertView>): AlertView {
  return {
    id: 1,
    taskSupplierId: 11,
    supplierId: 21,
    supplierCode: 'SUP-001',
    supplierName: '供应商一',
    year: 2026,
    quarter: 1,
    type: 'TOTAL_LOW',
    title: '总分低于阈值',
    content: '内容',
    dimensionId: null,
    baselineScore: 60,
    targetScore: 70,
    status: 'UNREAD',
    correctiveActionId: null,
    correctiveActionStatus: null,
    actionable: true,
    handledBy: null,
    handledByName: null,
    handledComment: null,
    handledAt: null,
    createdAt: '2026-01-01T00:00:00',
    ...overrides,
  }
}

const rows = [
  alert({
    id: 1,
    status: 'HANDLED',
    actionable: false,
    correctiveActionId: 7,
    correctiveActionStatus: 'WAITING_VERIFICATION',
  }),
  alert({ id: 2 }),
  alert({ id: 3, baselineScore: null, targetScore: null, actionable: false }),
  alert({
    id: 4,
    status: 'HANDLED',
    actionable: false,
    handledByName: '张岚',
    handledComment: '已约谈',
  }),
]

function mountView(): ReturnType<typeof mount> {
  return mount(AlertListView, {
    global: {
      plugins: [ElementPlus, createPinia()],
      stubs: { RouterLink: RouterLinkStub },
    },
  })
}

beforeEach(() => {
  mocks.roles = ['ADMIN']
  mocks.listAlerts.mockResolvedValue({ list: rows, total: rows.length, page: 1, size: 10 })
  mocks.handleAlert.mockReset()
})

describe('AlertListView 整改入口', () => {
  it('ADMIN 看到创建/处理/整改链接/旧预警/已处理信息', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('创建整改')
    expect(wrapper.text()).toContain('待验证')
    expect(wrapper.text()).toContain('查看整改')
    expect(wrapper.text()).toContain('旧预警')
    expect(wrapper.text()).toContain('张岚')
    expect(wrapper.findAll('button').filter((button) => button.text() === '处理')).toHaveLength(2)
    expect(wrapper.findAllComponents(RouterLinkStub)).toHaveLength(1)
  })

  it('VIEWER 只看到状态与旧预警提示，没有任何写入口', async () => {
    mocks.roles = ['VIEWER']
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('待验证')
    expect(wrapper.text()).toContain('旧预警')
    expect(wrapper.text()).not.toContain('创建整改')
    expect(wrapper.text()).not.toContain('查看整改')
    expect(wrapper.findAll('button').some((button) => button.text() === '处理')).toBe(false)
    expect(wrapper.findAllComponents(RouterLinkStub)).toHaveLength(0)
  })

  it('创建成功后重载当前列表', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(mocks.listAlerts).toHaveBeenCalledTimes(1)

    const create = wrapper.findAll('button').find((button) => button.text() === '创建整改')
    expect(create).toBeTruthy()
    await create!.trigger('click')

    const dialog = wrapper.findComponent({ name: 'ActionFormDialog' })
    expect(dialog.exists()).toBe(true)
    dialog.vm.$emit('saved')
    await flushPromises()

    expect(mocks.listAlerts).toHaveBeenCalledTimes(2)
  })
})
