package com.spe.context.sales.internal;

import java.math.BigDecimal;

/** 销售退货行投影。 */
public record SalesReturnLineView(
    long id,
    int lineNo,
    long shipmentLineId,
    long orderLineId,
    long itemId,
    BigDecimal qty,
    BigDecimal unitPrice,
    BigDecimal discountRate,
    BigDecimal taxRate,
    BigDecimal netAmount,
    BigDecimal taxAmount,
    String reason) {}
