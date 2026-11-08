package com.spe.context.sales.internal;

/** 销售订单状态（发货/开票状态由后续模块推进）。 */
public enum SalesOrderStatus {
  DRAFT,
  CONFIRMED,
  PARTIAL_SHIPPED,
  SHIPPED,
  CLOSED,
  CANCELED
}
