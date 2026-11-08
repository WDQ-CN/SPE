package com.spe.context.manufacturing.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 生产领料投影。 */
public record ProductionIssueView(
    long id,
    long componentId,
    long itemId,
    String lotNo,
    long locationId,
    BigDecimal qty,
    BigDecimal unitCost,
    BigDecimal amount,
    LocalDateTime createdAt) {}
