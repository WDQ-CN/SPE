package com.spe.context.inventory.internal;

import java.math.BigDecimal;

/** 库存余额投影。 */
public record InventoryStockView(
    long id,
    long companyId,
    String companyName,
    long plantId,
    String plantName,
    long warehouseId,
    String warehouseName,
    long itemId,
    String itemCode,
    String itemName,
    long lotId,
    String lotNo,
    long locationId,
    String locationCode,
    String locationName,
    String uom,
    BigDecimal qtyOnHand,
    BigDecimal qtyReserved,
    BigDecimal qtyFrozen,
    BigDecimal availableQty,
    BigDecimal totalValue,
    BigDecimal avgUnitCost,
    int version) {}
