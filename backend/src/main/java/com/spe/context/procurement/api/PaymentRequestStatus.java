package com.spe.context.procurement.api;

/** 付款申请状态（docs/erp/32 §3、48）。 */
public enum PaymentRequestStatus {
  REQUESTED,
  APPROVED,
  PAID,
  CANCELED
}
