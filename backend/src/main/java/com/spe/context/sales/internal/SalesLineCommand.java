package com.spe.context.sales.internal;

import java.math.BigDecimal;

/** 销售报价/订单行命令。 */
public record SalesLineCommand(
    long itemId,
    BigDecimal qty,
    String uom,
    BigDecimal unitPrice,
    BigDecimal discountRate,
    BigDecimal taxRate) {}
