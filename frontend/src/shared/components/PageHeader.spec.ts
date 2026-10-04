import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import PageHeader from './PageHeader.vue'

/**
 * 通用页头（`03 §35` 登记"没有单测"的组件之一）。
 *
 * <p>它是纯展示组件，价值不在"渲染出来了"，而在两点容易被改坏的契约：
 * ① `delay` 的默认值必须是 `0s`——页头是每页的**第一拍**，若默认变成别的值，
 *    全站页头的入场时机都会跟着挪（而它散在 10 个页面里，不会有人逐个去看）；
 * ② eyebrow/title 的文本必须原样落到 DOM——它们承载的是页面身份，错了是"看不出哪里不对"的那种错。
 */
describe('PageHeader（通用页头）', () => {
  it('eyebrow 与 title 原样渲染', () => {
    const wrapper = mount(PageHeader, {
      props: { eyebrow: 'SUPPLIER MASTER DATA', title: '供应商档案' },
    })
    expect(wrapper.get('.page-eyebrow').text()).toBe('SUPPLIER MASTER DATA')
    expect(wrapper.get('.page-title').text()).toBe('供应商档案')
  })

  it('delay 默认 0s：不传时页头不延迟（它是每页的第一拍）', () => {
    const wrapper = mount(PageHeader, {
      props: { eyebrow: 'X', title: 'Y' },
    })
    expect(wrapper.get('.page-head').attributes('style')).toContain('animation-delay: 0s')
  })

  it('传 delay 时写进内联样式（用于同页多区块排阶梯）', () => {
    const wrapper = mount(PageHeader, {
      props: { eyebrow: 'X', title: 'Y', delay: '0.25s' },
    })
    expect(wrapper.get('.page-head').attributes('style')).toContain('animation-delay: 0.25s')
  })

  it('带入场类 page-block（结构与样式分工：类名在 base.css，组件只给结构）', () => {
    const wrapper = mount(PageHeader, {
      props: { eyebrow: 'X', title: 'Y' },
    })
    expect(wrapper.get('header').classes()).toContain('page-block')
  })
})
