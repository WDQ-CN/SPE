import { downloadFile, http } from '@/app/http'
import type { PageResult } from '@/shared/types/result'
import type { SupplierSummary } from '@/shared/types/dto'

/** ERP 交易与查询接口（docs/erp/31~39、49）。端点均要求 ADMIN/MANAGER。 */

export interface PageParams {
  page: number
  size: number
}

// ---- 主数据选项 ----

export interface CompanyOption {
  id: number
  code: string
  name: string
  baseCurrency: string
  status: string
}

export interface PlantOption {
  id: number
  companyId: number
  code: string
  name: string
  status: string
}

export interface WarehouseOption {
  id: number
  plantId: number
  code: string
  name: string
  status: string
}

export interface LocationOption {
  id: number
  warehouseId: number
  code: string
  name: string
  status: string
}

export interface CustomerOption {
  id: number
  code: string
  name: string
  currency: string
  status: string
}

export interface ItemOption {
  id: number
  code: string
  name: string
  baseUom: string
  itemType: string
  status: string
}

export interface BomOption {
  id: number
  bomNo: string
  companyId: number
  itemId: number
  version: string
  status: string
}

function listOptions<T>(url: string, params: object = {}): Promise<T[]> {
  return http
    .get<PageResult<T>>(url, { page: 1, size: 200, ...params })
    .then((page) => page.list)
}

export const listCompanies = () => listOptions<CompanyOption>('/masterdata/companies')
export const listPlants = (companyId?: number) =>
  listOptions<PlantOption>('/masterdata/plants', companyId ? { companyId } : {})
export const listWarehouses = (plantId?: number) =>
  listOptions<WarehouseOption>('/masterdata/warehouses', plantId ? { plantId } : {})
export const listLocations = (warehouseId: number) =>
  http.get<LocationOption[]>(`/masterdata/warehouses/${warehouseId}/locations`, {
    status: 'ACTIVE',
  })
export const listCustomers = () => listOptions<CustomerOption>('/masterdata/customers')
export const listItems = () => listOptions<ItemOption>('/masterdata/items')
export const listSuppliers = () => listOptions<SupplierSummary>('/suppliers')
export const listBoms = (companyId: number) =>
  listOptions<BomOption>('/manufacturing/boms', { companyId })

// ---- 主数据维护 ----

export interface MasterDataQuery extends PageParams {
  keyword?: string
  status?: string
}

export interface CompanyRecord {
  id: number
  code: string
  name: string
  baseCurrency: string
  taxNo: string | null
  country: string
  status: string
  version: number
}

export interface CompanyPayload {
  code: string
  name: string
  baseCurrency: string
  taxNo: string | null
  country: string
}

export interface PlantRecord {
  id: number
  companyId: number
  code: string
  name: string
  address: string | null
  status: string
  version: number
}

export interface PlantPayload {
  companyId: number
  code: string
  name: string
  address: string | null
}

export interface WarehouseRecord {
  id: number
  plantId: number
  code: string
  name: string
  warehouseType: string
  status: string
  version: number
}

export interface WarehousePayload {
  plantId: number
  code: string
  name: string
  warehouseType: string
}

export interface ItemRecord {
  id: number
  code: string
  name: string
  spec: string | null
  itemType: string
  baseUom: string
  lotControlled: boolean
  serialControlled: boolean
  valuationMethod: string
  standardCost: number | null
  status: string
  version: number
}

export interface ItemPayload {
  code: string
  name: string
  spec: string | null
  itemType: string
  baseUom: string
  lotControlled: boolean
  serialControlled: boolean
  valuationMethod: string
  standardCost: number | null
}

export interface CustomerRecord {
  id: number
  code: string
  name: string
  taxNo: string | null
  address: string | null
  contactName: string | null
  contactPhone: string | null
  currency: string
  paymentTerms: string | null
  creditLimit: number | null
  creditUsed: number
  status: string
  version: number
}

export interface CustomerPayload {
  code: string
  name: string
  taxNo: string | null
  address: string | null
  contactName: string | null
  contactPhone: string | null
  currency: string
  paymentTerms: string | null
  creditLimit: number | null
}

export function pageCompanies(query: MasterDataQuery): Promise<PageResult<CompanyRecord>> {
  return http.get<PageResult<CompanyRecord>>('/masterdata/companies', query)
}

export function createCompany(payload: CompanyPayload): Promise<CompanyRecord> {
  return http.post<CompanyRecord>('/masterdata/companies', payload)
}

export function updateCompany(
  id: number,
  payload: CompanyPayload & { version: number },
): Promise<CompanyRecord> {
  return http.put<CompanyRecord>(`/masterdata/companies/${id}`, payload)
}

export function pagePlants(
  query: MasterDataQuery & { companyId?: number },
): Promise<PageResult<PlantRecord>> {
  return http.get<PageResult<PlantRecord>>('/masterdata/plants', query)
}

export function createPlant(payload: PlantPayload): Promise<PlantRecord> {
  return http.post<PlantRecord>('/masterdata/plants', payload)
}

export function updatePlant(
  id: number,
  payload: Omit<PlantPayload, 'companyId'> & { version: number },
): Promise<PlantRecord> {
  return http.put<PlantRecord>(`/masterdata/plants/${id}`, payload)
}

export function pageWarehouses(
  query: MasterDataQuery & { plantId?: number },
): Promise<PageResult<WarehouseRecord>> {
  return http.get<PageResult<WarehouseRecord>>('/masterdata/warehouses', query)
}

export function createWarehouse(payload: WarehousePayload): Promise<WarehouseRecord> {
  return http.post<WarehouseRecord>('/masterdata/warehouses', payload)
}

export function updateWarehouse(
  id: number,
  payload: Omit<WarehousePayload, 'plantId'> & { version: number },
): Promise<WarehouseRecord> {
  return http.put<WarehouseRecord>(`/masterdata/warehouses/${id}`, payload)
}

export function pageItems(
  query: MasterDataQuery & { itemType?: string },
): Promise<PageResult<ItemRecord>> {
  return http.get<PageResult<ItemRecord>>('/masterdata/items', query)
}

export function createItem(payload: ItemPayload): Promise<ItemRecord> {
  return http.post<ItemRecord>('/masterdata/items', payload)
}

export function updateItem(
  id: number,
  payload: ItemPayload & { version: number },
): Promise<ItemRecord> {
  return http.put<ItemRecord>(`/masterdata/items/${id}`, payload)
}

export function pageCustomers(query: MasterDataQuery): Promise<PageResult<CustomerRecord>> {
  return http.get<PageResult<CustomerRecord>>('/masterdata/customers', query)
}

export function createCustomer(payload: CustomerPayload): Promise<CustomerRecord> {
  return http.post<CustomerRecord>('/masterdata/customers', payload)
}

export function updateCustomer(
  id: number,
  payload: CustomerPayload & { version: number },
): Promise<CustomerRecord> {
  return http.put<CustomerRecord>(`/masterdata/customers/${id}`, payload)
}

export function changeMasterDataStatus(
  resource: 'companies' | 'plants' | 'warehouses' | 'items' | 'customers',
  id: number,
  status: string,
  version: number,
): Promise<void> {
  return http.put<void>(`/masterdata/${resource}/${id}/status`, { status, version })
}

// ---- 库存 ----

export interface InventoryStockQuery extends PageParams {
  companyId?: number
  warehouseId?: number
  itemId?: number
}

export interface InventoryStockRow {
  id: number
  companyId: number
  companyName: string
  plantId: number
  plantName: string
  warehouseId: number
  warehouseName: string
  itemId: number
  itemCode: string
  itemName: string
  lotId: number
  lotNo: string
  locationId: number
  locationCode: string
  locationName: string
  uom: string
  qtyOnHand: number
  qtyReserved: number
  qtyFrozen: number
  availableQty: number
  totalValue: number
  avgUnitCost: number
  version: number
}

export interface InventoryValuationRow {
  warehouseId: number
  itemId: number
  itemCode: string
  itemName: string
  lotId: number
  lotNo: string
  method: string
  qty: number
  value: number
}

export interface InventoryValuationReport {
  companyId: number
  warehouseId: number | null
  rows: InventoryValuationRow[]
  totalValue: number
}

export function listInventoryStock(
  query: InventoryStockQuery,
): Promise<PageResult<InventoryStockRow>> {
  return http.get<PageResult<InventoryStockRow>>('/inventory/stock', query)
}

