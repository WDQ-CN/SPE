/** ERP 状态文案与展示工具；状态值来自后端枚举，标签在此集中维护。 */

export const SALES_ORDER_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  PARTIAL_SHIPPED: '部分发货',
  SHIPPED: '已发货',
  CLOSED: '已关闭',
  CANCELED: '已取消',
}

export const PURCHASE_ORDER_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  APPROVED: '已审批',
  REJECTED: '已驳回',
  SENT: '已发送',
  PARTIAL_RECEIVED: '部分收货',
  RECEIVED: '已收货',
  CLOSED: '已关闭',
  CANCELED: '已取消',
}

export const BOM_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '已生效',
  INACTIVE: '已停用',
}

export const WORK_ORDER_STATUS_LABEL: Record<string, string> = {
  CREATED: '已创建',
  RELEASED: '已下达',
  IN_PROGRESS: '生产中',
  COMPLETED: '已完工',
  CLOSED: '已关闭',
  CANCELED: '已取消',
}

export const INSPECTION_PLAN_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '已生效',
  RETIRED: '已停用',
}

export const INSPECTION_TYPE_LABEL: Record<string, string> = {
  IQC: '来料检验',
  IPQC: '过程检验',
  FQC: '完工检验',
}

export const CERT_EFFECTIVE_STATUS_LABEL: Record<string, string> = {
  VALID: '有效',
  EXPIRING: '即将到期',
  EXPIRED: '已过期',
  REVOKED: '已撤销',
}

export const INSPECTION_STATUS_LABEL: Record<string, string> = {
  PENDING: '待检验',
  IN_PROGRESS: '检验中',
  PASSED: '合格',
  FAILED: '不合格',
  DEVIATED: '让步接收',
}

export const NCR_STATUS_LABEL: Record<string, string> = {
  OPEN: '待处置',
  DISPOSITIONED: '已处置',
  CLOSED: '已关闭',
}

export const NCR_DISPOSITION_LABEL: Record<string, string> = {
  REWORK: '返工',
  SCRAP: '报废',
  RETURN: '退货',
  DEVIATE: '让步接收',
  USE_AS_IS: '原样使用',
}

export const CAPA_STATUS_LABEL: Record<string, string> = {
  OPEN: '待处理',
  IN_PROGRESS: '进行中',
  VERIFIED: '已验证',
  CLOSED: '已关闭',
}

export const RESERVATION_STATUS_LABEL: Record<string, string> = {
  ACTIVE: '生效中',
  RELEASED: '已释放',
  CONSUMED: '已消耗',
  EXPIRED: '已过期',
}

export const FREEZE_STATUS_LABEL: Record<string, string> = {
  ACTIVE: '冻结中',
  RELEASED: '已解冻',
}

export const TRANSFER_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  IN_TRANSIT: '在途',
  RECEIVED: '已接收',
  CANCELED: '已取消',
}

export const COUNT_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  COUNTING: '盘点中',
  REVIEW: '待复核',
  APPROVED: '已批准',
  POSTED: '已过账',
  CANCELED: '已取消',
}

export const RFQ_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  SENT: '已发送',
  CLOSED: '已截止',
  EVALUATING: '评审中',
  AWARDED: '已定标',
  CANCELED: '已取消',
}

export const AWARD_POLICY_LABEL: Record<string, string> = {
  WHOLE: '整单定标',
  LINE: '按行定标',
  SPLIT: '拆分定标',
}

export const INVITATION_STATUS_LABEL: Record<string, string> = {
  INVITED: '已邀请',
  VIEWED: '已查看',
  QUOTED: '已报价',
  DECLINED: '已拒绝',
}

export const QUOTE_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '已提交',
  WITHDRAWN: '已撤回',
  ACCEPTED: '已接受',
  REJECTED: '已拒绝',
}

export const AWARD_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  APPROVED: '已批准',
  PO_CREATED: '已转 PO',
  CONTRACT_CREATED: '已转合同',
  CANCELED: '已取消',
}

export const ASSET_STATUS_LABEL: Record<string, string> = {
  PLANNED: '待资本化',
  ACTIVE: '使用中',
  DISPOSED: '已处置',
}

export const PROJECT_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  APPROVED: '已审批',
  ACTIVE: '进行中',
  CLOSED: '已关闭',
}

export const PROJECT_BUDGET_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  APPROVED: '已批准',
}

export const WBS_STATUS_LABEL: Record<string, string> = {
  OPEN: '开放',
  CLOSED: '已关闭',
}

export const TIMESHEET_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '待审批',
  APPROVED: '已批准',
  POSTED: '已入账',
}

export const REQUISITION_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '待审批',
  APPROVED: '已审批',
  REJECTED: '已驳回',
  CONVERTED: '已转 PO',
  CLOSED: '已关闭',
}

export const AUCTION_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  SCHEDULED: '已排期',
  LIVE: '进行中',
  CLOSED: '已结束',
  AWARDED: '已定标',
  CANCELED: '已取消',
}

export const AUCTION_BID_STATUS_LABEL: Record<string, string> = {
  VALID: '有效',
  OUTBID: '已被超越',
  WINNING: '领先',
  DISQUALIFIED: '无效',
}

