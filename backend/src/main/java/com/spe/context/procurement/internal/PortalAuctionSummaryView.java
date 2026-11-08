package com.spe.context.procurement.internal;

import java.time.LocalDateTime;

/** 门户竞价列表。 */
public record PortalAuctionSummaryView(
    long id,
    String auctionNo,
    String title,
    AuctionStatus status,
    boolean sealed,
    String currency,
    LocalDateTime startAt,
    LocalDateTime endAt,
    long myBidCount) {}
