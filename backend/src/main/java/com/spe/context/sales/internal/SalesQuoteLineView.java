package com.spe.context.sales.internal;

import java.math.BigDecimal;

/** 销售报价行投影。 */
public record SalesQuoteLineView(
    long id,
    int lineNo,
    long itemId,
    String itemCode,
    String itemName,
    BigDecimal qty,
    String uom,
    BigDecimal unitPrice,
    BigDecimal discountRate,
    BigDecimal taxRate,
    BigDecimal netAmount,
    BigDecimal taxAmount) {}
