package com.spe.context.procurement.internal;

import java.math.BigDecimal;

/** 竞价批次命令。 */
public record AuctionLotCommand(
    long itemId,
    BigDecimal qty,
    String uom,
    BigDecimal startPrice,
    BigDecimal reservePrice,
    BigDecimal minDecrement) {}
