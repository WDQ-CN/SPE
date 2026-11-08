package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 门户报价行。 */
public record PortalQuoteLineRequest(
    @NotNull(message = "RFQ 行不能为空") Long rfqLineId,
    @NotNull(message = "单价不能为空")
        @DecimalMin(value = "0.00", message = "单价不能为负")
        BigDecimal unitPrice,
    @DecimalMin(value = "0.0", message = "税率不能为负")
        @DecimalMax(value = "1.0", message = "税率不能超过 1")
        BigDecimal taxRate,
    Integer leadTimeDays,
    @DecimalMin(value = "0.0", message = "MOQ 不能为负") BigDecimal moq,
    @DecimalMin(value = "0.0", message = "可供数量不能为负") BigDecimal qtyAvailable,
    @Size(max = 16, message = "贸易术语长度不得超过 16") String incoterm,
    @Size(max = 255, message = "备注长度不得超过 255") String remark) {}
