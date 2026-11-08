package com.spe.context.procurement.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 门户自己的出价记录。 */
public record PortalBidView(
    long id, long lotId, BigDecimal amount, AuctionBidStatus status, LocalDateTime submittedAt) {}
