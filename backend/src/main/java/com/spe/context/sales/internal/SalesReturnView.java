package com.spe.context.sales.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 销售退货详情投影。 */
public record SalesReturnView(
    long id,
    String returnNo,
    long companyId,
    long customerId,
    String customerName,
    long shipmentId,
    long orderId,
    String currency,
    SalesReturnStatus status,
    BigDecimal netAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    String reason,
    LocalDateTime creditedAt,
    int version,
    List<SalesReturnLineView> lines) {}
