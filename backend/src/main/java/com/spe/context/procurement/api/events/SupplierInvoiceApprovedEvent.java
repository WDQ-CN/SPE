package com.spe.context.procurement.api.events;

import com.spe.common.event.DomainEvent;
import java.math.BigDecimal;

/**
 * 供应商发票已批准（W4 → W5）：财务据此生成 GR/IR、进项税与 AP 分录。
 *
 * <p>事件在审批事务内发布；金额是发票事实，不含行明细。幂等由财务侧的来源单据唯一键保证。
 */
public record SupplierInvoiceApprovedEvent(
    long invoiceId,
    long companyId,
    String currency,
    BigDecimal netAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    java.time.LocalDate businessDate)
    implements DomainEvent {}
