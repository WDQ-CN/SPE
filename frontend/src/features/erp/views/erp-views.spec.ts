import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => {
  const emptyPage = () => ({ list: [], total: 0, page: 1, size: 10 })
  const fn = () => vi.fn()
  return {
    emptyPage,
    listCompanies: fn(),
    listPlants: fn(),
    listWarehouses: fn(),
    listLocations: fn(),
    listReservations: fn(),
    createReservation: fn(),
    releaseReservation: fn(),
    consumeReservation: fn(),
    expireReservations: fn(),
    listFreezes: fn(),
    createFreeze: fn(),
    releaseFreeze: fn(),
    listTransfers: fn(),
    getTransfer: fn(),
    createTransfer: fn(),
    shipTransfer: fn(),
    receiveTransfer: fn(),
    cancelTransfer: fn(),
    listCounts: fn(),
    getCount: fn(),
    createCount: fn(),
    recordCounts: fn(),
    submitCount: fn(),
    approveCount: fn(),
    postCount: fn(),
    cancelCount: fn(),
    listAuctions: fn(),
    getAuction: fn(),
    createAuction: fn(),
    startAuction: fn(),
    closeAuction: fn(),
    listAuctionResults: fn(),
    awardAuction: fn(),
    approveAuctionResult: fn(),
    auctionResultToPo: fn(),
    listContracts: fn(),
    getContract: fn(),
    createContract: fn(),
    activateContract: fn(),
    terminateContract: fn(),
    releaseContractOrder: fn(),
    listContractConsumption: fn(),
    listSupplierPrices: fn(),
    createSupplierPrice: fn(),
    listRfqs: fn(),
    getRfq: fn(),
    createRfq: fn(),
    inviteRfq: fn(),
    sendRfq: fn(),
    closeRfq: fn(),
    cancelRfq: fn(),
    listRfqQuotes: fn(),
    rfqComparison: fn(),
    listRfqAwards: fn(),
    createAward: fn(),
    approveAward: fn(),
    awardToPo: fn(),
    pageCompanies: fn(),
    pagePlants: fn(),
    pageWarehouses: fn(),
    pageItems: fn(),
    pageCustomers: fn(),
    createCompany: fn(),
    updateCompany: fn(),
    createPlant: fn(),
    updatePlant: fn(),
    createWarehouse: fn(),
    updateWarehouse: fn(),
    createItem: fn(),
    updateItem: fn(),
    createCustomer: fn(),
    updateCustomer: fn(),
    changeMasterDataStatus: fn(),
    listAssets: fn(),
    getAsset: fn(),
    createAsset: fn(),
    capitalizeAsset: fn(),
    depreciateAsset: fn(),
    disposeAsset: fn(),
    listProjects: fn(),
    getProject: fn(),
    createProject: fn(),
    approveProject: fn(),
    activateProject: fn(),
    closeProject: fn(),
    addProjectWbs: fn(),
    createProjectBudget: fn(),
    approveProjectBudget: fn(),
    listTimesheets: fn(),
    getTimesheet: fn(),
    createTimesheet: fn(),
    submitTimesheet: fn(),
    approveTimesheet: fn(),
    listRequisitions: fn(),
    getRequisition: fn(),
    createRequisition: fn(),
    updateRequisition: fn(),
    submitRequisition: fn(),
    approveRequisition: fn(),
    rejectRequisition: fn(),
    convertRequisition: fn(),
    listGoodsReceipts: fn(),
    getGoodsReceipt: fn(),
    createGoodsReceipt: fn(),
    reverseGoodsReceipt: fn(),
    listSupplierInvoices: fn(),
    getSupplierInvoice: fn(),
    createSupplierInvoice: fn(),
    rematchInvoice: fn(),
    approveInvoice: fn(),
    listPayments: fn(),
    getPayment: fn(),
    createPayment: fn(),
    approvePayment: fn(),
    payPayment: fn(),
    listShipments: fn(),
    getShipment: fn(),
    createShipment: fn(),
    listSalesInvoices: fn(),
    getSalesInvoice: fn(),
    createSalesInvoice: fn(),
    listCustomerReceipts: fn(),
    getCustomerReceipt: fn(),
    createCustomerReceipt: fn(),
    listSalesReturns: fn(),
    getSalesReturn: fn(),
    createSalesReturn: fn(),
    creditSalesReturn: fn(),
    listCustomers: fn(),
    listItems: fn(),
    listSuppliers: fn(),
    listBoms: fn(),
    listInventoryStock: fn(),
    inventoryValuation: fn(),
    listSalesOrders: fn(),
    getSalesOrder: fn(),
    createSalesOrder: fn(),
    confirmSalesOrder: fn(),
    cancelSalesOrder: fn(),
    listPurchaseOrders: fn(),
    getPurchaseOrder: fn(),
    approvePurchaseOrder: fn(),
    rejectPurchaseOrder: fn(),
    sendPurchaseOrder: fn(),
    cancelPurchaseOrder: fn(),
    pageBoms: fn(),
    getBom: fn(),
    createBom: fn(),
    activateBom: fn(),
    issueWorkOrder: fn(),
    listWorkOrders: fn(),
    getWorkOrder: fn(),
    createWorkOrder: fn(),
    releaseWorkOrder: fn(),
    receiptWorkOrder: fn(),
    closeWorkOrder: fn(),
    pageInspectionPlans: fn(),
    createInspectionPlan: fn(),
    activateInspectionPlan: fn(),
    pageCertificates: fn(),
    expiringCertificates: fn(),
    createCertificate: fn(),
    listInspectionOrders: fn(),
    getInspectionPlan: fn(),
    getInspectionOrder: fn(),
    recordInspectionResults: fn(),
    listNcrs: fn(),
    createNcr: fn(),
    disposeNcr: fn(),
    closeNcr: fn(),
    listCapas: fn(),
    createCapa: fn(),
    verifyCapa: fn(),
    closeCapa: fn(),
    listReportDefinitions: fn(),
    listReportRuns: fn(),
    runReport: fn(),
    downloadReportRun: fn(),
  }
})

