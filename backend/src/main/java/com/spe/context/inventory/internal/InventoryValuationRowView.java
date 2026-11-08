package com.spe.context.inventory.internal;

import com.spe.context.masterdata.api.ValuationMethod;
import java.math.BigDecimal;

/** 估值报表行。 */
public record InventoryValuationRowView(
    long warehouseId,
    long itemId,
    String itemCode,
    String itemName,
    long lotId,
    String lotNo,
    ValuationMethod method,
    BigDecimal qty,
    BigDecimal value) {}