export const AUCTION_RESULT_STATUS_LABEL: Record<string, string> = {
  DRAFT: '待批准',
  APPROVED: '已批准',
  PO_CREATED: '已转 PO',
  CANCELED: '已取消',
}

export const CONTRACT_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '生效中',
  EXPIRED: '已过期',
  TERMINATED: '已终止',
}

export const PRICE_SOURCE_LABEL: Record<string, string> = {
  MANUAL: '手工维护',
  CONTRACT: '合同价',
  AWARD: '定标价',
}

export const PRICE_STATUS_LABEL: Record<string, string> = {
  ACTIVE: '有效',
  INACTIVE: '失效',
}

export const GOODS_RECEIPT_STATUS_LABEL: Record<string, string> = {
  POSTED: '已过账',
  REVERSED: '已冲销',
}

export const INVOICE_STATUS_LABEL: Record<string, string> = {
  RECEIVED: '待匹配',
  MATCHED: '匹配通过',
  EXCEPTION: '匹配异常',
  APPROVED: '已批准',
  PARTIALLY_PAID: '部分付款',
  PAID: '已付清',
  CANCELED: '已取消',
}

export const INVOICE_MATCH_STATUS_LABEL: Record<string, string> = {
  MATCHED: '匹配通过',
  EXCEPTION: '匹配异常',
}

export const PAYMENT_STATUS_LABEL: Record<string, string> = {
  REQUESTED: '待审批',
  APPROVED: '已批准',
  PAID: '已付款',
  CANCELED: '已取消',
}

export const MASTER_STATUS_LABEL: Record<string, string> = {
  ACTIVE: '启用',
  INACTIVE: '停用',
}

export const ITEM_TYPE_LABEL: Record<string, string> = {
  RAW: '原材料',
  WIP: '在制品',
  FINISHED: '成品',
  SERVICE: '服务',
  EXPENSE: '费用',
}

export const VALUATION_METHOD_LABEL: Record<string, string> = {
  FIFO: '先进先出',
  AVCO: '移动平均',
  STANDARD: '标准成本',
}

export const WAREHOUSE_TYPE_LABEL: Record<string, string> = {
  STANDARD: '标准仓',
  QUALITY: '质检仓',
  TRANSIT: '在途仓',
  CONSIGNMENT: '寄售仓',
}

export const SHIPMENT_STATUS_LABEL: Record<string, string> = {
  SHIPPED: '已发货',
  DELIVERED: '已送达',
  CANCELED: '已取消',
}

export const SALES_INVOICE_STATUS_LABEL: Record<string, string> = {
  ISSUED: '已开票',
  PARTIAL: '部分收款',
  PAID: '已收款',
  VOID: '已作废',
}

export const RECEIPT_STATUS_LABEL: Record<string, string> = {
  POSTED: '已入账',
  CLEARED: '已核销',
  VOID: '已作废',
}

export const SALES_RETURN_STATUS_LABEL: Record<string, string> = {
  REQUESTED: '待贷项',
  CREDITED: '已贷项',
  CANCELED: '已取消',
}

const SUCCESS_STATUS = new Set([
  'CONFIRMED',
  'APPROVED',
  'SENT',
  'RECEIVED',
  'CLOSED',
  'COMPLETED',
  'PASSED',
  'VERIFIED',
  'SUCCESS',
  'ACTIVE',
  'APPROVED',
  'POSTED',
  'MATCHED',
  'PAID',
  'PARTIAL',
  'CLEARED',
  'CREDITED',
  'AWARDED',
  'QUOTED',
  'SUBMITTED',
  'PO_CREATED',
  'CONTRACT_CREATED',
  'CONVERTED',
  'EXPIRING',
])

const WARNING_STATUS = new Set([
  'DRAFT',
  'CREATED',
  'RELEASED',
  'IN_PROGRESS',
  'PARTIAL_SHIPPED',
  'PARTIAL_RECEIVED',
  'PENDING',
  'OPEN',
  'DISPOSITIONED',
  'COUNTING',
  'REVIEW',
  'EXCEPTION',
  'PARTIALLY_PAID',
  'REQUESTED',
  'ISSUED',
  'SENT',
  'CLOSED',
  'EVALUATING',
  'LIVE',
  'SCHEDULED',
  'SUBMITTED',
  'EXPIRED',
  'REVOKED',
  'DISPOSED',
])

/** 映射到全局 `.status-mark` 的颜色修饰类 */
export function statusTone(status: string): string {
  if (SUCCESS_STATUS.has(status)) {
    return 'is-success'
  }
  if (WARNING_STATUS.has(status)) {
    return 'is-warning'
  }
  return 'is-off'
}

export function labelOf(labels: Record<string, string>, value: string | null): string {
  if (!value) {
    return '—'
  }
  return labels[value] ?? value
}

export function formatDateTime(value: string | null): string {
  if (!value) {
    return '—'
  }
  return value.replace('T', ' ').slice(0, 19)
}

export function formatAmount(value: number | null, currency?: string): string {
  if (value === null || value === undefined) {
    return '—'
  }
  const text = value.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  })
  return currency ? `${text} ${currency}` : text
}

export function formatQty(value: number | null): string {
  if (value === null || value === undefined) {
    return '—'
  }
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 6 })
}
