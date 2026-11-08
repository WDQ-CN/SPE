package com.spe.context.procurement.internal;

import java.math.BigDecimal;

/** 三单匹配结果投影。 */
public record MatchResultView(
    MatchStatus status,
    BigDecimal priceVariance,
    BigDecimal qtyVariance,
    BigDecimal amountVariance,
    String detail) {}
