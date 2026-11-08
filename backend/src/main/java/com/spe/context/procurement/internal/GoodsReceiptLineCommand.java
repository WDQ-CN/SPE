package com.spe.context.procurement.internal;

import java.math.BigDecimal;

/** 收货行命令：只引用 PO 行与数量，物料/单位从 PO 行取，避免收货时篡改。 */
public record GoodsReceiptLineCommand(
    long poLineId,
    BigDecimal qty,
    String lotNo,
    java.time.LocalDate productionDate,
    java.time.LocalDate expiryDate,
    Long locationId,
    java.util.List<String> serials) {}
