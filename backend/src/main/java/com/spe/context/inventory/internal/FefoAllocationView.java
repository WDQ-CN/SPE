package com.spe.context.inventory.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/** FEFO 建议行。 */
public record FefoAllocationView(
    long lotId,
    String lotNo,
    LocalDate expiryDate,
    long locationId,
    String locationName,
    BigDecimal availableQty,
    BigDecimal suggestedQty) {}
