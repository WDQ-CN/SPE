package com.spe.context.sales.internal;

import java.time.LocalDateTime;
import java.util.List;

/** 发货详情投影。 */
public record SalesShipmentView(
    long id,
    String shipmentNo,
    long companyId,
    long customerId,
    long orderId,
    long warehouseId,
    SalesShipmentStatus status,
    LocalDateTime shippedAt,
    int version,
    List<SalesShipmentLineView> lines) {}
