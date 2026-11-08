package com.spe.context.inventory.internal;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 批次投影。 */
public record InventoryLotView(
    long id,
    String lotNo,
    long itemId,
    String itemCode,
    String itemName,
    Long supplierId,
    String supplierName,
    LocalDate productionDate,
    LocalDate expiryDate,
    InventoryLotStatus status,
    int version,
    LocalDateTime createdAt) {}
