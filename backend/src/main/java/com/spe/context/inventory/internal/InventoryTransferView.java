package com.spe.context.inventory.internal;

import java.time.LocalDateTime;
import java.util.List;

/** 调拨单详情投影。 */
public record InventoryTransferView(
    long id,
    String transferNo,
    long companyId,
    long fromPlantId,
    long fromWarehouseId,
    long fromLocationId,
    long toPlantId,
    long toWarehouseId,
    long toLocationId,
    InventoryTransferStatus status,
    String reason,
    LocalDateTime shippedAt,
    LocalDateTime receivedAt,
    int version,
    LocalDateTime createdAt,
    List<InventoryTransferLineView> lines) {}
