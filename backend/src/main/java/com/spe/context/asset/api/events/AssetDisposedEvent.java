package com.spe.context.asset.api.events;

import com.spe.common.event.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 资产处置（当前仅发布事件，GL 处置分录待配置专用映射）。 */
public record AssetDisposedEvent(
    long assetId,
    long companyId,
    BigDecimal cost,
    BigDecimal accumulated,
    BigDecimal proceeds,
    BigDecimal gainLoss,
    String currency,
    LocalDate businessDate)
    implements DomainEvent {}
