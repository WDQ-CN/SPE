package com.spe.context.manufacturing.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 工单完工入库。 */
public record ReceiptWorkOrderRequest(
    @NotNull(message = "仓库不能为空") Long warehouseId,
    Long locationId,
    @Size(max = 64, message = "批次号长度不得超过 64") String lotNo,
    @NotNull(message = "完工数量不能为空")
        @DecimalMin(value = "0.000001", message = "完工数量必须大于 0")
        BigDecimal qty) {}