export function inventoryValuation(
  companyId: number,
  warehouseId?: number,
): Promise<InventoryValuationReport> {
  return http.get<InventoryValuationReport>('/inventory/reports/valuation', {
    companyId,
    warehouseId,
  })
}

// ---- 库存作业：预留、冻结、调拨、盘点 ----

export interface InventoryReservationRow {
  id: number
  companyId: number
  warehouseId: number
  locationId: number
  itemId: number
  lotId: number
  serialId: number
  qty: number
  sourceType: string
  sourceId: number
  sourceLineId: number | null
  status: string
  expiresAt: string | null
  createdAt: string
}

export interface InventoryFreezeRow {
  id: number
  companyId: number
  warehouseId: number
  locationId: number
  itemId: number
  lotId: number
  serialId: number
  qty: number
  reason: string
  sourceType: string
  sourceId: number
  status: string
  createdAt: string
}

export interface AllocationQuery extends PageParams {
  warehouseId?: number
  itemId?: number
  status?: string
}

export interface CreateReservationPayload {
  companyId: number
  warehouseId: number
  locationId: number | null
  itemId: number
  lotId: number | null
  serialId: number | null
  qty: number
  sourceType: string
  sourceId: number
  sourceLineId: number | null
  expiresAt: string | null
}

export interface CreateFreezePayload {
  companyId: number
  warehouseId: number
  locationId: number | null
  itemId: number
  lotId: number | null
  serialId: number | null
  qty: number
  reason: string
  sourceType: string
  sourceId: number
}

export function listReservations(
  query: AllocationQuery,
): Promise<PageResult<InventoryReservationRow>> {
  return http.get<PageResult<InventoryReservationRow>>('/inventory/reservations', query)
}

export function createReservation(
  payload: CreateReservationPayload,
): Promise<InventoryReservationRow> {
  return http.post<InventoryReservationRow>('/inventory/reservations', payload)
}

export function releaseReservation(id: number): Promise<InventoryReservationRow> {
  return http.post<InventoryReservationRow>(`/inventory/reservations/${id}/release`)
}

export function consumeReservation(id: number): Promise<InventoryReservationRow> {
  return http.post<InventoryReservationRow>(`/inventory/reservations/${id}/consume`)
}

export function expireReservations(): Promise<number> {
  return http.post<number>('/inventory/reservations/expire')
}

export function listFreezes(query: AllocationQuery): Promise<PageResult<InventoryFreezeRow>> {
  return http.get<PageResult<InventoryFreezeRow>>('/inventory/freezes', query)
}

export function createFreeze(payload: CreateFreezePayload): Promise<InventoryFreezeRow> {
  return http.post<InventoryFreezeRow>('/inventory/freezes', payload)
}

export function releaseFreeze(id: number): Promise<InventoryFreezeRow> {
  return http.post<InventoryFreezeRow>(`/inventory/freezes/${id}/release`)
}

export interface TransferQuery extends PageParams {
  companyId?: number
  status?: string
}

export interface InventoryTransferRow {
  id: number
  transferNo: string
  companyId: number
  fromPlantId: number
  fromWarehouseId: number
  fromLocationId: number
  toPlantId: number
  toWarehouseId: number
  toLocationId: number
  status: string
  reason: string | null
  shippedAt: string | null
  receivedAt: string | null
  version: number
  createdAt: string
}

export interface InventoryTransferLine {
  id: number
  lineNo: number
  itemId: number
  lotId: number
  qty: number
  uom: string
  unitCost: number
  amount: number
  serialIds: number[]
}

export interface InventoryTransferDetail extends InventoryTransferRow {
  lines: InventoryTransferLine[]
}

export interface TransferLinePayload {
  itemId: number
  lotId: number | null
  qty: number
  uom: string
  unitCost: number | null
  serialIds: number[]
}

export interface CreateTransferPayload {
  companyId: number
  fromWarehouseId: number
  fromLocationId: number | null
  toWarehouseId: number
  toLocationId: number | null
  reason: string | null
  lines: TransferLinePayload[]
}

export function listTransfers(query: TransferQuery): Promise<PageResult<InventoryTransferRow>> {
  return http.get<PageResult<InventoryTransferRow>>('/inventory/transfers', query)
}

export function getTransfer(id: number): Promise<InventoryTransferDetail> {
  return http.get<InventoryTransferDetail>(`/inventory/transfers/${id}`)
}

export function createTransfer(payload: CreateTransferPayload): Promise<InventoryTransferDetail> {
  return http.post<InventoryTransferDetail>('/inventory/transfers', payload)
}

export function shipTransfer(id: number): Promise<InventoryTransferDetail> {
  return http.post<InventoryTransferDetail>(`/inventory/transfers/${id}/ship`)
}

export function receiveTransfer(id: number): Promise<InventoryTransferDetail> {
  return http.post<InventoryTransferDetail>(`/inventory/transfers/${id}/receive`)
}

export function cancelTransfer(id: number): Promise<InventoryTransferDetail> {
  return http.post<InventoryTransferDetail>(`/inventory/transfers/${id}/cancel`)
}

export interface CountQuery extends PageParams {
  companyId?: number
  warehouseId?: number
  status?: string
}

export interface InventoryCountRow {
  id: number
  countNo: string
  companyId: number
  warehouseId: number
  status: string
  snapshotAt: string
  reason: string | null
  version: number
  createdAt: string
}

export interface InventoryCountLine {
  id: number
  lineNo: number
  itemId: number
  lotId: number
  locationId: number
  snapshotQty: number
  countedQty: number | null
  varianceQty: number | null
  reason: string | null
}

export interface InventoryCountDetail extends InventoryCountRow {
  lines: InventoryCountLine[]
}

export interface CreateCountPayload {
  companyId: number
  warehouseId: number
  itemId: number | null
  lotId: number | null
  locationId: number | null
  reason: string | null
}

export interface CountLinePayload {
  lineId: number
  countedQty: number
  reason: string | null
}

export function listCounts(query: CountQuery): Promise<PageResult<InventoryCountRow>> {
  return http.get<PageResult<InventoryCountRow>>('/inventory/counts', query)
}

export function getCount(id: number): Promise<InventoryCountDetail> {
  return http.get<InventoryCountDetail>(`/inventory/counts/${id}`)
}

export function createCount(payload: CreateCountPayload): Promise<InventoryCountDetail> {
  return http.post<InventoryCountDetail>('/inventory/counts', payload)
}

export function recordCounts(
  id: number,
  lines: CountLinePayload[],
): Promise<InventoryCountDetail> {
  return http.put<InventoryCountDetail>(`/inventory/counts/${id}/lines`, { lines })
}

export function submitCount(id: number): Promise<InventoryCountDetail> {
  return http.post<InventoryCountDetail>(`/inventory/counts/${id}/submit`)
}

export function approveCount(id: number): Promise<InventoryCountDetail> {
  return http.post<InventoryCountDetail>(`/inventory/counts/${id}/approve`)
}

export function postCount(id: number): Promise<InventoryCountDetail> {
  return http.post<InventoryCountDetail>(`/inventory/counts/${id}/post`)
}

export function cancelCount(id: number): Promise<InventoryCountDetail> {
  return http.post<InventoryCountDetail>(`/inventory/counts/${id}/cancel`)
}

// ---- 销售订单 ----

export interface SalesOrderQuery extends PageParams {
  companyId?: number
  customerId?: number
  status?: string
}

export interface SalesOrderRow {
  id: number
  orderNo: string
  companyId: number
  customerId: number
  sourceQuoteId: number | null
  currency: string
  paymentTerms: string | null
  status: string
  netAmount: number
  taxAmount: number
  totalAmount: number
  version: number
  createdAt: string
  confirmedAt: string | null
}

export interface SalesOrderLine {
  id: number
  lineNo: number
  itemId: number
  itemCode: string
  itemName: string
  qty: number
  uom: string
  unitPrice: number
  discountRate: number
  taxRate: number
  netAmount: number
  taxAmount: number
  shippedQty: number
  invoicedQty: number
}

export interface SalesOrderDetail {
  id: number
  orderNo: string
  companyId: number
  customerId: number
  customerCode: string
  customerName: string
  sourceQuoteId: number | null
  currency: string
  paymentTerms: string | null
  status: string
  netAmount: number
  taxAmount: number
  totalAmount: number
  version: number
  createdAt: string
  confirmedAt: string | null
  lines: SalesOrderLine[]
}

export interface SalesLinePayload {
  itemId: number
  qty: number
  uom: string
  unitPrice: number
  discountRate: number
  taxRate: number
}

