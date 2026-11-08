package com.spe.context.procurement.internal;

import java.math.BigDecimal;

/** 报价行投影。 */
public record SupplierQuoteLineView(
    long id,
    long rfqLineId,
    BigDecimal unitPrice,
    BigDecimal taxRate,
    BigDecimal taxAmount,
    Integer leadTimeDays,
    BigDecimal moq,
    BigDecimal qtyAvailable,
    String incoterm,
    String remark) {}
