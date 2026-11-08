package com.spe.context.sales.api.events;

import com.spe.common.event.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 销售退货已贷项：财务冲减收入/销项税/AR，并恢复库存/成本。 */
public record SalesReturnCreditedEvent(
    long returnId,
    long companyId,
    long customerId,
    String currency,
    BigDecimal netAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    BigDecimal costTotal,
    LocalDate businessDate)
    implements DomainEvent {}