export interface CreateSalesOrderPayload {
  companyId: number
  customerId: number
  currency: string
  paymentTerms: string | null
  lines: SalesLinePayload[]
}

export function listSalesOrders(query: SalesOrderQuery): Promise<PageResult<SalesOrderRow>> {
  return http.get<PageResult<SalesOrderRow>>('/sales/orders', query)
}

export function getSalesOrder(id: number): Promise<SalesOrderDetail> {
  return http.get<SalesOrderDetail>(`/sales/orders/${id}`)
}

export function createSalesOrder(payload: CreateSalesOrderPayload): Promise<SalesOrderDetail> {
  return http.post<SalesOrderDetail>('/sales/orders', payload)
}

export function confirmSalesOrder(id: number): Promise<SalesOrderDetail> {
  return http.post<SalesOrderDetail>(`/sales/orders/${id}/confirm`)
}

export function cancelSalesOrder(id: number): Promise<SalesOrderDetail> {
  return http.post<SalesOrderDetail>(`/sales/orders/${id}/cancel`)
}

// ---- 销售执行：发货、发票、收款、退货 ----

export interface ShipmentQuery extends PageParams {
  companyId?: number
  orderId?: number
  status?: string
}

export interface ShipmentRow {
  id: number
  shipmentNo: string
  companyId: number
  customerId: number
  orderId: number
  warehouseId: number
  currency: string
  status: string
  shippedAt: string
  createdBy: number
  version: number
  createdAt: string
}

export interface ShipmentLine {
  id: number
  lineNo: number
  orderLineId: number
  itemId: number
  lotNo: string | null
  locationId: number
  qty: number
}

export interface ShipmentDetail {
  id: number
  shipmentNo: string
  companyId: number
  customerId: number
  orderId: number
  warehouseId: number
  status: string
  shippedAt: string
  version: number
  lines: ShipmentLine[]
}

export interface ShipmentLinePayload {
  orderLineId: number
  qty: number
  lotNo: string | null
  locationId: number | null
}

export interface CreateShipmentPayload {
  orderId: number
  warehouseId: number
  lines: ShipmentLinePayload[]
}

export function listShipments(query: ShipmentQuery): Promise<PageResult<ShipmentRow>> {
  return http.get<PageResult<ShipmentRow>>('/sales/shipments', query)
}

export function getShipment(id: number): Promise<ShipmentDetail> {
  return http.get<ShipmentDetail>(`/sales/shipments/${id}`)
}

export function createShipment(payload: CreateShipmentPayload): Promise<ShipmentDetail> {
  return http.post<ShipmentDetail>('/sales/shipments', payload)
}

export interface SalesInvoiceQuery extends PageParams {
  companyId?: number
  customerId?: number
  status?: string
}

export interface SalesInvoiceRow {
  id: number
  invoiceNo: string
  companyId: number
  customerId: number
  orderId: number
  currency: string
  status: string
  netAmount: number
  taxAmount: number
  totalAmount: number
  paidAmount: number
  issuedAt: string
  dueDate: string
  remark: string | null
  version: number
  createdBy: number
  createdAt: string
}

export interface SalesInvoiceLine {
  id: number
  lineNo: number
  orderLineId: number
  itemId: number
  itemCode: string
  itemName: string
  qty: number
  unitPrice: number
  discountRate: number
  taxRate: number
  netAmount: number
  taxAmount: number
}

export interface SalesInvoiceDetail {
  id: number
  invoiceNo: string
  companyId: number
  customerId: number
  customerName: string | null
  orderId: number
  currency: string
  status: string
  netAmount: number
  taxAmount: number
  totalAmount: number
  paidAmount: number
  issuedAt: string
  dueDate: string
  remark: string | null
  version: number
  lines: SalesInvoiceLine[]
}

export interface CreateSalesInvoicePayload {
  orderId: number
  dueDate: string
  remark: string | null
  lines: { orderLineId: number; qty: number }[]
}

export function listSalesInvoices(
  query: SalesInvoiceQuery,
): Promise<PageResult<SalesInvoiceRow>> {
  return http.get<PageResult<SalesInvoiceRow>>('/sales/invoices', query)
}

export function getSalesInvoice(id: number): Promise<SalesInvoiceDetail> {
  return http.get<SalesInvoiceDetail>(`/sales/invoices/${id}`)
}

export function createSalesInvoice(
  payload: CreateSalesInvoicePayload,
): Promise<SalesInvoiceDetail> {
  return http.post<SalesInvoiceDetail>('/sales/invoices', payload)
}

export interface ReceiptQuery extends PageParams {
  companyId?: number
  customerId?: number
}

export interface ReceiptRow {
  id: number
  receiptNo: string
  companyId: number
  customerId: number
  currency: string
  amount: number
  method: string | null
  status: string
  receivedAt: string
  version: number
  createdBy: number
  createdAt: string
}

export interface ReceiptAllocation {
  id: number
  invoiceId: number
  invoiceNo: string | null
  amount: number
}

export interface ReceiptDetail {
  id: number
  receiptNo: string
  companyId: number
  customerId: number
  customerName: string | null
  currency: string
  amount: number
  method: string | null
  status: string
  receivedAt: string
  version: number
  allocations: ReceiptAllocation[]
}

export interface CreateReceiptPayload {
  customerId: number
  method: string | null
  allocations: { invoiceId: number; amount: number }[]
}

export function listCustomerReceipts(query: ReceiptQuery): Promise<PageResult<ReceiptRow>> {
  return http.get<PageResult<ReceiptRow>>('/sales/receipts', query)
}

export function getCustomerReceipt(id: number): Promise<ReceiptDetail> {
  return http.get<ReceiptDetail>(`/sales/receipts/${id}`)
}

export function createCustomerReceipt(payload: CreateReceiptPayload): Promise<ReceiptDetail> {
  return http.post<ReceiptDetail>('/sales/receipts', payload)
}

export interface SalesReturnQuery extends PageParams {
  companyId?: number
  customerId?: number
  status?: string
}

export interface SalesReturnRow {
  id: number
  returnNo: string
  companyId: number
  customerId: number
  shipmentId: number
  orderId: number
  currency: string
  status: string
  netAmount: number
  taxAmount: number
  totalAmount: number
  reason: string | null
  creditedAt: string | null
  version: number
  createdBy: number
  createdAt: string
}

export interface SalesReturnLine {
  id: number
  lineNo: number
  shipmentLineId: number
  orderLineId: number
  itemId: number
  qty: number
  unitPrice: number
  discountRate: number
  taxRate: number
  netAmount: number
  taxAmount: number
  reason: string | null
}

export interface SalesReturnDetail {
  id: number
  returnNo: string
  companyId: number
  customerId: number
  customerName: string | null
  shipmentId: number
  orderId: number
  currency: string
  status: string
  netAmount: number
  taxAmount: number
  totalAmount: number
  reason: string | null
  creditedAt: string | null
  version: number
  lines: SalesReturnLine[]
}

export interface CreateSalesReturnPayload {
  shipmentId: number
  reason: string | null
  lines: { shipmentLineId: number; qty: number; reason: string | null }[]
}

export function listSalesReturns(
  query: SalesReturnQuery,
): Promise<PageResult<SalesReturnRow>> {
  return http.get<PageResult<SalesReturnRow>>('/sales/returns', query)
}

export function getSalesReturn(id: number): Promise<SalesReturnDetail> {
  return http.get<SalesReturnDetail>(`/sales/returns/${id}`)
}

export function createSalesReturn(
  payload: CreateSalesReturnPayload,
): Promise<SalesReturnDetail> {
  return http.post<SalesReturnDetail>('/sales/returns', payload)
}

export function creditSalesReturn(id: number): Promise<SalesReturnDetail> {
  return http.post<SalesReturnDetail>(`/sales/returns/${id}/credit`)
}

// ---- 采购订单 ----

export interface PurchaseOrderQuery extends PageParams {
  companyId?: number
  supplierId?: number
  status?: string
  keyword?: string
}

export interface PurchaseOrderRow {
  id: number
  docNo: string
  companyId: number
  companyName: string
  supplierId: number
  supplierName: string
  plantId: number
  plantName: string
  currency: string
  amount: number
  status: string
  version: number
  createdAt: string
}

export interface PurchaseOrderLine {
  id: number
  lineNo: number
  itemId: number
  itemCode: string
  itemName: string
  qty: number
  uom: string
  price: number
  amount: number
  receivedQty: number
  invoicedQty: number
}

