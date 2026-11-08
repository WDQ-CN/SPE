package com.spe.context.sales.internal;

import java.math.BigDecimal;

/** 销售发票行投影。 */
public record SalesInvoiceLineView(
    long id,
    int lineNo,
    long orderLineId,
    long itemId,
    String itemCode,
    String itemName,
    BigDecimal qty,
    BigDecimal unitPrice,
    BigDecimal discountRate,
    BigDecimal taxRate,
    BigDecimal netAmount,
    BigDecimal taxAmount) {}
