package com.spe.context.procurement.internal;

import java.math.BigDecimal;

/** 收货单行投影。 */
public record GoodsReceiptLineView(
    long id,
    int lineNo,
    long poLineId,
    long itemId,
    String itemCode,
    String itemName,
    BigDecimal qty,
    String uom,
    String lotNo,
    Long locationId,
    String locationName) {}
