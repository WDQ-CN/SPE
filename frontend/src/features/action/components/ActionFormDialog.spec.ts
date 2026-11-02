import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElDatePicker, ElSelect } from 'element-plus'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { BizError } from '@/shared/error-code'
import type { AlertView, CorrectiveActionDetail } from '@/shared/types/dto'

/**
 * 整改表单（W4）：创建 payload、EVALUATOR 负责人只读、2xxx 在对话框内回显。
 */
const mocks = vi.hoisted(() => ({
  createCorrectiveAction: vi.fn(),
  updateCorrectiveAction: vi.fn(),
  listUserOptions: vi.fn(),
}))

vi.mock('@/features/action/api', () => ({
  createCorrectiveAction: mocks.createCorrectiveAction,
  updateCorrectiveAction: mocks.updateCorrectiveAction,
}))
vi.mock('@/features/user/api', () => ({ listUserOptions: mocks.listUserOptions }))

import ActionFormDialog from './ActionFormDialog.vue'

const alert: AlertView = {
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
}

function detail(overrides: Partial<CorrectiveActionDetail> = {}): CorrectiveActionDetail {
  return {
    id: 1,
    alertId: 11,
    alertType: 'TOTAL_LOW',
    alertTitle: '总分低于阈值',
    taskSupplierId: 21,
    supplierId: 31,
    supplierCode: 'SUP-001',
    supplierName: '供应商一',
    dimensionId: null,
    dimensionName: null,
    baselineYear: 2026,
    baselineQuarter: 1,
    baselineScore: 60,
    targetScore: 70,
    ownerId: 41,
    ownerName: '李衡',
    dueDate: '2026-03-31',
    measure: '改进来料检验',
    expectedResult: '下期不低于 70',
    status: 'OPEN',
    overdue: false,
    submitComment: null,
    submittedAt: null,
    verifyYear: null,
    verifyQuarter: null,
    verifyTaskSupplierId: null,
    verifyScore: null,
    verifyOutcome: null,
    verifyComment: null,
    verifiedBy: null,
    verifiedByName: null,
    verifiedAt: null,
    canceledReason: null,
    createdBy: 51,
    createdByName: '张岚',
    version: 2,
    createdAt: '2026-01-10T09:00:00',
    updatedAt: '2026-01-10T09:00:00',
    auditTrail: [],
    ...overrides,
  }
}

function mountDialog(props: {
  modelValue: boolean
  alert?: AlertView | null
  action?: CorrectiveActionDetail | null
  manager: boolean
}): ReturnType<typeof mount> {
  return mount(ActionFormDialog, {
    props,
    global: { plugins: [ElementPlus, createPinia()] },
  })
}

async function fillCreateForm(wrapper: ReturnType<typeof mount>): Promise<void> {
  wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 41)
  wrapper.findComponent(ElDatePicker).vm.$emit('update:modelValue', '2026-03-31')
  const areas = wrapper.findAll('textarea')
  await areas[0].setValue('  改进来料检验  ')
  await areas[1].setValue('下期不低于 70')
  await flushPromises()
}

async function clickSave(wrapper: ReturnType<typeof mount>): Promise<void> {
  const save = wrapper.findAll('button').find((button) => button.text() === '保存')
  expect(save).toBeTruthy()
  await save!.trigger('click')
  await flushPromises()
}

beforeEach(() => {
  mocks.createCorrectiveAction.mockReset()
  mocks.updateCorrectiveAction.mockReset()
  mocks.listUserOptions.mockReset()
  mocks.listUserOptions.mockResolvedValue([
    { id: 41, name: '李衡', role: 'EVALUATOR', department: 'QUALITY' },
    { id: 42, name: '张岚', role: 'MANAGER', department: 'PURCHASE' },
  ])
})

describe('ActionFormDialog', () => {
  it('创建：trim 措施并按契约提交', async () => {
    mocks.createCorrectiveAction.mockResolvedValue(detail())
    const wrapper = mountDialog({ modelValue: true, alert, manager: true })
    await flushPromises()

    await fillCreateForm(wrapper)
    await clickSave(wrapper)

    expect(mocks.createCorrectiveAction).toHaveBeenCalledWith(1, {
      ownerId: 41,
      dueDate: '2026-03-31',
      measure: '改进来料检验',
      expectedResult: '下期不低于 70',
    })
    expect(wrapper.emitted('saved')).toHaveLength(1)
  })

  it('EVALUATOR 编辑：不加载负责人目录，回传原 ownerId 与 version', async () => {
    mocks.updateCorrectiveAction.mockResolvedValue(detail())
    const wrapper = mountDialog({ modelValue: true, action: detail(), manager: false })
    await flushPromises()

    expect(wrapper.findComponent(ElSelect).exists()).toBe(false)
    expect(wrapper.text()).toContain('李衡')
    expect(mocks.listUserOptions).not.toHaveBeenCalled()

    await clickSave(wrapper)

    expect(mocks.updateCorrectiveAction).toHaveBeenCalledWith(1, {
      ownerId: 41,
      dueDate: '2026-03-31',
      measure: '改进来料检验',
      expectedResult: '下期不低于 70',
      version: 2,
    })
  })

  it('2xxx 在对话框内回显，不关闭弹窗', async () => {
    mocks.createCorrectiveAction.mockRejectedValue(new BizError(2040, '该预警不能创建整改'))
    const wrapper = mountDialog({ modelValue: true, alert, manager: true })
    await flushPromises()

    await fillCreateForm(wrapper)
    await clickSave(wrapper)

    expect(wrapper.text()).toContain('该预警不能创建整改')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})
