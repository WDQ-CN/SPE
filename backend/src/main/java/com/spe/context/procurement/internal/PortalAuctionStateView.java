package com.spe.context.procurement.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 门户竞价状态轮询（docs/erp/56 §6.2）；密封模式只返回自己的最后出价。 */
public record PortalAuctionStateView(
    AuctionStatus status,
    LocalDateTime serverTime,
    LocalDateTime endAt,
    long remainingMs,
    boolean sealed,
    Integer myRank,
    Long participantCount,
    BigDecimal lowestAmount,
    BigDecimal nextMinAmount,
    BigDecimal myLastAmount,
    int extensionCount,
    boolean extended) {}
