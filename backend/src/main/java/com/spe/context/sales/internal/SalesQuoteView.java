package com.spe.context.sales.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 销售报价详情投影。 */
public record SalesQuoteView(
    long id,
    String quoteNo,
    long companyId,
    long customerId,
    String customerCode,
    String customerName,
    String currency,
    LocalDate validUntil,
    SalesQuoteStatus status,
    BigDecimal netAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    int version,
    LocalDateTime createdAt,
    LocalDateTime sentAt,
    LocalDateTime acceptedAt,
    Long convertedOrderId,
    List<SalesQuoteLineView> lines) {}
