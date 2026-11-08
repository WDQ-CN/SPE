package com.spe.context.procurement.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 供应商价格投影。 */
public record SupplierPriceView(
    long id,
    long companyId,
    long supplierId,
    String supplierName,
    long itemId,
    String itemCode,
    String itemName,
    String uom,
    BigDecimal minQty,
    BigDecimal price,
    boolean taxIncluded,
    String currency,
    LocalDate validFrom,
    LocalDate validTo,
    SupplierPriceSource sourceType,
    Long contractId,
    Long awardId,
    SupplierPriceStatus status,
    int version) {}
