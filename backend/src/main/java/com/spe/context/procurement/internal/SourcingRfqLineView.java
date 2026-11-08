package com.spe.context.procurement.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/** RFQ 行投影。 */
public record SourcingRfqLineView(
    long id,
    int lineNo,
    long itemId,
    String itemCode,
    String itemName,
    BigDecimal qty,
    String uom,
    LocalDate needDate,
    BigDecimal targetPrice,
    String spec) {}
