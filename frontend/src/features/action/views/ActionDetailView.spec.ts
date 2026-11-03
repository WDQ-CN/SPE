import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElRadioGroup } from 'element-plus'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { CorrectiveActionDetail } from '@/shared/types/dto'

/**
 * 整改详情页的按钮矩阵与验证意见闸门（W4）。
 *
 * <p>页面本身不比较 ownerId：EVALUATOR 能加载详情就说明后端已按本人过滤；
 * 这里只验证角色/状态决定的按钮显隐与 PENDING 时意见必填。
 */
const mocks = vi.hoisted(() => ({
  getCorrectiveAction: vi.fn(),
  startCorrectiveAction: vi.fn(),
  submitCorrectiveAction: vi.fn(),
  verifyCorrectiveAction: vi.fn(),
  cancelCorrectiveAction: vi.fn(),
  roles: ['ADMIN'] as string[],
  push: vi.fn(),
}))

vi.mock('@/features/action/api', () => ({
  getCorrectiveAction: mocks.getCorrectiveAction,
  startCorrectiveAction: mocks.startCorrectiveAction,
  submitCorrectiveAction: mocks.submitCorrectiveAction,
  verifyCorrectiveAction: mocks.verifyCorrectiveAction,
  cancelCorrectiveAction: mocks.cancelCorrectiveAction,
}))
vi.mock('@/features/auth/store', () => ({ useAuthStore: () => ({ roles: mocks.roles }) }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '1' } }),
  useRouter: () => ({ push: mocks.push }),
}))
vi.mock('@/features/action/components/ActionFormDialog.vue', async () => {
  const { defineComponent } = await import('vue')
  return {
    default: defineComponent({
      name: 'ActionFormDialog',
      props: {
        modelValue: { type: Boolean, default: false },
        action: { type: Object, default: null },
        manager: { type: Boolean, default: false },
      },
      emits: ['update:modelValue', 'saved'],
      template: '<div class="action-form-stub" />',
    }),
  }
})

import ActionDetailView from './ActionDetailView.vue'

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
    status: 'WAITING_VERIFICATION',
    overdue: false,
    reverifyPending: false,
    submitComment: '已完成',
    submittedAt: '2026-02-01T10:00:00',
    verifyYear: null,
    verifyQuarter: null,
    verifyTaskSupplierId: null,
    verifyScore: null,
    verifyOutcome: 'PENDING',
    verifyComment: null,
    verifiedBy: null,
    verifiedByName: null,
    verifiedAt: null,
    canceledReason: null,
    createdBy: 51,
    createdByName: '张岚',
    version: 2,
    createdAt: '2026-01-10T09:00:00',
    updatedAt: '2026-02-01T10:00:00',
    auditTrail: [
      {
        id: 1,
        action: 'CORRECTIVE_ACTION_CREATE',
        operatorId: 2,
        operatorName: 'w3-manager',
        detail: '{"alertId":11}',
        createdAt: '2026-01-10T09:00:00',
      },
      {
        id: 2,
        action: 'CORRECTIVE_ACTION_SUBMIT',
        operatorId: 41,
        operatorName: '李衡',
        detail: '{"targetStatus":"WAITING_VERIFICATION"}',
        createdAt: '2026-02-01T10:00:00',
      },
    ],
    ...overrides,
  }
}

function mountView(): ReturnType<typeof mount> {
  return mount(ActionDetailView, {
    global: { plugins: [ElementPlus, createPinia()] },
  })
}

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((button) => button.text() === text)
}

beforeEach(() => {
  mocks.roles = ['ADMIN']
  mocks.getCorrectiveAction.mockResolvedValue(detail())
  mocks.verifyCorrectiveAction.mockReset()
  mocks.push.mockReset()
})

describe('ActionDetailView 操作矩阵', () => {
  it('ADMIN 在 WAITING_VERIFICATION 只看到验证/取消；PENDING 时无意见不能提交', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(buttonByText(wrapper, '编辑')).toBeUndefined()
    expect(buttonByText(wrapper, '提交完成')).toBeUndefined()
    expect(buttonByText(wrapper, '验证')).toBeTruthy()
    expect(buttonByText(wrapper, '取消整改')).toBeTruthy()
    expect(wrapper.text()).toContain('操作审计')
    expect(wrapper.text()).toContain('w3-manager')

    await buttonByText(wrapper, '验证')!.trigger('click')
    await flushPromises()
    wrapper.findComponent(ElRadioGroup).vm.$emit('update:modelValue', true)
    await flushPromises()
    await buttonByText(wrapper, '确认验证')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('下期结果待人工判断时，验证意见不能为空')
    expect(mocks.verifyCorrectiveAction).not.toHaveBeenCalled()

    await wrapper.find('textarea').setValue('人工判断通过')
    mocks.verifyCorrectiveAction.mockResolvedValue(
      detail({
        status: 'VERIFIED',
        version: 3,
        verifyOutcome: 'IMPROVED',
        verifyScore: 75,
        verifyComment: '人工判断通过',
        verifiedByName: '系统管理员',
        verifiedAt: '2026-04-01T11:00:00',
      }),
    )
    await buttonByText(wrapper, '确认验证')!.trigger('click')
    await flushPromises()

    expect(mocks.verifyCorrectiveAction).toHaveBeenCalledWith(1, 2, true, '人工判断通过')
    expect(wrapper.text()).toContain('已通过')
    expect(wrapper.text()).toContain('已改善')

    // 重开待验证：隐藏验证按钮并展示等待新周期提示
    mocks.getCorrectiveAction.mockResolvedValue(
      detail({
        reverifyPending: true,
        verifyYear: 2026,
        verifyQuarter: 2,
        verifyOutcome: 'NOT_IMPROVED',
      }),
    )
    const pendingWrapper = mountView()
    await flushPromises()
    expect(buttonByText(pendingWrapper, '验证')).toBeUndefined()
    expect(pendingWrapper.text()).toContain('已重新提交，等待下期定稿后再次验证')
    expect(pendingWrapper.text()).toContain('上一轮结果，等待新周期')
  })

  it('EVALUATOR 在 WAITING_VERIFICATION 没有验证/取消，只提示等待经理', async () => {
    mocks.roles = ['EVALUATOR']
    const wrapper = mountView()
    await flushPromises()

    expect(buttonByText(wrapper, '验证')).toBeUndefined()
    expect(buttonByText(wrapper, '取消整改')).toBeUndefined()
    expect(buttonByText(wrapper, '编辑')).toBeUndefined()
    expect(wrapper.text()).toContain('已提交，等待采购经理验证')
  })

  it('终态 VERIFIED 只读', async () => {
    mocks.getCorrectiveAction.mockResolvedValue(
      detail({
        status: 'VERIFIED',
        verifyOutcome: 'IMPROVED',
        verifiedAt: '2026-04-01T11:00:00',
      }),
    )
    const wrapper = mountView()
    await flushPromises()

    expect(buttonByText(wrapper, '编辑')).toBeUndefined()
    expect(buttonByText(wrapper, '验证')).toBeUndefined()
    expect(buttonByText(wrapper, '取消整改')).toBeUndefined()
    expect(wrapper.text()).toContain('整改已通过，记录仅供查看')
  })
})
