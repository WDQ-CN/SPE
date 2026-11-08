package com.spe.context.procurement.internal;

import java.math.BigDecimal;

/** 请购行命令（Controller DTO → Service 的内部形状）。 */
public record RequisitionLineCommand(
    long itemId, BigDecimal qty, String uom, BigDecimal estimatedPrice) {}
