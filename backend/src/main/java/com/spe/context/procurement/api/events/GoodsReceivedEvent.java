package com.spe.context.procurement.api.events;

import com.spe.common.event.DomainEvent;

/**
 * 收货已过账（W2 → W3/W5 的集成事件）。
 *
 * <p>W5 用它生成 GR/IR 分录；W3 用它按行生成库存 RECEIPT 移动。载荷带仓库与行明细，
 * 消费方不必反查采购内部；事件与收货写在同一事务（05 ADR-9），失败随主事务回滚。
 */
public record GoodsReceivedEvent(
    long goodsReceiptId,
    long purchaseOrderId,
    long companyId,
    long supplierId,
    long warehouseId,
    String currency,
    java.math.BigDecimal netAmount,
    java.time.LocalDate businessDate,
    java.util.List<GoodsReceivedLine> lines)
    implements DomainEvent {}
