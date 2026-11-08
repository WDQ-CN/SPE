package com.spe.context.sales.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 销售订单详情投影。 */
public record SalesOrderView(
    long id,
    String orderNo,
    long companyId,
    long customerId,
    String customerCode,
    String customerName,
    Long sourceQuoteId,
    String currency,
    String paymentTerms,
    SalesOrderStatus status,
    BigDecimal netAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    int version,
    LocalDateTime createdAt,
    LocalDateTime confirmedAt,
    List<SalesOrderLineView> lines) {}