vi.mock('@/features/erp/api', () => mocks)

import InventoryOperationsView from './InventoryOperationsView.vue'
import InventoryStockView from './InventoryStockView.vue'
import AssetsProjectsView from './AssetsProjectsView.vue'
import BomMaintenanceView from './BomMaintenanceView.vue'
import MasterDataView from './MasterDataView.vue'
import SourcingView from './SourcingView.vue'
import ProcurementExecutionView from './ProcurementExecutionView.vue'
import SalesExecutionView from './SalesExecutionView.vue'
import PurchaseOrderListView from './PurchaseOrderListView.vue'
import QualityCenterView from './QualityCenterView.vue'
import ReportCenterView from './ReportCenterView.vue'
import SalesOrderListView from './SalesOrderListView.vue'
import WorkOrderListView from './WorkOrderListView.vue'

const testRouter = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
})

const views = [
  ['库存存量', InventoryStockView],
  ['销售订单', SalesOrderListView],
  ['采购订单', PurchaseOrderListView],
  ['生产工单', WorkOrderListView],
  ['质量管理', QualityCenterView],
  ['报表中心', ReportCenterView],
] as const

beforeEach(() => {
  const page = mocks.emptyPage()
  mocks.listCompanies.mockResolvedValue([
    { id: 1, code: 'C1', name: '公司一', baseCurrency: 'CNY', status: 'ACTIVE' },
  ])
  mocks.listPlants.mockResolvedValue([])
  mocks.listWarehouses.mockResolvedValue([])
  mocks.listLocations.mockResolvedValue([])
  mocks.listReservations.mockResolvedValue(page)
  mocks.listFreezes.mockResolvedValue(page)
  mocks.listTransfers.mockResolvedValue(page)
  mocks.listCounts.mockResolvedValue(page)
  mocks.listCustomers.mockResolvedValue([])
  mocks.listItems.mockResolvedValue([])
  mocks.listSuppliers.mockResolvedValue([])
  mocks.listBoms.mockResolvedValue([])
  mocks.listRfqs.mockResolvedValue(page)
  mocks.listAuctions.mockResolvedValue(page)
  mocks.listContracts.mockResolvedValue(page)
  mocks.listSupplierPrices.mockResolvedValue([])
  mocks.pageCompanies.mockResolvedValue(page)
  mocks.pagePlants.mockResolvedValue(page)
  mocks.pageWarehouses.mockResolvedValue(page)
  mocks.pageItems.mockResolvedValue(page)
  mocks.pageCustomers.mockResolvedValue(page)
  mocks.listAssets.mockResolvedValue(page)
  mocks.listProjects.mockResolvedValue(page)
  mocks.listTimesheets.mockResolvedValue(page)
  mocks.listRequisitions.mockResolvedValue(page)
  mocks.listGoodsReceipts.mockResolvedValue(page)
  mocks.listSupplierInvoices.mockResolvedValue(page)
  mocks.listPayments.mockResolvedValue(page)
  mocks.listShipments.mockResolvedValue(page)
  mocks.listSalesInvoices.mockResolvedValue(page)
  mocks.listCustomerReceipts.mockResolvedValue(page)
  mocks.listSalesReturns.mockResolvedValue(page)
  mocks.listInventoryStock.mockResolvedValue(page)
  mocks.listSalesOrders.mockResolvedValue(page)
  mocks.listPurchaseOrders.mockResolvedValue(page)
  mocks.listWorkOrders.mockResolvedValue(page)
  mocks.pageBoms.mockResolvedValue(page)
  mocks.listInspectionOrders.mockResolvedValue(page)
  mocks.pageInspectionPlans.mockResolvedValue(page)
  mocks.pageCertificates.mockResolvedValue(page)
  mocks.expiringCertificates.mockResolvedValue([])
  mocks.listNcrs.mockResolvedValue(page)
  mocks.listCapas.mockResolvedValue(page)
  mocks.listReportDefinitions.mockResolvedValue([])
  mocks.listReportRuns.mockResolvedValue(page)
})

