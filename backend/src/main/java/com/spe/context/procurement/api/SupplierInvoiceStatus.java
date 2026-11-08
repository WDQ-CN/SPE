package com.spe.context.procurement.api;

/** 供应商发票状态（docs/erp/32 §3、48）。 */
public enum SupplierInvoiceStatus {
  RECEIVED,
  MATCHED,
  EXCEPTION,
  APPROVED,
  PARTIALLY_PAID,
  PAID,
  CANCELED
}
