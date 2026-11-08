package com.spe.context.sales.internal;

import java.math.BigDecimal;

/** 发货行投影。 */
public record SalesShipmentLineView(
    long id,
    int lineNo,
    long orderLineId,
    long itemId,
    String lotNo,
    long locationId,
    BigDecimal qty) {}
