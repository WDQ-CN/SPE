package com.spe.context.inventory.api;

import java.math.BigDecimal;

/** 库存入库命令（销售退货/生产完工等），成本由调用方按原始成本或标准成本给出。 */
public record InventoryReceiveCommand(
    long companyId,
    long warehouseId,
    long locationId,
    long itemId,
    String lotNo,
    BigDecimal qty,
    BigDecimal unitCost,
    String currency,
    String sourceType,
    long sourceId,
    long sourceLineId) {}
