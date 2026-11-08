package com.spe.context.procurement.internal;

import java.math.BigDecimal;

/** 采购退货行命令：只给 PO 行与数量，成本取 PO 单价。 */
public record PurchaseReturnLineCommand(
    long poLineId,
    BigDecimal qty,
    String lotNo,
    Long locationId,
    java.util.List<String> serials) {}
