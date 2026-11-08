package com.spe.context.procurement.internal;

import java.time.LocalDateTime;
import java.util.List;

/** 内部竞价详情。 */
public record AuctionDetailView(
    long id,
    String auctionNo,
    String title,
    AuctionStatus status,
    boolean sealed,
    String currency,
    LocalDateTime startAt,
    LocalDateTime endAt,
    int antiSnipingSeconds,
    int maxExtensions,
    int extensionCount,
    int version,
    List<AuctionLotDetailView> lots,
    List<Long> supplierIds) {}