export interface PurchaseOrderDetail {
  id: number
  docNo: string
  companyId: number
  companyName: string
  supplierId: number
  supplierName: string
  plantId: number
  plantName: string
  sourcePrId: number | null
  sourcePrDocNo: string | null
  currency: string
  paymentTerms: string | null
  amount: number
  status: string
  createdBy: number
  createdByName: string
  approvedBy: number | null
  approvedByName: string | null
  approvedAt: string | null
  version: number
  createdAt: string
  lines: PurchaseOrderLine[]
}

export function listPurchaseOrders(
  query: PurchaseOrderQuery,
): Promise<PageResult<PurchaseOrderRow>> {
  return http.get<PageResult<PurchaseOrderRow>>('/procurement/purchase-orders', query)
}

export function getPurchaseOrder(id: number): Promise<PurchaseOrderDetail> {
  return http.get<PurchaseOrderDetail>(`/procurement/purchase-orders/${id}`)
}

export function approvePurchaseOrder(id: number, version: number): Promise<PurchaseOrderDetail> {
  return http.post<PurchaseOrderDetail>(`/procurement/purchase-orders/${id}/approve`, { version })
}

export function rejectPurchaseOrder(
  id: number,
  comment: string,
  version: number,
): Promise<PurchaseOrderDetail> {
  return http.post<PurchaseOrderDetail>(`/procurement/purchase-orders/${id}/reject`, {
    comment,
    version,
  })
}

export function sendPurchaseOrder(id: number, version: number): Promise<PurchaseOrderDetail> {
  return http.post<PurchaseOrderDetail>(`/procurement/purchase-orders/${id}/send`, { version })
}

export function cancelPurchaseOrder(id: number, version: number): Promise<PurchaseOrderDetail> {
  return http.post<PurchaseOrderDetail>(`/procurement/purchase-orders/${id}/cancel`, { version })
}

// ---- 寻源：RFQ、报价、比价、定标 ----

export interface RfqQuery extends PageParams {
  companyId?: number
  status?: string
  keyword?: string
}

export interface RfqRow {
  id: number
  rfqNo: string
  companyId: number
  plantId: number
  buyerId: number
  title: string
  currency: string
  deadlineAt: string
  sealed: boolean
  status: string
  sourcePrId: number | null
  awardPolicy: string
  version: number
  createdAt: string
  sentAt: string | null
  closedAt: string | null
}

export interface RfqLine {
  id: number
  lineNo: number
  itemId: number
  itemCode: string
  itemName: string
  qty: number
  uom: string
  needDate: string | null
  targetPrice: number | null
  spec: string | null
}

export interface RfqInvitation {
  id: number
  supplierId: number
  supplierCode: string
  supplierName: string
  status: string
  invitedAt: string
  respondedAt: string | null
}

export interface RfqDetail extends RfqRow {
  lines: RfqLine[]
  invitations: RfqInvitation[]
}

export interface RfqLinePayload {
  itemId: number
  qty: number
  uom: string
  needDate: string | null
  targetPrice: number | null
  spec: string | null
}

export interface CreateRfqPayload {
  companyId: number
  plantId: number
  title: string
  currency: string
  deadlineAt: string
  sealed: boolean
  sourcePrId: number | null
  awardPolicy: string
  lines: RfqLinePayload[]
}

export interface QuoteLine {
  id: number
  rfqLineId: number
  unitPrice: number
  taxRate: number
  taxAmount: number
  leadTimeDays: number | null
  moq: number | null
  qtyAvailable: number | null
  incoterm: string | null
  remark: string | null
}

export interface Quote {
  id: number
  quoteNo: string
  rfqId: number
  supplierId: number
  supplierCode: string
  supplierName: string
  currency: string
  validUntil: string | null
  status: string
  submittedAt: string
  version: number
  lines: QuoteLine[]
}

export interface ComparisonCell {
  quoteId: number
  quoteLineId: number
  supplierId: number
  supplierCode: string
  supplierName: string
  unitPrice: number
  taxRate: number
  taxIncludedPrice: number
  leadTimeDays: number | null
  moq: number | null
  qtyAvailable: number | null
  totalCost: number
  best: boolean
}

export interface ComparisonLine {
  rfqLineId: number
  itemId: number
  qty: number
  bestSupplierId: number | null
  cells: ComparisonCell[]
}

export interface Comparison {
  rfqId: number
  sealedVisible: boolean
  lines: ComparisonLine[]
}

export interface AwardLine {
  id: number
  rfqLineId: number
  quoteLineId: number
  awardedQty: number
  unitPrice: number
  amount: number
}

export interface Award {
  id: number
  awardNo: string
  rfqId: number
  supplierId: number
  quoteId: number
  awardAmount: number
  currency: string
  status: string
  reason: string | null
  approvedBy: number | null
  approvedAt: string | null
  version: number
  createdAt: string
  lines: AwardLine[]
}

export interface CreateAwardPayload {
  supplierId: number
  reason: string | null
  lines: { rfqLineId: number; quoteLineId: number; awardedQty: number }[]
}

export function listRfqs(query: RfqQuery): Promise<PageResult<RfqRow>> {
  return http.get<PageResult<RfqRow>>('/sourcing/rfqs', query)
}

export function getRfq(id: number): Promise<RfqDetail> {
  return http.get<RfqDetail>(`/sourcing/rfqs/${id}`)
}

export function createRfq(payload: CreateRfqPayload): Promise<RfqDetail> {
  return http.post<RfqDetail>('/sourcing/rfqs', payload)
}

export function inviteRfq(id: number, supplierIds: number[]): Promise<RfqDetail> {
  return http.post<RfqDetail>(`/sourcing/rfqs/${id}/invite`, { supplierIds })
}

export function sendRfq(id: number): Promise<RfqDetail> {
  return http.post<RfqDetail>(`/sourcing/rfqs/${id}/send`)
}

export function closeRfq(id: number): Promise<RfqDetail> {
  return http.post<RfqDetail>(`/sourcing/rfqs/${id}/close`)
}

export function cancelRfq(id: number): Promise<RfqDetail> {
  return http.post<RfqDetail>(`/sourcing/rfqs/${id}/cancel`)
}

export function listRfqQuotes(id: number): Promise<Quote[]> {
  return http.get<Quote[]>(`/sourcing/rfqs/${id}/quotes`)
}

export function rfqComparison(id: number): Promise<Comparison> {
  return http.get<Comparison>(`/sourcing/rfqs/${id}/comparison`)
}

export function listRfqAwards(id: number): Promise<Award[]> {
  return http.get<Award[]>(`/sourcing/rfqs/${id}/awards`)
}

export function createAward(id: number, payload: CreateAwardPayload): Promise<Award> {
  return http.post<Award>(`/sourcing/rfqs/${id}/awards`, payload)
}

export function approveAward(id: number): Promise<Award> {
  return http.post<Award>(`/sourcing/awards/${id}/approve`)
}

export function awardToPo(id: number): Promise<PurchaseOrderDetail> {
  return http.post<PurchaseOrderDetail>(`/sourcing/awards/${id}/to-po`)
}

// ---- 竞价 ----

export interface AuctionQuery extends PageParams {
  companyId?: number
  status?: string
}

