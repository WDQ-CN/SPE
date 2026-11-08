package com.spe.context.procurement.internal;

import java.math.BigDecimal;

/** 采购退货行投影。 */
public record PurchaseReturnLineView(
    long id,
    int lineNo,
    long poLineId,
    long itemId,
    String itemCode,
    String itemName,
    BigDecimal qty,
    String uom,
    BigDecimal unitCost,
    BigDecimal amount,
    String lotNo,
    Long locationId,
    String locationName) {}
