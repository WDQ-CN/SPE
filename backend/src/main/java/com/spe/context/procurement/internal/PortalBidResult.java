package com.spe.context.procurement.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 出价结果；密封模式 rank/lowest/nextMin 为 null。 */
public record PortalBidResult(
    long bidId,
    long lotId,
    AuctionBidStatus status,
    BigDecimal amount,
    Integer rank,
    Long participantCount,
    BigDecimal lowestAmount,
    BigDecimal nextMinAmount,
    LocalDateTime serverTime,
    LocalDateTime endAt,
    boolean sealed,
    boolean extended,
    int extensionCount) {}
