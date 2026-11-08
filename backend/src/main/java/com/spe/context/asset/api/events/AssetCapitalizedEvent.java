package com.spe.context.asset.api.events;

import com.spe.common.event.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 资产资本化：借固定资产，贷来源科目。 */
public record AssetCapitalizedEvent(
    long assetId, long companyId, BigDecimal cost, String currency, LocalDate businessDate)
    implements DomainEvent {}
