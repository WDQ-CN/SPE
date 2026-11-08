package com.spe.context.sales.api.events;

import com.spe.common.event.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 销售发票已开具：财务生成 AR/收入/销项税。 */
public record SalesInvoicedEvent(
    long invoiceId,
    long companyId,
    long customerId,
    String currency,
    BigDecimal netAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    LocalDate businessDate)
    implements DomainEvent {}
