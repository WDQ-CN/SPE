package com.spe.context.manufacturing.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 工单详情投影。 */
public record WorkOrderView(
    long id,
    String orderNo,
    long companyId,
    long plantId,
    long itemId,
    String itemCode,
    String itemName,
    long bomId,
    BigDecimal qty,
    WorkOrderStatus status,
    BigDecimal materialCost,
    BigDecimal receivedQty,
    int version,
    LocalDateTime createdAt,
    LocalDateTime releasedAt,
    LocalDateTime completedAt,
    List<WorkOrderComponentView> components,
    List<ProductionIssueView> issues,
    List<ProductionReceiptView> receipts) {}
