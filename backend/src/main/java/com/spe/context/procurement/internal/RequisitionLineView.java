package com.spe.context.procurement.internal;

import java.math.BigDecimal;

/** 请购单行投影。 */
public record RequisitionLineView(
    long id,
    int lineNo,
    long itemId,
    String itemCode,
    String itemName,
    BigDecimal qty,
    String uom,
    BigDecimal estimatedPrice,
    BigDecimal amount) {}
