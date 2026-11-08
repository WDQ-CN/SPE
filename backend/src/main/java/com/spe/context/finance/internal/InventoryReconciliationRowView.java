package com.spe.context.finance.internal;

import java.math.BigDecimal;

/** 库存子账明细行（GL 无物料维度，只按总额对账）。 */
public record InventoryReconciliationRowView(
    long warehouseId,
    long itemId,
    long lotId,
    String valuationMethod,
    BigDecimal qty,
    BigDecimal value) {}
