package com.spe.context.sales.internal.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 销售报价/订单行。 */
public record SalesLineRequest(
    @NotNull(message = "物料不能为空") Long itemId,
    @NotNull(message = "数量不能为空")
        @DecimalMin(value = "0.000001", message = "数量必须大于 0")
        BigDecimal qty,
    @Size(max = 16, message = "单位长度不得超过 16") String uom,
    @NotNull(message = "单价不能为空")
        @DecimalMin(value = "0.00", message = "单价不能为负")
        BigDecimal unitPrice,
    @DecimalMin(value = "0.0", message = "折扣率不能为负")
        @DecimalMax(value = "1.0", message = "折扣率不能超过 1")
        BigDecimal discountRate,
    @DecimalMin(value = "0.0", message = "税率不能为负")
        @DecimalMax(value = "1.0", message = "税率不能超过 1")
        BigDecimal taxRate) {}
