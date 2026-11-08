package com.spe.context.inventory.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 设置库位容量；itemId=0 表示库位总件数上限。 */
public record UpsertLocationCapacityRequest(
    Long itemId,
    @NotNull(message = "容量上限不能为空")
        @DecimalMin(value = "0.000001", message = "容量上限必须大于 0")
        BigDecimal maxQty,
    @Size(max = 16, message = "单位长度不得超过 16") String uom,
    @Size(max = 16, message = "状态长度不得超过 16") String status) {}
