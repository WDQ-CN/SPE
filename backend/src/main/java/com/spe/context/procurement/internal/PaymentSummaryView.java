package com.spe.context.procurement.internal;

import com.spe.context.procurement.api.PaymentRequestStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 付款申请列表投影。 */
public record PaymentSummaryView(
    long id,
    String docNo,
    long companyId,
    String companyName,
    long supplierId,
    String supplierName,
    long invoiceId,
    String invoiceDocNo,
    BigDecimal amount,
    String currency,
    PaymentRequestStatus status,
    LocalDateTime createdAt) {}
