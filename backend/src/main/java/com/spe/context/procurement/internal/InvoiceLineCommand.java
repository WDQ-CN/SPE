package com.spe.context.procurement.internal;

import java.math.BigDecimal;

/** 发票行命令。 */
public record InvoiceLineCommand(long poLineId, BigDecimal qty, BigDecimal unitPrice) {}
