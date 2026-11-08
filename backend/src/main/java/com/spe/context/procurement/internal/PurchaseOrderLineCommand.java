package com.spe.context.procurement.internal;

import java.math.BigDecimal;

/** 采购订单行命令。 */
public record PurchaseOrderLineCommand(long itemId, BigDecimal qty, String uom, BigDecimal price) {}
