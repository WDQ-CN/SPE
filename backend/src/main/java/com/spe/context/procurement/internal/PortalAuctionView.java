package com.spe.context.procurement.internal;

import java.time.LocalDateTime;
import java.util.List;

/** 门户竞价详情（密封模式下隐藏最低价/排名）。 */
public record PortalAuctionView(
    long id,
    String auctionNo,
    String title,
    AuctionStatus status,
    boolean sealed,
    String currency,
    LocalDateTime startAt,
    LocalDateTime endAt,
    LocalDateTime serverTime,
    int antiSnipingSeconds,
    int maxExtensions,
    int extensionCount,
    List<PortalAuctionLotView> lots) {}
