package com.spe.context.procurement.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 合同消耗投影。 */
public record ContractConsumptionView(
    long id,
    long contractLineId,
    long poId,
    long poLineId,
    BigDecimal qty,
    BigDecimal amount,
    LocalDateTime consumedAt) {}
