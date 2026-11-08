package com.spe.context.inventory.internal;

import java.time.LocalDateTime;

/** 序列号投影。 */
public record InventorySerialView(
    long id,
    String serialNo,
    long itemId,
    String itemCode,
    String itemName,
    long lotId,
    String lotNo,
    Long supplierId,
    String supplierName,
    long warehouseId,
    String warehouseName,
    long locationId,
    String locationName,
    InventorySerialStatus status,
    String sourceType,
    long sourceId,
    LocalDateTime createdAt) {}
