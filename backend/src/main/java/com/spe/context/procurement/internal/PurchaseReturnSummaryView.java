package com.spe.context.procurement.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 采购退货列表投影。 */
public record PurchaseReturnSummaryView(
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
    String status) {}