describe('ERP 首版页面挂载', () => {
  it.each(views)('%s 能加载主数据并渲染标题', async (title, component) => {
    const wrapper = mount(component, {
      global: { plugins: [ElementPlus, createPinia(), testRouter] },
    })
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain(title)
    if (title === '质量管理') {
      expect(wrapper.text()).toContain('检验计划')
      expect(wrapper.text()).toContain('供应商证书')
    }
    wrapper.unmount()
  })

  it('资产与项目页渲染固定资产/项目/工时三个页签', async () => {
    const wrapper = mount(AssetsProjectsView, {
      global: { plugins: [ElementPlus, createPinia(), testRouter] },
    })
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('资产与项目')
    expect(wrapper.text()).toContain('固定资产')
    expect(wrapper.text()).toContain('项目')
    expect(wrapper.text()).toContain('工时')
    wrapper.unmount()
  })

  it('BOM 维护页渲染列表与新建入口', async () => {
    const wrapper = mount(BomMaintenanceView, {
      global: { plugins: [ElementPlus, createPinia(), testRouter] },
    })
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('BOM 维护')
    expect(wrapper.text()).toContain('新建 BOM')
    wrapper.unmount()
  })

  it('寻源页渲染 RFQ 列表与新建入口', async () => {
    const wrapper = mount(SourcingView, {
      global: { plugins: [ElementPlus, createPinia(), testRouter] },
    })
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('寻源')
    expect(wrapper.text()).toContain('新建 RFQ')
    expect(wrapper.text()).toContain('竞价')
    expect(wrapper.text()).toContain('合同 / 框架协议')
    expect(wrapper.text()).toContain('供应商价格库')
    wrapper.unmount()
  })

  it('主数据页渲染公司/工厂/仓库/物料/客户五个切换项', async () => {
    const wrapper = mount(MasterDataView, {
      global: { plugins: [ElementPlus, createPinia(), testRouter] },
    })
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('主数据')
    expect(wrapper.text()).toContain('公司')
    expect(wrapper.text()).toContain('工厂')
    expect(wrapper.text()).toContain('仓库')
    expect(wrapper.text()).toContain('物料')
    expect(wrapper.text()).toContain('客户')
    wrapper.unmount()
  })

  it('销售执行页渲染发货、发票、收款与退货四个页签', async () => {
    const wrapper = mount(SalesExecutionView, {
      global: { plugins: [ElementPlus, createPinia(), testRouter] },
    })
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('销售执行')
    expect(wrapper.text()).toContain('发货')
    expect(wrapper.text()).toContain('销售发票')
    expect(wrapper.text()).toContain('收款')
    expect(wrapper.text()).toContain('退货')
    wrapper.unmount()
  })

  it('采购执行页渲染收货、发票与付款三个页签', async () => {
    const wrapper = mount(ProcurementExecutionView, {
      global: { plugins: [ElementPlus, createPinia(), testRouter] },
    })
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('采购执行')
    expect(wrapper.text()).toContain('请购')
    expect(wrapper.text()).toContain('收货')
    expect(wrapper.text()).toContain('供应商发票')
    expect(wrapper.text()).toContain('付款')
    wrapper.unmount()
  })

  it('库存作业页渲染预留/冻结、调拨与盘点三个页签', async () => {
    const wrapper = mount(InventoryOperationsView, {
      global: { plugins: [ElementPlus, createPinia(), testRouter] },
    })
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('库存作业')
    expect(wrapper.text()).toContain('预留 / 冻结')
    expect(wrapper.text()).toContain('调拨')
    expect(wrapper.text()).toContain('盘点')
    wrapper.unmount()
  })

  it('库存页把公司与仓库筛选传给库存接口', async () => {
    const wrapper = mount(InventoryStockView, {
      global: { plugins: [ElementPlus, createPinia(), testRouter] },
    })
    await flushPromises()
    await flushPromises()
    expect(mocks.listInventoryStock).toHaveBeenCalledWith(
      expect.objectContaining({ page: 1, size: 10, companyId: 1 }),
    )
    wrapper.unmount()
  })
})
