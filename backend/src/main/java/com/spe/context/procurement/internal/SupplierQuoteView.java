package com.spe.context.procurement.internal;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 供应商报价投影（内部比价与门户自己的报价共用；门户只返回本供应商）。 */
public record SupplierQuoteView(
    long id,
    String quoteNo,
    long rfqId,
    long supplierId,
    String supplierCode,
    String supplierName,
    String currency,
    LocalDate validUntil,
    SupplierQuoteStatus status,
    LocalDateTime submittedAt,
    int version,
    List<SupplierQuoteLineView> lines) {}
