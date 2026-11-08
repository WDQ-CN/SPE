package com.spe.context.inventory.api;

import java.math.BigDecimal;

/** 销售/其他上下文请求库存出库的命令；成本由库存按当前移动平均计算。 */
public record InventoryIssueCommand(
    long companyId,
    long warehouseId,
    long locationId,
    long itemId,
    String lotNo,
    BigDecimal qty,
    String currency,
    String sourceType,
    long sourceId,
    long sourceLineId) {}