export interface AuctionRow {
  id: number
  auctionNo: string
  companyId: number
  title: string
  currency: string
  sealed: boolean
  antiSnipingSeconds: number
  maxExtensions: number
  startAt: string
  endAt: string
  extensionCount: number
  resultVisibility: string
  status: string
  createdBy: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface AuctionLot {
  id: number
  lineNo: number
  itemId: number
  itemCode: string
  itemName: string
  qty: number
  uom: string
  startPrice: number
  reservePrice: number | null
  minDecrement: number
  currentLowestAmount: number | null
  bidCount: number
}

export interface AuctionDetail {
  id: number
  auctionNo: string
  title: string
  status: string
  sealed: boolean
  currency: string
  startAt: string
  endAt: string
  antiSnipingSeconds: number
  maxExtensions: number
  extensionCount: number
  version: number
  lots: AuctionLot[]
  supplierIds: number[]
}

export interface AuctionLotPayload {
  itemId: number
  qty: number
  uom: string
  startPrice: number
  reservePrice: number | null
  minDecrement: number
}

export interface CreateAuctionPayload {
  title: string
  companyId: number
  currency: string
  startAt: string
  endAt: string
  sealed: boolean
  antiSnipingSeconds: number
  maxExtensions: number
  lots: AuctionLotPayload[]
  supplierIds: number[]
}

export interface AuctionResult {
  id: number
  auctionId: number
  lotId: number
  supplierId: number
  supplierName: string
  bidId: number
  amount: number
  status: string
  approvedBy: number | null
  approvedAt: string | null
  version: number
  createdAt: string
}

export function listAuctions(query: AuctionQuery): Promise<PageResult<AuctionRow>> {
  return http.get<PageResult<AuctionRow>>('/sourcing/auctions', query)
}

export function getAuction(id: number): Promise<AuctionDetail> {
  return http.get<AuctionDetail>(`/sourcing/auctions/${id}`)
}

export function createAuction(payload: CreateAuctionPayload): Promise<AuctionDetail> {
  return http.post<AuctionDetail>('/sourcing/auctions', payload)
}

export function startAuction(id: number, version: number): Promise<AuctionDetail> {
  return http.post<AuctionDetail>(`/sourcing/auctions/${id}/start`, { version })
}

export function closeAuction(id: number, version: number): Promise<AuctionDetail> {
  return http.post<AuctionDetail>(`/sourcing/auctions/${id}/close`, { version })
}

export function listAuctionResults(auctionId: number): Promise<AuctionResult[]> {
  return http.get<AuctionResult[]>(`/sourcing/auctions/${auctionId}/results`)
}

export function awardAuction(auctionId: number): Promise<AuctionResult[]> {
  return http.post<AuctionResult[]>(`/sourcing/auctions/${auctionId}/award`)
}

export function approveAuctionResult(id: number): Promise<AuctionResult> {
  return http.post<AuctionResult>(`/sourcing/auction-results/${id}/approve`)
}

export function auctionResultToPo(id: number, plantId: number): Promise<PurchaseOrderDetail> {
  return http.post<PurchaseOrderDetail>(`/sourcing/auction-results/${id}/to-po`, { plantId })
}

// ---- 合同/框架协议与供应商价格库 ----

export interface ContractQuery extends PageParams {
  companyId?: number
  supplierId?: number
  status?: string
}

export interface ContractRow {
  id: number
  contractNo: string
  companyId: number
  supplierId: number
  title: string
  currency: string
  validFrom: string
  validTo: string
  paymentTerms: string | null
  status: string
  totalLimitAmount: number | null
  totalLimitQty: number | null
  sourceAwardId: number | null
  version: number
  createdBy: number
  createdAt: string
  approvedBy: number | null
  approvedAt: string | null
}

export interface ContractLine {
  id: number
  lineNo: number
  itemId: number
  itemCode: string
  itemName: string
  uom: string
  price: number
  minQty: number | null
  maxQty: number | null
  taxIncluded: boolean
  validFrom: string | null
  validTo: string | null
  consumedQty: number
}

export interface ContractDetail {
  id: number
  contractNo: string
  companyId: number
  supplierId: number
  supplierName: string | null
  title: string
  currency: string
  validFrom: string
  validTo: string
  paymentTerms: string | null
  status: string
  totalLimitAmount: number | null
  totalLimitQty: number | null
  sourceAwardId: number | null
  version: number
  createdAt: string
  consumedQty: number
  consumedAmount: number
  lines: ContractLine[]
}

export interface ContractConsumption {
  id: number
  contractLineId: number
  poId: number
  poLineId: number
  qty: number
  amount: number
  consumedAt: string
}

export interface ContractLinePayload {
  itemId: number
  uom: string | null
  price: number
  minQty: number | null
  maxQty: number | null
  taxIncluded: boolean
  validFrom: string | null
  validTo: string | null
}

export interface CreateContractPayload {
  companyId: number
  supplierId: number
  title: string
  currency: string
  validFrom: string
  validTo: string
  paymentTerms: string | null
  totalLimitAmount: number | null
  totalLimitQty: number | null
  lines: ContractLinePayload[]
}

export function listContracts(query: ContractQuery): Promise<PageResult<ContractRow>> {
  return http.get<PageResult<ContractRow>>('/procurement/contracts', query)
}

export function getContract(id: number): Promise<ContractDetail> {
  return http.get<ContractDetail>(`/procurement/contracts/${id}`)
}

export function createContract(payload: CreateContractPayload): Promise<ContractDetail> {
  return http.post<ContractDetail>('/procurement/contracts', payload)
}

export function activateContract(id: number): Promise<ContractDetail> {
  return http.post<ContractDetail>(`/procurement/contracts/${id}/activate`)
}

export function terminateContract(id: number): Promise<ContractDetail> {
  return http.post<ContractDetail>(`/procurement/contracts/${id}/terminate`)
}

export function releaseContractOrder(
  id: number,
  plantId: number,
  lines: { contractLineId: number; qty: number }[],
): Promise<PurchaseOrderDetail> {
  return http.post<PurchaseOrderDetail>(`/procurement/contracts/${id}/release-order`, {
    plantId,
    lines,
  })
}

export function listContractConsumption(id: number): Promise<ContractConsumption[]> {
  return http.get<ContractConsumption[]>(`/procurement/contracts/${id}/consumption`)
}

export interface SupplierPriceRow {
  id: number
  companyId: number
  supplierId: number
  supplierName: string
  itemId: number
  itemCode: string
  itemName: string
  uom: string
  minQty: number
  price: number
  taxIncluded: boolean
  currency: string
  validFrom: string
  validTo: string | null
  sourceType: string
  contractId: number | null
  awardId: number | null
  status: string
  version: number
}

export interface CreateSupplierPricePayload {
  companyId: number
  supplierId: number
  itemId: number
  uom: string | null
  minQty: number | null
  price: number
  taxIncluded: boolean
  currency: string
  validFrom: string
  validTo: string | null
}

export function listSupplierPrices(query: {
  supplierId: number
  itemId?: number
  date?: string
}): Promise<SupplierPriceRow[]> {
  return http.get<SupplierPriceRow[]>('/procurement/supplier-prices', query)
}

export function createSupplierPrice(
  payload: CreateSupplierPricePayload,
): Promise<SupplierPriceRow> {
  return http.post<SupplierPriceRow>('/procurement/supplier-prices', payload)
}

// ---- 固定资产 ----

export interface AssetQuery extends PageParams {
  companyId?: number
  status?: string
}

export interface AssetRow {
  id: number
  assetNo: string
  companyId: number
  plantId: number | null
  category: string
  name: string
  sourceType: string | null
  sourceId: number | null
  cost: number
  salvageValue: number
  usefulLifeMonths: number
  depreciationMethod: string
  accumulatedDepreciation: number
  status: string
  lastDepreciationPeriod: string | null
  capitalizedAt: string | null
  disposedAt: string | null
  disposalProceeds: number | null
  disposalGainLoss: number | null
  version: number
  createdBy: number
  createdAt: string
}

export interface AssetDepreciation {
  id: number
  period: string
  amount: number
  accumulatedAfter: number
  postedAt: string
}

export interface AssetDetail extends AssetRow {
  netBookValue: number
  depreciations: AssetDepreciation[]
}

export interface CreateAssetPayload {
  companyId: number
  plantId: number | null
  category: string
  name: string
  sourceType: string | null
  sourceId: number | null
  cost: number
  salvageValue: number
  usefulLifeMonths: number
  depreciationMethod: string
}

export function listAssets(query: AssetQuery): Promise<PageResult<AssetRow>> {
  return http.get<PageResult<AssetRow>>('/assets', query)
}

export function getAsset(id: number): Promise<AssetDetail> {
  return http.get<AssetDetail>(`/assets/${id}`)
}

export function createAsset(payload: CreateAssetPayload): Promise<AssetDetail> {
  return http.post<AssetDetail>('/assets', payload)
}

export function capitalizeAsset(id: number): Promise<AssetDetail> {
  return http.post<AssetDetail>(`/assets/${id}/capitalize`)
}

export function depreciateAsset(id: number, period: string): Promise<AssetDetail> {
  return http.post<AssetDetail>(`/assets/${id}/depreciate`, { period })
}

export function disposeAsset(id: number, proceeds: number): Promise<AssetDetail> {
  return http.post<AssetDetail>(`/assets/${id}/dispose`, { proceeds })
}

// ---- 项目与工时 ----

export interface ProjectQuery extends PageParams {
  companyId?: number
  status?: string
}

export interface ProjectRow {
  id: number
  projectNo: string
  companyId: number
  customerId: number | null
  name: string
  currency: string
  budgetAmount: number
  defaultLaborRate: number
  status: string
  startDate: string | null
  endDate: string | null
  managerId: number
  createdBy: number
  createdAt: string
  approvedBy: number | null
  approvedAt: string | null
  closedAt: string | null
  version: number
}

export interface ProjectWbs {
  id: number
  parentId: number | null
  code: string
  name: string
  budget: number
  status: string
}

export interface ProjectBudget {
  id: number
  versionNo: string
  laborAmount: number
  purchaseAmount: number
  expenseAmount: number
  otherAmount: number
  totalAmount: number
  status: string
}

export interface ProjectDetail {
  id: number
  projectNo: string
  companyId: number
  customerId: number | null
  name: string
  currency: string
  budgetAmount: number
  defaultLaborRate: number
  status: string
  startDate: string | null
  endDate: string | null
  managerId: number
  createdAt: string
  approvedAt: string | null
  closedAt: string | null
  version: number
  actualCost: number
  wbs: ProjectWbs[]
  budgets: ProjectBudget[]
}

export interface CreateProjectPayload {
  companyId: number
  customerId: number | null
  name: string
  currency: string
  defaultLaborRate: number | null
  startDate: string | null
  endDate: string | null
  managerId: number | null
}

export function listProjects(query: ProjectQuery): Promise<PageResult<ProjectRow>> {
  return http.get<PageResult<ProjectRow>>('/projects', query)
}

export function getProject(id: number): Promise<ProjectDetail> {
  return http.get<ProjectDetail>(`/projects/${id}`)
}

export function createProject(payload: CreateProjectPayload): Promise<ProjectDetail> {
  return http.post<ProjectDetail>('/projects', payload)
}

export function approveProject(id: number): Promise<ProjectDetail> {
  return http.post<ProjectDetail>(`/projects/${id}/approve`)
}

export function activateProject(id: number): Promise<ProjectDetail> {
  return http.post<ProjectDetail>(`/projects/${id}/activate`)
}

export function closeProject(id: number): Promise<ProjectDetail> {
  return http.post<ProjectDetail>(`/projects/${id}/close`)
}

export function addProjectWbs(
  id: number,
  payload: { parentId: number | null; code: string; name: string; budget: number | null },
): Promise<ProjectDetail> {
  return http.post<ProjectDetail>(`/projects/${id}/wbs`, payload)
}

export function createProjectBudget(
  id: number,
  payload: {
    versionNo: string | null
    laborAmount: number | null
    purchaseAmount: number | null
    expenseAmount: number | null
    otherAmount: number | null
  },
): Promise<ProjectDetail> {
  return http.post<ProjectDetail>(`/projects/${id}/budgets`, payload)
}

export function approveProjectBudget(id: number): Promise<ProjectDetail> {
  return http.post<ProjectDetail>(`/projects/budgets/${id}/approve`)
}

export interface TimesheetRow {
  id: number
  timesheetNo: string
  companyId: number
  userId: number
  status: string
  totalHours: number
  totalAmount: number
  createdAt: string
  submittedAt: string | null
  approvedBy: number | null
  approvedAt: string | null
  version: number
}

export interface TimesheetDetail extends TimesheetRow {
  lines: {
    id: number
    lineNo: number
    projectId: number
    wbsId: number | null
    workDate: string
    hours: number
    rate: number
    amount: number
  }[]
}

export interface TimesheetLinePayload {
  projectId: number
  wbsId: number | null
  workDate: string
  hours: number
}

export function listTimesheets(query: {
  page: number
  size: number
  companyId?: number
  userId?: number
  status?: string
}): Promise<PageResult<TimesheetRow>> {
  return http.get<PageResult<TimesheetRow>>('/timesheets', query)
}

export function getTimesheet(id: number): Promise<TimesheetDetail> {
  return http.get<TimesheetDetail>(`/timesheets/${id}`)
}

export function createTimesheet(
  companyId: number,
  lines: TimesheetLinePayload[],
): Promise<TimesheetDetail> {
  return http.post<TimesheetDetail>('/timesheets', { companyId, lines })
}

export function submitTimesheet(id: number): Promise<TimesheetDetail> {
  return http.post<TimesheetDetail>(`/timesheets/${id}/submit`)
}

export function approveTimesheet(id: number): Promise<TimesheetDetail> {
  return http.post<TimesheetDetail>(`/timesheets/${id}/approve`)
}

// ---- 请购 ----

export interface RequisitionQuery extends PageParams {
  companyId?: number
  status?: string
  keyword?: string
}

export interface RequisitionRow {
  id: number
  docNo: string
  companyId: number
  companyName: string
  requesterId: number
  requesterName: string
  needDate: string
  currency: string
  amount: number
  status: string
  version: number
  createdAt: string
}

export interface RequisitionLine {
  id: number
  lineNo: number
  itemId: number
  itemCode: string
  itemName: string
  qty: number
  uom: string
  estimatedPrice: number
  amount: number
}

export interface RequisitionDetail extends RequisitionRow {
  lines: RequisitionLine[]
}

export interface RequisitionLinePayload {
  itemId: number
  qty: number
  uom: string
  estimatedPrice: number
}

export interface CreateRequisitionPayload {
  companyId: number
  needDate: string
  currency: string
  lines: RequisitionLinePayload[]
}

export function listRequisitions(
  query: RequisitionQuery,
): Promise<PageResult<RequisitionRow>> {
  return http.get<PageResult<RequisitionRow>>('/procurement/requisitions', query)
}

export function getRequisition(id: number): Promise<RequisitionDetail> {
  return http.get<RequisitionDetail>(`/procurement/requisitions/${id}`)
}

export function createRequisition(
  payload: CreateRequisitionPayload,
): Promise<RequisitionDetail> {
  return http.post<RequisitionDetail>('/procurement/requisitions', payload)
}

export function updateRequisition(
  id: number,
  payload: Omit<CreateRequisitionPayload, 'companyId'> & { version: number },
): Promise<RequisitionDetail> {
  return http.put<RequisitionDetail>(`/procurement/requisitions/${id}`, payload)
}

export function submitRequisition(id: number, version: number): Promise<RequisitionDetail> {
  return http.post<RequisitionDetail>(`/procurement/requisitions/${id}/submit`, { version })
}

export function approveRequisition(id: number, version: number): Promise<RequisitionDetail> {
  return http.post<RequisitionDetail>(`/procurement/requisitions/${id}/approve`, { version })
}

export function rejectRequisition(
  id: number,
  comment: string,
  version: number,
): Promise<RequisitionDetail> {
  return http.post<RequisitionDetail>(`/procurement/requisitions/${id}/reject`, {
    comment,
    version,
  })
}

export function convertRequisition(
  id: number,
  supplierId: number,
  plantId: number,
  paymentTerms: string | null,
  version: number,
): Promise<PurchaseOrderDetail> {
  return http.post<PurchaseOrderDetail>(`/procurement/requisitions/${id}/convert`, {
    supplierId,
    plantId,
    paymentTerms,
    version,
  })
}

// ---- 采购执行：收货、发票、付款 ----

export interface GoodsReceiptQuery extends PageParams {
  companyId?: number
  poId?: number
  keyword?: string
}

export interface GoodsReceiptRow {
  id: number
  docNo: string
  poId: number
  poDocNo: string
  companyId: number
  companyName: string
  warehouseId: number
  warehouseName: string
  status: string
  receivedAt: string
}

export interface GoodsReceiptLine {
  id: number
  lineNo: number
  poLineId: number
  itemId: number
  itemCode: string
  itemName: string
  qty: number
  uom: string
  lotNo: string | null
  locationId: number | null
  locationName: string | null
}

export interface GoodsReceiptDetail extends GoodsReceiptRow {
  createdBy: number
  createdByName: string
  reversedBy: number | null
  reversedByName: string | null
  reversedAt: string | null
  reverseReason: string | null
  version: number
  lines: GoodsReceiptLine[]
}

export interface GoodsReceiptLinePayload {
  poLineId: number
  qty: number
  lotNo: string | null
  productionDate: string | null
  expiryDate: string | null
  locationId: number | null
  serials: string[]
}

export interface CreateGoodsReceiptPayload {
  poId: number
  warehouseId: number
  lines: GoodsReceiptLinePayload[]
}

export function listGoodsReceipts(
  query: GoodsReceiptQuery,
): Promise<PageResult<GoodsReceiptRow>> {
  return http.get<PageResult<GoodsReceiptRow>>('/procurement/goods-receipts', query)
}

export function getGoodsReceipt(id: number): Promise<GoodsReceiptDetail> {
  return http.get<GoodsReceiptDetail>(`/procurement/goods-receipts/${id}`)
}

export function createGoodsReceipt(
  payload: CreateGoodsReceiptPayload,
): Promise<GoodsReceiptDetail> {
  return http.post<GoodsReceiptDetail>('/procurement/goods-receipts', payload)
}

export function reverseGoodsReceipt(
  id: number,
  reason: string,
  version: number,
): Promise<GoodsReceiptDetail> {
  return http.post<GoodsReceiptDetail>(`/procurement/goods-receipts/${id}/reverse`, {
    reason,
    version,
  })
}

export interface InvoiceQuery extends PageParams {
  companyId?: number
  supplierId?: number
  status?: string
  keyword?: string
}

export interface InvoiceRow {
  id: number
  docNo: string
  supplierInvoiceNo: string
  companyId: number
  companyName: string
  supplierId: number
  supplierName: string
  poId: number
  poDocNo: string
  invoiceDate: string
  currency: string
  netAmount: number
  taxAmount: number
  totalAmount: number
  paidAmount: number
  status: string
  version: number
}

export interface InvoiceLine {
  id: number
  lineNo: number
  poLineId: number
  itemId: number
  itemCode: string
  itemName: string
  qty: number
  unitPrice: number
  amount: number
}

export interface InvoiceMatch {
  status: string
  priceVariance: number
  qtyVariance: number
  amountVariance: number
  detail: string | null
}

export interface InvoiceDetail extends InvoiceRow {
  dueDate: string | null
  createdBy: number
  createdByName: string
  approvedBy: number | null
  approvedByName: string | null
  approvedAt: string | null
  createdAt: string
  match: InvoiceMatch | null
  lines: InvoiceLine[]
}

export interface InvoiceLinePayload {
  poLineId: number
  qty: number
  unitPrice: number
}

export interface CreateInvoicePayload {
  poId: number
  supplierInvoiceNo: string
  invoiceDate: string
  dueDate: string | null
  currency: string
  taxAmount: number
  lines: InvoiceLinePayload[]
}

export function listSupplierInvoices(query: InvoiceQuery): Promise<PageResult<InvoiceRow>> {
  return http.get<PageResult<InvoiceRow>>('/procurement/invoices', query)
}

export function getSupplierInvoice(id: number): Promise<InvoiceDetail> {
  return http.get<InvoiceDetail>(`/procurement/invoices/${id}`)
}

export function createSupplierInvoice(payload: CreateInvoicePayload): Promise<InvoiceDetail> {
  return http.post<InvoiceDetail>('/procurement/invoices', payload)
}

export function rematchInvoice(id: number, version: number): Promise<InvoiceDetail> {
  return http.post<InvoiceDetail>(`/procurement/invoices/${id}/match`, { version })
}

export function approveInvoice(
  id: number,
  comment: string | null,
  version: number,
): Promise<InvoiceDetail> {
  return http.post<InvoiceDetail>(`/procurement/invoices/${id}/approve`, { comment, version })
}

export interface PaymentQuery extends PageParams {
  companyId?: number
  invoiceId?: number
  status?: string
  keyword?: string
}

export interface PaymentRow {
  id: number
  docNo: string
  companyId: number
  companyName: string
  supplierId: number
  supplierName: string
  invoiceId: number
  invoiceDocNo: string
  amount: number
  currency: string
  status: string
  createdAt: string
}

export interface PaymentDetail extends PaymentRow {
  supplierInvoiceNo: string
  requestedBy: number
  requestedByName: string
  approvedBy: number | null
  approvedByName: string | null
  approvedAt: string | null
  paidAt: string | null
  version: number
}

export function listPayments(query: PaymentQuery): Promise<PageResult<PaymentRow>> {
  return http.get<PageResult<PaymentRow>>('/procurement/payments', query)
}

export function getPayment(id: number): Promise<PaymentDetail> {
  return http.get<PaymentDetail>(`/procurement/payments/${id}`)
}

export function createPayment(invoiceId: number, amount: number): Promise<PaymentDetail> {
  return http.post<PaymentDetail>('/procurement/payments', { invoiceId, amount })
}

export function approvePayment(id: number, version: number): Promise<PaymentDetail> {
  return http.post<PaymentDetail>(`/procurement/payments/${id}/approve`, { version })
}

export function payPayment(id: number, version: number): Promise<PaymentDetail> {
  return http.post<PaymentDetail>(`/procurement/payments/${id}/pay`, { version })
}

// ---- BOM ----

export interface BomRow {
  id: number
  bomNo: string
  companyId: number
  itemId: number
  version: string
  status: string
  createdBy: number
  createdAt: string
  activatedAt: string | null
}

export interface BomLine {
  id: number
  lineNo: number
  componentItemId: number
  componentCode: string
  componentName: string
  qty: number
  uom: string
}

export interface BomDetail extends BomRow {
  itemCode: string
  itemName: string
  lines: BomLine[]
}

export interface BomLinePayload {
  componentItemId: number
  qty: number
  uom: string
}

export interface CreateBomPayload {
  companyId: number
  itemId: number
  version: string
  lines: BomLinePayload[]
}

export function pageBoms(query: {
  page: number
  size: number
  companyId: number
}): Promise<PageResult<BomRow>> {
  return http.get<PageResult<BomRow>>('/manufacturing/boms', query)
}

export function getBom(id: number): Promise<BomDetail> {
  return http.get<BomDetail>(`/manufacturing/boms/${id}`)
}

export function createBom(payload: CreateBomPayload): Promise<BomDetail> {
  return http.post<BomDetail>('/manufacturing/boms', payload)
}

export function activateBom(id: number): Promise<BomDetail> {
  return http.post<BomDetail>(`/manufacturing/boms/${id}/activate`)
}

// ---- 生产工单 ----

export interface WorkOrderQuery extends PageParams {
  companyId?: number
  status?: string
}

export interface WorkOrderRow {
  id: number
  orderNo: string
  companyId: number
  plantId: number
  itemId: number
  bomId: number
  qty: number
  status: string
  materialCost: number
  receivedQty: number
  version: number
  createdAt: string
  releasedAt: string | null
  completedAt: string | null
}

export interface WorkOrderComponent {
  id: number
  lineNo: number
  componentItemId: number
  componentCode: string
  componentName: string
  qtyRequired: number
  issuedQty: number
  uom: string
}

export interface ProductionIssue {
  id: number
  componentId: number
  itemId: number
  lotNo: string | null
  locationId: number
  qty: number
  unitCost: number
  amount: number
  createdAt: string
}

export interface ProductionReceipt {
  id: number
  receiptNo: string
  warehouseId: number
  locationId: number
  lotNo: string | null
  qty: number
  unitCost: number
  amount: number
  status: string
  createdAt: string
}

export interface WorkOrderDetail {
  id: number
  orderNo: string
  companyId: number
  plantId: number
  itemId: number
  itemCode: string
  itemName: string
  bomId: number
  qty: number
  status: string
  materialCost: number
  receivedQty: number
  version: number
  createdAt: string
  releasedAt: string | null
  completedAt: string | null
  components: WorkOrderComponent[]
  issues: ProductionIssue[]
  receipts: ProductionReceipt[]
}

export interface CreateWorkOrderPayload {
  companyId: number
  plantId: number
  itemId: number
  bomId: number
  qty: number
}

export interface IssueWorkOrderPayload {
  warehouseId: number
  lines: {
    componentId: number
    qty: number
    lotNo: string | null
    locationId: number | null
  }[]
}

export function issueWorkOrder(
  id: number,
  payload: IssueWorkOrderPayload,
): Promise<WorkOrderDetail> {
  return http.post<WorkOrderDetail>(`/manufacturing/work-orders/${id}/issues`, payload)
}

export interface ReceiptWorkOrderPayload {
  warehouseId: number
  locationId: number | null
  lotNo: string | null
  qty: number
}

export function listWorkOrders(query: WorkOrderQuery): Promise<PageResult<WorkOrderRow>> {
  return http.get<PageResult<WorkOrderRow>>('/manufacturing/work-orders', query)
}

export function getWorkOrder(id: number): Promise<WorkOrderDetail> {
  return http.get<WorkOrderDetail>(`/manufacturing/work-orders/${id}`)
}

export function createWorkOrder(payload: CreateWorkOrderPayload): Promise<WorkOrderDetail> {
  return http.post<WorkOrderDetail>('/manufacturing/work-orders', payload)
}

export function releaseWorkOrder(id: number): Promise<WorkOrderDetail> {
  return http.post<WorkOrderDetail>(`/manufacturing/work-orders/${id}/release`)
}

export function receiptWorkOrder(
  id: number,
  payload: ReceiptWorkOrderPayload,
): Promise<WorkOrderDetail> {
  return http.post<WorkOrderDetail>(`/manufacturing/work-orders/${id}/receipts`, payload)
}

export function closeWorkOrder(id: number): Promise<WorkOrderDetail> {
  return http.post<WorkOrderDetail>(`/manufacturing/work-orders/${id}/close`)
}

// ---- 质量 ----

export interface QualityQuery extends PageParams {
  companyId?: number
  status?: string
}

export interface InspectionCharacteristic {
  id: number
  lineNo: number
  name: string
  spec: string | null
  critical: boolean
  method: string | null
}

export interface InspectionPlanDetail {
  id: number
  planNo: string
  companyId: number
  itemId: number
  itemCode: string
  itemName: string
  supplierId: number | null
  inspectionType: string
  version: string
  status: string
  samplingPlan: string | null
  aql: number | null
  createdAt: string
  activatedAt: string | null
  characteristics: InspectionCharacteristic[]
}

export interface InspectionPlanRow {
  id: number
  planNo: string
  companyId: number
  itemId: number
  supplierId: number | null
  inspectionType: string
  version: string
  status: string
  samplingPlan: string | null
  aql: number | null
  createdBy: number
  createdAt: string
  activatedAt: string | null
}

export interface InspectionPlanPayload {
  companyId: number
  itemId: number
  supplierId: number | null
  inspectionType: string
  version: string
  samplingPlan: string | null
  aql: number | null
  characteristics: {
    name: string
    spec: string | null
    critical: boolean
    method: string | null
  }[]
}

export function pageInspectionPlans(
  page: number,
  size: number,
  companyId: number,
): Promise<PageResult<InspectionPlanRow>> {
  return http.get<PageResult<InspectionPlanRow>>('/quality/inspection-plans', {
    page,
    size,
    companyId,
  })
}

export function createInspectionPlan(
  payload: InspectionPlanPayload,
): Promise<InspectionPlanDetail> {
  return http.post<InspectionPlanDetail>('/quality/inspection-plans', payload)
}

export function activateInspectionPlan(id: number): Promise<InspectionPlanDetail> {
  return http.post<InspectionPlanDetail>(`/quality/inspection-plans/${id}/activate`)
}

export interface CertificateRow {
  id: number
  companyId: number
  supplierId: number
  itemId: number | null
  certType: string
  certNo: string
  validFrom: string
  validTo: string
  status: string
  effectiveStatus: string
  daysUntilExpiry: number
}

export interface CreateCertificatePayload {
  companyId: number
  supplierId: number
  itemId: number | null
  certType: string
  certNo: string
  validFrom: string
  validTo: string
}

export function pageCertificates(
  page: number,
  size: number,
  companyId?: number,
  supplierId?: number,
): Promise<PageResult<CertificateRow>> {
  return http.get<PageResult<CertificateRow>>('/quality/certificates', {
    page,
    size,
    companyId,
    supplierId,
  })
}

export function expiringCertificates(companyId: number, days = 30): Promise<CertificateRow[]> {
  return http.get<CertificateRow[]>('/quality/certificates/expiring', { companyId, days })
}

export function createCertificate(payload: CreateCertificatePayload): Promise<CertificateRow> {
  return http.post<CertificateRow>('/quality/certificates', payload)
}

export interface InspectionOrderRow {
  id: number
  orderNo: string
  companyId: number
  planId: number
  sourceType: string | null
  sourceId: number | null
  itemId: number
  supplierId: number | null
  lotNo: string | null
  qty: number
  status: string
  createdAt: string
  completedAt: string | null
}

export interface InspectionResult {
  id: number
  lineNo: number
  characteristicId: number | null
  name: string
  measuredValue: string | null
  result: string | null
  remark: string | null
}

export interface InspectionOrderDetail {
  id: number
  orderNo: string
  companyId: number
  planId: number
  sourceType: string | null
  sourceId: number | null
  sourceLineId: number | null
  itemId: number
  supplierId: number | null
  lotNo: string | null
  qty: number
  status: string
  createdAt: string
  completedAt: string | null
  ncrId: number | null
  results: InspectionResult[]
}

export interface RecordResultsPayload {
  lines: {
    characteristicId: number | null
    name: string
    measuredValue: string | null
    result: string
    remark: string | null
  }[]
}

export interface NcrRow {
  id: number
  ncrNo: string
  companyId: number
  sourceType: string | null
  sourceId: number | null
  inspectionId: number | null
  itemId: number
  supplierId: number | null
  lotNo: string | null
  qty: number
  defect: string
  status: string
  disposition: string | null
  reason: string | null
  createdAt: string
  dispositionedAt: string | null
  closedAt: string | null
}

export interface CapaRow {
  id: number
  capaNo: string
  companyId: number
  ncrId: number | null
  supplierId: number | null
  rootCause: string | null
  actionPlan: string
  status: string
  createdAt: string
  verifiedBy: number | null
  verifiedAt: string | null
  closedAt: string | null
}

export function getInspectionPlan(id: number): Promise<InspectionPlanDetail> {
  return http.get<InspectionPlanDetail>(`/quality/inspection-plans/${id}`)
}

export function listInspectionOrders(
  query: QualityQuery,
): Promise<PageResult<InspectionOrderRow>> {
  return http.get<PageResult<InspectionOrderRow>>('/quality/inspection-orders', query)
}

export function getInspectionOrder(id: number): Promise<InspectionOrderDetail> {
  return http.get<InspectionOrderDetail>(`/quality/inspection-orders/${id}`)
}

export function recordInspectionResults(
  id: number,
  payload: RecordResultsPayload,
): Promise<InspectionOrderDetail> {
  return http.post<InspectionOrderDetail>(`/quality/inspection-orders/${id}/results`, payload)
}

export function listNcrs(query: QualityQuery): Promise<PageResult<NcrRow>> {
  return http.get<PageResult<NcrRow>>('/quality/ncrs', query)
}

export interface CreateNcrPayload {
  companyId: number
  itemId: number
  supplierId: number | null
  lotNo: string | null
  qty: number | null
  defect: string
}

export function createNcr(payload: CreateNcrPayload): Promise<NcrRow> {
  return http.post<NcrRow>('/quality/ncrs', payload)
}

export function disposeNcr(id: number, disposition: string, reason: string): Promise<NcrRow> {
  return http.post<NcrRow>(`/quality/ncrs/${id}/disposition`, { disposition, reason })
}

export function closeNcr(id: number): Promise<NcrRow> {
  return http.post<NcrRow>(`/quality/ncrs/${id}/close`)
}

export function listCapas(query: QualityQuery): Promise<PageResult<CapaRow>> {
  return http.get<PageResult<CapaRow>>('/quality/capas', query)
}

export function createCapa(
  ncrId: number,
  rootCause: string | null,
  actionPlan: string,
): Promise<CapaRow> {
  return http.post<CapaRow>('/quality/capas', { ncrId, rootCause, actionPlan })
}

export function verifyCapa(id: number): Promise<CapaRow> {
  return http.post<CapaRow>(`/quality/capas/${id}/verify`)
}

export function closeCapa(id: number): Promise<CapaRow> {
  return http.post<CapaRow>(`/quality/capas/${id}/close`)
}

// ---- 报表中心 ----

export interface ReportDefinition {
  id: number
  code: string
  name: string
  queryKey: string
  description: string | null
  status: string
  createdAt: string
}

export interface ReportRun {
  id: number
  definitionId: number
  code: string
  name: string
  companyId: number
  asOf: string | null
  status: string
  rowCount: number
  durationMs: number | null
  createdAt: string
}

export function listReportDefinitions(): Promise<ReportDefinition[]> {
  return http.get<ReportDefinition[]>('/reports')
}

export interface ReportRunQuery extends PageParams {
  companyId?: number
}

export function listReportRuns(query: ReportRunQuery): Promise<PageResult<ReportRun>> {
  return http.get<PageResult<ReportRun>>('/reports/runs', query)
}

export function runReport(
  code: string,
  companyId: number,
  asOf: string | null,
): Promise<ReportRun> {
  return http.post<ReportRun>(`/reports/${code}/runs`, { companyId, asOf })
}

export function downloadReportRun(runId: number): Promise<Blob> {
  return downloadFile(`/reports/runs/${runId}/download`)
}
