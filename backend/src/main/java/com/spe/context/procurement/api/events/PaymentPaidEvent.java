package com.spe.context.procurement.api.events;

import com.spe.common.event.DomainEvent;
import java.math.BigDecimal;

/** 付款已登记（W4 → W5）：财务据此生成 AP / 银行分录。 */
public record PaymentPaidEvent(
    long paymentId,
    long invoiceId,
    long companyId,
    String currency,
    BigDecimal amount,
    java.time.LocalDate businessDate)
    implements DomainEvent {}
