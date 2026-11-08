package com.spe.context.inventory.internal;

import java.math.BigDecimal;
import java.util.List;

/** 库存估值报表。 */
public record InventoryValuationReportView(
    long companyId, Long warehouseId, List<InventoryValuationRowView> rows, BigDecimal totalValue) {}
