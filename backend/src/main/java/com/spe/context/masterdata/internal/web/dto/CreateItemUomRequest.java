package com.spe.context.masterdata.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 新增物料计量单位换算。 */
public record CreateItemUomRequest(
    @NotBlank(message = "源单位不能为空") @Size(max = 16, message = "单位长度不得超过 16") String fromUom,
    @NotBlank(message = "目标单位不能为空") @Size(max = 16, message = "单位长度不得超过 16") String toUom,
    @NotNull(message = "换算分子不能为空")
        @DecimalMin(value = "0.000001", message = "换算分子必须大于 0")
        BigDecimal numerator,
    @NotNull(message = "换算分母不能为空")
        @DecimalMin(value = "0.000001", message = "换算分母必须大于 0")
        BigDecimal denominator) {}
