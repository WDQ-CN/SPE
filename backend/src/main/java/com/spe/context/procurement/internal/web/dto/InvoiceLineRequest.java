package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** 供应商发票行。 */
public record InvoiceLineRequest(
    @Positive(message = "订单行 id 必须为正") long poLineId,
    @NotNull(message = "开票数量不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "开票数量必须大于 0")
        @Digits(integer = 13, fraction = 6, message = "数量精度不得超过 6 位小数")
        BigDecimal qty,
    @NotNull(message = "开票单价不能为空")
        @DecimalMin(value = "0", message = "开票单价不能为负")
        @Digits(integer = 15, fraction = 4, message = "单价精度不得超过 4 位小数")
        BigDecimal unitPrice) {}
