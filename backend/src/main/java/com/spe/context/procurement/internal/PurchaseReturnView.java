package com.spe.context.procurement.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 采购退货详情投影。 */
public record PurchaseReturnView(
    long id,
    String docNo,
    long companyId,
    String companyName,
    long supplierId,
    String supplierName,
    long poId,
    String poDocNo,
    long warehouseId,
    String warehouseName,
    LocalDate returnDate,
    String currency,
    BigDecimal netAmount,
    boolean invoiced,
    String status,
    String reason,
    long createdBy,
    String createdByName,
    int version,
    LocalDateTime createdAt,
    List<PurchaseReturnLineView> lines) {}
