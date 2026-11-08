package com.spe.context.procurement.internal;

import java.math.BigDecimal;

/** 采购订单行投影（含收货/开票进度）。 */
public record PurchaseOrderLineView(
    long id,
    int lineNo,
    long itemId,
    String itemCode,
    String itemName,
    BigDecimal qty,
    String uom,
    BigDecimal price,
    BigDecimal amount,
    BigDecimal receivedQty,
    BigDecimal invoicedQty) {}
