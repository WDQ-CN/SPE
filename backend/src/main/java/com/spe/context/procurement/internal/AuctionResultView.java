package com.spe.context.procurement.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 竞价结果投影。 */
public record AuctionResultView(
    long id,
    long auctionId,
    long lotId,
    long supplierId,
    String supplierName,
    long bidId,
    BigDecimal amount,
    AuctionResultStatus status,
    Long approvedBy,
    LocalDateTime approvedAt,
    int version,
    LocalDateTime createdAt) {}
