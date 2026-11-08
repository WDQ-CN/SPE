package com.spe.context.procurement.internal;

import com.spe.context.procurement.api.GoodsReceiptStatus;
import java.time.LocalDateTime;

/** 收货单列表投影（不含行）。 */
public record GoodsReceiptSummaryView(
    long id,
    String docNo,
    long poId,
    String poDocNo,
    long companyId,
    String companyName,
    long warehouseId,
    String warehouseName,
    GoodsReceiptStatus status,
    LocalDateTime receivedAt) {}
