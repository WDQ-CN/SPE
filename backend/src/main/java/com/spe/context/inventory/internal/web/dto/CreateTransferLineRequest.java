package com.spe.context.inventory.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 调拨行创建参数。 */
public record CreateTransferLineRequest(
    @NotNull(message = "物料不能为空") Long itemId,
    Long lotId,
    @NotNull(message = "数量不能为空")
        @DecimalMin(value = "0.000001", message = "数量必须大于 0")
        BigDecimal qty,
    @NotBlank(message = "单位不能为空") @Size(max = 16, message = "单位长度不得超过 16") String uom,
    @DecimalMin(value = "0.00", message = "单位成本不能为负") BigDecimal unitCost,
    List<Long> serialIds) {}
