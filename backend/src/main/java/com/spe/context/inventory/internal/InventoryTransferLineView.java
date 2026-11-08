package com.spe.context.inventory.internal;

import java.math.BigDecimal;
import java.util.List;

/** 调拨行投影。 */
public record InventoryTransferLineView(
    long id,
    int lineNo,
    long itemId,
    long lotId,
    BigDecimal qty,
    String uom,
    BigDecimal unitCost,
    BigDecimal amount,
    List<Long> serialIds) {}
