package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** RFQ 行创建参数。 */
public record CreateRfqLineRequest(
    @NotNull(message = "物料不能为空") Long itemId,
    @NotNull(message = "数量不能为空")
        @DecimalMin(value = "0.000001", message = "数量必须大于 0")
        BigDecimal qty,
    @NotBlank(message = "单位不能为空") @Size(max = 16, message = "单位长度不得超过 16") String uom,
    LocalDate needDate,
    @DecimalMin(value = "0.00", message = "目标价不能为负") BigDecimal targetPrice,
    @Size(max = 255, message = "规格说明长度不得超过 255") String spec) {}
