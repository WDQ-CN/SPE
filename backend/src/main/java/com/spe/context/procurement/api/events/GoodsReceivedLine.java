package com.spe.context.procurement.api.events;

import java.math.BigDecimal;

/** 收货事件行载荷：库存台账按行落移动，金额来自 PO 行单价。 */
public record GoodsReceivedLine(
    long goodsReceiptLineId,
    long purchaseOrderLineId,
    long itemId,
    BigDecimal qty,
    String uom,
    BigDecimal unitCost,
    BigDecimal amount,
    String lotNo,
    Long locationId,
    java.time.LocalDate productionDate,
    java.time.LocalDate expiryDate,
    java.util.List<String> serials) {}
