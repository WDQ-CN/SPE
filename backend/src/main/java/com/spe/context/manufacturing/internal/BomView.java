package com.spe.context.manufacturing.internal;

import java.time.LocalDateTime;
import java.util.List;

/** BOM 详情投影。 */
public record BomView(
    long id,
    String bomNo,
    long companyId,
    long itemId,
    String itemCode,
    String itemName,
    String version,
    BomStatus status,
    LocalDateTime createdAt,
    LocalDateTime activatedAt,
    List<BomLineView> lines) {}
