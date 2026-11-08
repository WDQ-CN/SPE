package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 定标行参数。 */
public record CreateAwardLineRequest(
    @NotNull(message = "RFQ 行不能为空") Long rfqLineId,
    @NotNull(message = "报价行不能为空") Long quoteLineId,
    @NotNull(message = "定标数量不能为空")
        @DecimalMin(value = "0.000001", message = "定标数量必须大于 0")
        BigDecimal awardedQty) {}
