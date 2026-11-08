package com.spe.context.procurement.internal;

import com.spe.context.procurement.api.PurchaseOrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 采购订单列表投影（不含行）。 */
public record PurchaseOrderSummaryView(
    long id,
    String docNo,
    long companyId,
    String companyName,
    long supplierId,
    String supplierName,
    long plantId,
    String plantName,
    String currency,
    BigDecimal amount,
    PurchaseOrderStatus status,
    int version,
    LocalDateTime createdAt) {}
