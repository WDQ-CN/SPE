package com.spe.context.procurement.internal;

import java.math.BigDecimal;

/** 门户竞价批次。 */
public record PortalAuctionLotView(
    long id,
    int lineNo,
    String itemCode,
    String itemName,
    BigDecimal qty,
    String uom,
    BigDecimal startPrice,
    BigDecimal minDecrement,
    BigDecimal currentLowestAmount,
    BigDecimal myLastAmount,
    Integer myRank) {}
