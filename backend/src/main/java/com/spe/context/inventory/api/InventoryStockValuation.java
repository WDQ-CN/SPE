package com.spe.context.inventory.api;

import java.math.BigDecimal;

/** 库存估值快照的跨上下文投影（用于库存-GL 对账）。 */
public record InventoryStockValuation(
    long companyId,
    long warehouseId,
    long itemId,
    long lotId,
    String valuationMethod,
    BigDecimal qty,
    BigDecimal value) {}
