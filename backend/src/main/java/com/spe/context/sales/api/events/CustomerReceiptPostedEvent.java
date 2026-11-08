package com.spe.context.sales.api.events;

import com.spe.common.event.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 客户收款已过账：财务生成银行/AR。 */
public record CustomerReceiptPostedEvent(
    long receiptId,
    long companyId,
    long customerId,
    String currency,
    BigDecimal amount,
    LocalDate businessDate)
    implements DomainEvent {}
