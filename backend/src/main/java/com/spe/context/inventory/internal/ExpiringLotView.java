package com.spe.context.inventory.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 效期预警行。 */
public record ExpiringLotView(
    long lotId,
    String lotNo,
    long itemId,
    String itemCode,
    String itemName,
    LocalDate expiryDate,
    long daysLeft,
    BigDecimal qtyOnHand) {}
