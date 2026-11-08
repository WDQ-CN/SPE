package com.spe.context.procurement.api;

/** 收货单状态（docs/erp/32、48）；E1 只支持过账，冲销在 W3。 */
public enum GoodsReceiptStatus {
  POSTED,
  REVERSED
}
