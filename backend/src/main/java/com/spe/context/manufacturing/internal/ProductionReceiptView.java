package com.spe.context.manufacturing.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 完工入库投影。 */
public record ProductionReceiptView(
    long id,
    String receiptNo,
    long warehouseId,
    long locationId,
    String lotNo,
    BigDecimal qty,
    BigDecimal unitCost,
    BigDecimal amount,
    ProductionReceiptStatus status,
    LocalDateTime createdAt) {}
