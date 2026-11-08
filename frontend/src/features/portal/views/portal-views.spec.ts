import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => {
  const emptyPage = () => ({ list: [], total: 0, page: 1, size: 10 })
  return {
    emptyPage,
    portalAuthenticated: vi.fn(() => false),
    portalLogin: vi.fn(),
    portalLogout: vi.fn(),
    portalUser: { value: null },
    acceptInvitation: vi.fn(),
    listPortalAuctions: vi.fn(),
    listPortalRfqs: vi.fn(),
    getPortalAuction: vi.fn(),
    getPortalAuctionBids: vi.fn(),
    getPortalAuctionState: vi.fn(),
    getPortalAuctionResult: vi.fn(),
    submitPortalBid: vi.fn(),
    getPortalRfq: vi.fn(),
    submitPortalQuote: vi.fn(),
    withdrawPortalQuote: vi.fn(),
  }
})

vi.mock('@/features/portal/api', () => mocks)

import PortalAuctionListView from './PortalAuctionListView.vue'
import PortalLoginView from './PortalLoginView.vue'
import PortalRfqListView from './PortalRfqListView.vue'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/portal/login', component: { template: '<div />' } },
    { path: '/portal/auctions', component: { template: '<div />' } },
    { path: '/portal/rfqs', component: { template: '<div />' } },
  ],
})

beforeEach(async () => {
  const page = mocks.emptyPage()
  mocks.listPortalAuctions.mockResolvedValue(page)
  mocks.listPortalRfqs.mockResolvedValue(page)
  mocks.portalAuthenticated.mockReturnValue(false)
  await router.push('/portal/login')
  await router.isReady()
})

function mountView(component: Parameters<typeof mount>[0]) {
  return mount(component, {
    global: { plugins: [ElementPlus, createPinia(), router] },
  })
}

describe('供应商门户页面挂载', () => {
  it('登录页渲染门户标题与登录表单', async () => {
    const wrapper = mountView(PortalLoginView)
    await flushPromises()
    expect(wrapper.text()).toContain('供应商门户')
    expect(wrapper.text()).toContain('登录')
    wrapper.unmount()
  })

  it('竞价列表调用门户竞价接口并渲染空态', async () => {
    await router.push('/portal/auctions')
    const wrapper = mountView(PortalAuctionListView)
    await flushPromises()
    await flushPromises()
    expect(mocks.listPortalAuctions).toHaveBeenCalledWith(1, 10, undefined)
    expect(wrapper.text()).toContain('我的竞价')
    wrapper.unmount()
  })

  it('询价列表调用门户 RFQ 接口并渲染空态', async () => {
    await router.push('/portal/rfqs')
    const wrapper = mountView(PortalRfqListView)
    await flushPromises()
    await flushPromises()
    expect(mocks.listPortalRfqs).toHaveBeenCalledWith(1, 10, undefined)
    expect(wrapper.text()).toContain('我的询价')
    wrapper.unmount()
  })
})
