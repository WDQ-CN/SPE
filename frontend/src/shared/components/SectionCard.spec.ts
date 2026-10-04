import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'

import SectionCard from './SectionCard.vue'

/**
 * 通用区块卡片（`03 §35` 登记"没有单测"的组件之二）。
 *
 * <p>它背着一条**默认值契约**：`delay` 默认 `0.1s`。这个默认值不是随手取的——
 * 页头用 `0s`、卡片用 `0.1s`，两者拼成"页头先落、卡片随后"的第二拍；
 * 若默认值被改成 `0s`，六个调用点（它们都**不再显式传** delay）会同时失去阶梯感，
 * 而现象只是"动画看起来有点齐"，不会有人报 bug。
 */
// ⚠️ props 的类型要写具体（`{ title; delay? }`）而不是 `Record<string, unknown>`：
// vitest 不做类型检查，但 `pnpm build` 会跑 vue-tsc —— 后者要求 props 与组件的声明兼容
const mountCard = (
  props: { title: string; delay?: string },
  slots: Record<string, string> = {},
) =>
  mount(SectionCard, {
    props,
    slots,
    global: { plugins: [ElementPlus] },
  })

describe('SectionCard（通用区块卡片）', () => {
  it('标题落在卡片头部，正文走默认插槽', () => {
    const wrapper = mountCard({ title: '供应商列表' }, { default: '<p>正文内容</p>' })
    expect(wrapper.get('.section-head').text()).toContain('供应商列表')
    expect(wrapper.get('.el-card__body').text()).toContain('正文内容')
  })

  it('actions 插槽渲染到头部右侧（操作按钮的位置由组件定，不由页面定）', () => {
    const wrapper = mountCard(
      { title: 'T' },
      { default: '<p>b</p>', actions: '<button class="probe">新增</button>' },
    )
    expect(wrapper.get('.section-head__actions .probe').text()).toBe('新增')
  })

  it('delay 默认 0.1s：走默认值时卡片仍排在页头之后', () => {
    const wrapper = mountCard({ title: 'T' })
    expect(wrapper.get('.el-card').attributes('style')).toContain('animation-delay: 0.1s')
  })

  it('传 delay 时覆盖默认值', () => {
    const wrapper = mountCard({ title: 'T', delay: '0.3s' })
    expect(wrapper.get('.el-card').attributes('style')).toContain('animation-delay: 0.3s')
  })

  it('带 section-card 与 page-block 两个类（间距与入场都在全局样式里）', () => {
    const wrapper = mountCard({ title: 'T' })
    expect(wrapper.get('.el-card').classes()).toEqual(
      expect.arrayContaining(['section-card', 'page-block']),
    )
  })
})
