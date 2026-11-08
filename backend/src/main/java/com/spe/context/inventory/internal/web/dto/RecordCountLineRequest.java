package com.spe.context.inventory.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 单行实盘录入。 */
public record RecordCountLineRequest(
    @NotNull(message = "盘点行不能为空") Long lineId,
    @NotNull(message = "实盘数量不能为空")
        @DecimalMin(value = "0.0", message = "实盘数量不能为负")
        BigDecimal countedQty,
    @Size(max = 255, message = "差异原因长度不得超过 255") String reason) {}
