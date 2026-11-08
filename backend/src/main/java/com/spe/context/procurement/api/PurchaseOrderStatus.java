package com.spe.context.procurement.api;

/** 采购订单状态（docs/erp/32、48）。 */
public enum PurchaseOrderStatus {
  DRAFT,
  APPROVED,
  REJECTED,
  SENT,
  PARTIAL_RECEIVED,
  RECEIVED,
  CLOSED,
  CANCELED
}
