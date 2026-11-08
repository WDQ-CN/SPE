package com.spe.context.procurement.api.events;

import com.spe.common.event.DomainEvent;
import java.time.LocalDate;
import java.util.List;

/** 收货已冲销（W3）：库存据此生成 REVERSAL/OUT 移动并回退余额。 */
public record GoodsReceiptReversedEvent(
    long goodsReceiptId,
    long purchaseOrderId,
    long companyId,
    long warehouseId,
    String currency,
    LocalDate businessDate,
    String reason,
    List<GoodsReceivedLine> lines)
    implements DomainEvent {}
