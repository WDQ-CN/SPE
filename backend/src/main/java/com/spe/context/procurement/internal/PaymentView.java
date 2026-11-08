package com.spe.context.procurement.internal;

import com.spe.context.procurement.api.PaymentRequestStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 付款申请详情投影。 */
public record PaymentView(
    long id,
    String docNo,
    long companyId,
    String companyName,
    long supplierId,
    String supplierName,
    long invoiceId,
    String invoiceDocNo,
    String supplierInvoiceNo,
    BigDecimal amount,
    String currency,
    PaymentRequestStatus status,
    long requestedBy,
    String requestedByName,
    Long approvedBy,
    String approvedByName,
    LocalDateTime approvedAt,
    LocalDateTime paidAt,
    int version,
    LocalDateTime createdAt) {}
