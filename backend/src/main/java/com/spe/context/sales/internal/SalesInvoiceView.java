package com.spe.context.sales.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 销售发票详情投影。 */
public record SalesInvoiceView(
    long id,
    String invoiceNo,
    long companyId,
    long customerId,
    String customerName,
    long orderId,
    String currency,
    SalesInvoiceStatus status,
    BigDecimal netAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    BigDecimal paidAmount,
    LocalDateTime issuedAt,
    LocalDate dueDate,
    String remark,
    int version,
    List<SalesInvoiceLineView> lines) {}
