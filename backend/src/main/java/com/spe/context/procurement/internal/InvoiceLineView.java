package com.spe.context.procurement.internal;

import java.math.BigDecimal;

/** 供应商发票行投影。 */
public record InvoiceLineView(
    long id,
    int lineNo,
    long poLineId,
    long itemId,
    String itemCode,
    String itemName,
    BigDecimal qty,
    BigDecimal unitPrice,
    BigDecimal amount) {}
