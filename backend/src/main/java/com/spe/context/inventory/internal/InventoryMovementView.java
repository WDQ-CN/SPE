package com.spe.context.inventory.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 库存移动投影（不可变台账）。 */
public record InventoryMovementView(
    long id,
    String movementNo,
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
    InventoryMovementType movementType,
    MovementDirection direction,
    BigDecimal qty,
    String uom,
    BigDecimal unitCost,
    BigDecimal amount,
    String currency,
    String sourceType,
    long sourceId,
    long sourceLineId,
    LocalDateTime occurredAt) {}
