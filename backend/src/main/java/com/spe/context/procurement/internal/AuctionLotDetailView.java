package com.spe.context.procurement.internal;

import java.math.BigDecimal;

/** 内部竞价批次。 */
public record AuctionLotDetailView(
    long id,
    int lineNo,
    long itemId,
    String itemCode,
    String itemName,
    BigDecimal qty,
    String uom,
    BigDecimal startPrice,
    BigDecimal reservePrice,
    BigDecimal minDecrement,
    BigDecimal currentLowestAmount,
    int bidCount) {}
