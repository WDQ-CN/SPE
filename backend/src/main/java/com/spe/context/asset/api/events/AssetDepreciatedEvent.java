package com.spe.context.asset.api.events;

import com.spe.common.event.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 资产折旧：借折旧费用，贷累计折旧。 */
public record AssetDepreciatedEvent(
    long assetId,
    long depreciationId,
    long companyId,
    String period,
    BigDecimal amount,
    BigDecimal accumulated,
    String currency,
    LocalDate businessDate)
    implements DomainEvent {}
