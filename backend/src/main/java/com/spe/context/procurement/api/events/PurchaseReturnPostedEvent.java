package com.spe.context.procurement.api.events;

import com.spe.common.event.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 采购退货已过账（W3 → inventory/finance）。
 *
 * <p>`invoiced` 决定财务方向：已开票借记 AP，未开票借记 GR/IR；库存一律 RETURN/OUT。
 */
public record PurchaseReturnPostedEvent(
    long purchaseReturnId,
    long purchaseOrderId,
    long companyId,
    long warehouseId,
    String currency,
    BigDecimal netAmount,
    boolean invoiced,
    LocalDate businessDate,
    String reason,
    List<PurchaseReturnedLine> lines)
    implements DomainEvent {}
