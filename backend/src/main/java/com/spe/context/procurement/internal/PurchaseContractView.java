package com.spe.context.procurement.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 合同详情投影。 */
public record PurchaseContractView(
    long id,
    String contractNo,
    long companyId,
    long supplierId,
    String supplierName,
    String title,
    String currency,
    LocalDate validFrom,
    LocalDate validTo,
    String paymentTerms,
    PurchaseContractStatus status,
    BigDecimal totalLimitAmount,
    BigDecimal totalLimitQty,
    Long sourceAwardId,
    int version,
    LocalDateTime createdAt,
    BigDecimal consumedQty,
    BigDecimal consumedAmount,
    List<PurchaseContractLineView> lines) {}
