package com.spe.context.procurement.api.events;

import java.math.BigDecimal;

/** 采购退货行载荷：库存按行生成 RETURN/OUT，金额来自 PO 单价。 */
public record PurchaseReturnedLine(
    long purchaseReturnLineId,
    long purchaseOrderLineId,
    long itemId,
    BigDecimal qty,
    String uom,
    BigDecimal unitCost,
    BigDecimal amount,
    String lotNo,
    Long locationId,
    java.util.List<String> serials) {}
