package com.spe.context.inventory.api;

import java.math.BigDecimal;

/** 出库结果：来源行 + 库存当前移动平均成本。 */
public record InventoryIssueResult(
    long sourceLineId, long movementId, BigDecimal unitCost, BigDecimal amount) {}
