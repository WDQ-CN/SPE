package com.spe.context.procurement.internal;

import com.spe.context.procurement.api.GoodsReceiptStatus;
import java.time.LocalDateTime;
import java.util.List;

/** 收货单详情投影。 */
public record GoodsReceiptView(
    long id,
    String docNo,
    long poId,
    String poDocNo,
    long companyId,
    String companyName,
    long warehouseId,
    String warehouseName,
    GoodsReceiptStatus status,
    long createdBy,
    String createdByName,
    LocalDateTime receivedAt,
    Long reversedBy,
    String reversedByName,
    LocalDateTime reversedAt,
    String reverseReason,
    int version,
    List<GoodsReceiptLineView> lines) {}
