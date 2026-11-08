package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 请购行。 */
public record RequisitionLineRequest(
    @Positive(message = "物料 id 必须为正") long itemId,
    @NotNull(message = "数量不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "数量必须大于 0")
        @Digits(integer = 13, fraction = 6, message = "数量精度不得超过 6 位小数")
        BigDecimal qty,
    @Size(max = 16, message = "单位长度不得超过 16") String uom,
    @NotNull(message = "预估单价不能为空")
        @DecimalMin(value = "0", message = "预估单价不能为负")
        @Digits(integer = 15, fraction = 4, message = "单价精度不得超过 4 位小数")
        BigDecimal estimatedPrice) {}
