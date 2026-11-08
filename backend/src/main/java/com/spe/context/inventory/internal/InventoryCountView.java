package com.spe.context.inventory.internal;

import java.time.LocalDateTime;
import java.util.List;

/** 盘点单详情投影。 */
public record InventoryCountView(
    long id,
    String countNo,
    long companyId,
    long warehouseId,
    InventoryCountStatus status,
    LocalDateTime snapshotAt,
    String reason,
    int version,
    LocalDateTime createdAt,
    List<InventoryCountLineView> lines) {}
