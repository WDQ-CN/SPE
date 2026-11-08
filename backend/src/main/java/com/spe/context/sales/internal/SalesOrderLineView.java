package com.spe.context.sales.internal;

import java.math.BigDecimal;

/** 销售订单行投影。 */
public record SalesOrderLineView(
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
    BigDecimal taxAmount,
    BigDecimal shippedQty,
    BigDecimal invoicedQty) {}
