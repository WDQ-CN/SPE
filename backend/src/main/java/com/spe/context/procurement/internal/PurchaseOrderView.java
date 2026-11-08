package com.spe.context.procurement.internal;

import com.spe.context.procurement.api.PurchaseOrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 采购订单详情投影。 */
public record PurchaseOrderView(
    long id,
    String docNo,
    long companyId,
    String companyName,
    long supplierId,
    String supplierName,
    long plantId,
    String plantName,
    Long sourcePrId,
    String sourcePrDocNo,
    String currency,
    String paymentTerms,
    BigDecimal amount,
    PurchaseOrderStatus status,
    long createdBy,
    String createdByName,
    Long approvedBy,
    String approvedByName,
    LocalDateTime approvedAt,
    int version,
    LocalDateTime createdAt,
    List<PurchaseOrderLineView> lines) {}
