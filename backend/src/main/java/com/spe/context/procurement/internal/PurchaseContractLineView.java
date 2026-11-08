package com.spe.context.procurement.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 合同行投影。 */
public record PurchaseContractLineView(
    long id,
    int lineNo,
    long itemId,
    String itemCode,
    String itemName,
    String uom,
    BigDecimal price,
    BigDecimal minQty,
    BigDecimal maxQty,
    boolean taxIncluded,
    LocalDate validFrom,
    LocalDate validTo,
    BigDecimal consumedQty) {}
