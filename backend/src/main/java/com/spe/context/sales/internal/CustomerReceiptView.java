package com.spe.context.sales.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 客户收款详情投影。 */
public record CustomerReceiptView(
    long id,
    String receiptNo,
    long companyId,
    long customerId,
    String customerName,
    String currency,
    BigDecimal amount,
    String method,
    ReceiptStatus status,
    LocalDateTime receivedAt,
    int version,
    List<Allocation> allocations) {

  public record Allocation(long id, long invoiceId, String invoiceNo, BigDecimal amount) {}
}
