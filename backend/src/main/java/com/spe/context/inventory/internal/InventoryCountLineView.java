package com.spe.context.inventory.internal;

import java.math.BigDecimal;

/** 盘点行投影。 */
public record InventoryCountLineView(
    long id,
    int lineNo,
    long itemId,
    long lotId,
    long locationId,
    BigDecimal snapshotQty,
    BigDecimal countedQty,
    BigDecimal varianceQty,
    String reason) {}
