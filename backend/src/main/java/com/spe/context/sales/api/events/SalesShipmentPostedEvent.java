package com.spe.context.sales.api.events;

import com.spe.common.event.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 销售发货已过账：财务按出库成本生成 COGS/库存。 */
public record SalesShipmentPostedEvent(
    long shipmentId,
    long companyId,
    String currency,
    BigDecimal totalCost,
    LocalDate businessDate)
    implements DomainEvent {}
